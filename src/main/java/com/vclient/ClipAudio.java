package com.vclient;

import com.sun.jna.Function;
import com.sun.jna.Memory;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.TargetDataLine;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/**
 * Keeps the last seconds of sound for the replay buffer: everything the PC is playing (WASAPI loopback, so game sound
 * is included) and, when the player turned it on, the microphone. Both are held in memory as 48 kHz stereo 16-bit
 * chunks stamped with the time they were heard, so a clip can be cut to exactly the span of its video frames.
 */
final class ClipAudio {
    static final int RATE = 48000;
    private static final long KEEP_MILLIS = (ReplayBuffer.SECONDS + 3) * 1000L;

    private record Chunk(long startMillis, short[] stereo) {}

    private final BooleanSupplier micEnabled;
    private final ArrayDeque<Chunk> system = new ArrayDeque<>();
    private final ArrayDeque<Chunk> mic = new ArrayDeque<>();
    private volatile boolean running;
    private volatile boolean keeping;
    private Thread systemThread;
    private Thread micThread;

    ClipAudio(BooleanSupplier micEnabled) {
        this.micEnabled = micEnabled;
    }

    synchronized void start() {
        if (running) return;
        running = true;
        clear();
        systemThread = new Thread(this::systemLoop, "vclient-clip-audio-system");
        systemThread.setDaemon(true);
        systemThread.start();
        micThread = new Thread(this::micLoop, "vclient-clip-audio-mic");
        micThread.setDaemon(true);
        micThread.start();
    }

    synchronized void stop() {
        running = false;
        if (systemThread != null) systemThread.interrupt();
        if (micThread != null) micThread.interrupt();
        systemThread = null;
        micThread = null;
        clear();
    }

    /** Sound is only kept while the game window is the one being recorded, matching what the video shows. */
    void keeping(boolean keep) {
        keeping = keep;
    }

    private void clear() {
        synchronized (system) {
            system.clear();
        }
        synchronized (mic) {
            mic.clear();
        }
    }

    private void add(ArrayDeque<Chunk> queue, short[] stereo, long endMillis) {
        if (!keeping || stereo.length == 0) return;
        long start = endMillis - (long) (stereo.length / 2) * 1000L / RATE;
        synchronized (queue) {
            // Never let a chunk start before the previous one ended (timing jitter would double that stretch).
            Chunk previous = queue.peekLast();
            if (previous != null) start = Math.max(start, previous.startMillis() + (long) (previous.stereo().length / 2) * 1000L / RATE);
            queue.addLast(new Chunk(start, stereo));
            while (!queue.isEmpty() && endMillis - queue.peekFirst().startMillis() > KEEP_MILLIS) queue.removeFirst();
        }
    }

    /**
     * The sound from {@code startMillis} for {@code sampleFrames} frames as interleaved 48 kHz stereo, with silence
     * where nothing was playing. Returns null when no sound was heard at all, so silent clips get no audio track.
     */
    short[] mix(long startMillis, int sampleFrames) {
        int[] sum = new int[sampleFrames * 2];
        boolean heard = false;
        heard |= place(system, sum, startMillis, 1.0);
        if (micEnabled.getAsBoolean()) heard |= place(mic, sum, startMillis, 1.0);
        if (!heard) return null;
        short[] out = new short[sum.length];
        for (int i = 0; i < sum.length; i++) out[i] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, sum[i]));
        return out;
    }

    private static boolean place(ArrayDeque<Chunk> queue, int[] sum, long startMillis, double gain) {
        List<Chunk> chunks;
        synchronized (queue) {
            chunks = new ArrayList<>(queue);
        }
        boolean placed = false;
        for (Chunk chunk : chunks) {
            long offsetFrames = (chunk.startMillis() - startMillis) * RATE / 1000L;
            int frames = chunk.stereo().length / 2;
            if (offsetFrames + frames <= 0 || offsetFrames >= sum.length / 2) continue;
            short[] data = chunk.stereo();
            for (int i = 0; i < data.length; i++) {
                long index = offsetFrames * 2 + i;
                if (index < 0 || index >= sum.length) continue;
                sum[(int) index] += (int) (data[i] * gain);
                placed = true;
            }
        }
        return placed;
    }

    // ---- microphone ------------------------------------------------------------------------------

    private void micLoop() {
        TargetDataLine line = null;
        int lineRate = 0;
        while (running) {
            try {
                if (!micEnabled.getAsBoolean()) {
                    if (line != null) {
                        line.stop();
                        line.close();
                        line = null;
                        synchronized (mic) {
                            mic.clear();
                        }
                    }
                    Thread.sleep(1000);
                    continue;
                }
                if (line == null) {
                    for (int rate : new int[]{RATE, 44100}) {
                        AudioFormat format = new AudioFormat(rate, 16, 1, true, false);
                        DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);
                        if (!AudioSystem.isLineSupported(info)) continue;
                        TargetDataLine candidate = (TargetDataLine) AudioSystem.getLine(info);
                        candidate.open(format, rate / 5 * 2);
                        candidate.start();
                        line = candidate;
                        lineRate = rate;
                        ClipLog.write("microphone open at " + rate + " Hz");
                        break;
                    }
                    if (line == null) {
                        ClipLog.write("no microphone available");
                        Thread.sleep(5000);
                        continue;
                    }
                }
                byte[] block = new byte[lineRate / 50 * 2]; // 20 ms
                int read = line.read(block, 0, block.length);
                if (read <= 0) continue;
                long now = System.currentTimeMillis();
                int frames = read / 2;
                short[] stereo = new short[frames * 2];
                for (int i = 0; i < frames; i++) {
                    short sample = (short) ((block[i * 2] & 0xFF) | (block[i * 2 + 1] << 8));
                    stereo[i * 2] = sample;
                    stereo[i * 2 + 1] = sample;
                }
                add(mic, resample(stereo, lineRate), now);
            } catch (InterruptedException e) {
                break;
            } catch (Throwable e) {
                ClipLog.write("microphone error: " + e);
                if (line != null) {
                    try {
                        line.close();
                    } catch (Throwable ignored) {
                        // closing a broken line
                    }
                    line = null;
                }
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException interrupted) {
                    break;
                }
            }
        }
        if (line != null) line.close();
    }

    /** Linear-interpolation conversion of interleaved stereo to {@link #RATE}. */
    private static short[] resample(short[] stereo, int fromRate) {
        if (fromRate == RATE) return stereo;
        int inFrames = stereo.length / 2;
        int outFrames = (int) ((long) inFrames * RATE / fromRate);
        short[] out = new short[outFrames * 2];
        for (int i = 0; i < outFrames; i++) {
            double position = (double) i * fromRate / RATE;
            int a = Math.min(inFrames - 1, (int) position);
            int b = Math.min(inFrames - 1, a + 1);
            double fraction = position - a;
            for (int channel = 0; channel < 2; channel++) {
                out[i * 2 + channel] = (short) (stereo[a * 2 + channel] * (1 - fraction) + stereo[b * 2 + channel] * fraction);
            }
        }
        return out;
    }

    // ---- system sound: WASAPI loopback ---------------------------------------------------------------

    private static final int POINTER_SIZE = com.sun.jna.Native.POINTER_SIZE;
    private static final int AUDCLNT_STREAMFLAGS_LOOPBACK = 0x00020000;
    private static final int AUDCLNT_BUFFERFLAGS_SILENT = 0x2;

    private void systemLoop() {
        NativeLibrary ole;
        try {
            ole = NativeLibrary.getInstance("ole32");
        } catch (Throwable e) {
            ClipLog.write("system audio unavailable: " + e);
            return;
        }
        while (running) {
            try {
                loopbackSession(ole);
            } catch (InterruptedException e) {
                break;
            } catch (Throwable e) {
                ClipLog.write("system audio error: " + e);
            }
            try {
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                break;
            }
        }
    }

    /** Native scratch memory lives as long as the session so the garbage collector cannot free it during a call. */
    private final Memory iid = new Memory(16);
    private final Memory clsid = new Memory(16);
    private final PointerByReference out = new PointerByReference();
    private final PointerByReference dataOut = new PointerByReference();
    private final IntByReference framesOut = new IntByReference();
    private final IntByReference flagsOut = new IntByReference();

    private void loopbackSession(NativeLibrary ole) throws InterruptedException {
        Function coInitialize = ole.getFunction("CoInitializeEx");
        Function coCreate = ole.getFunction("CoCreateInstance");
        Function coFree = ole.getFunction("CoTaskMemFree");
        coInitialize.invokeInt(new Object[]{null, 0});
        Pointer enumerator = null;
        Pointer device = null;
        Pointer client = null;
        Pointer capture = null;
        Pointer format = null;
        boolean started = false;
        try {
            writeGuid(clsid, "bcde0395-e52f-467c-8e3d-c4579291692e"); // MMDeviceEnumerator
            writeGuid(iid, "a95664d2-9614-4f35-a746-de8db63617e6");   // IMMDeviceEnumerator
            check(coCreate.invokeInt(new Object[]{clsid, null, 23, iid, out}), "CoCreateInstance");
            enumerator = out.getValue();
            check(call(enumerator, 4, 0 /* eRender */, 0 /* eConsole */, out), "GetDefaultAudioEndpoint");
            device = out.getValue();
            writeGuid(iid, "1cb9ad4c-dbfa-4c32-b178-c2f568a703b2");   // IAudioClient
            check(call(device, 3, iid, 23, null, out), "Activate");
            client = out.getValue();
            check(call(client, 8, out), "GetMixFormat");
            format = out.getValue();
            int tag = format.getShort(0) & 0xFFFF;
            int channels = format.getShort(2) & 0xFFFF;
            int rate = format.getInt(4);
            int blockAlign = format.getShort(12) & 0xFFFF;
            int bits = format.getShort(14) & 0xFFFF;
            boolean floating = tag == 3 || (tag == 0xFFFE && format.getInt(24) == 3);
            check(call(client, 3, 0 /* shared */, AUDCLNT_STREAMFLAGS_LOOPBACK, 10_000_000L, 0L, format, null), "Initialize");
            writeGuid(iid, "c8adbd64-e71e-48a0-a4de-185c395cd317");   // IAudioCaptureClient
            check(call(client, 14, iid, out), "GetService");
            capture = out.getValue();
            check(call(client, 10), "Start");
            started = true;
            ClipLog.write("system audio capture open: " + rate + " Hz, " + channels + " ch, " + bits + " bit"
                    + (floating ? " float" : ""));

            IntByReference packet = new IntByReference();
            while (running) {
                Thread.sleep(10);
                List<short[]> pieces = new ArrayList<>();
                int total = 0;
                while (true) {
                    check(call(capture, 5, packet), "GetNextPacketSize");
                    if (packet.getValue() == 0) break;
                    check(call(capture, 3, dataOut, framesOut, flagsOut, null, null), "GetBuffer");
                    int frames = framesOut.getValue();
                    short[] stereo = new short[frames * 2];
                    if ((flagsOut.getValue() & AUDCLNT_BUFFERFLAGS_SILENT) == 0) {
                        byte[] raw = new byte[frames * blockAlign];
                        dataOut.getValue().read(0, raw, 0, raw.length);
                        decode(raw, frames, channels, bits, floating, blockAlign, stereo);
                    }
                    call(capture, 4, frames);
                    pieces.add(stereo);
                    total += stereo.length;
                }
                if (total == 0) continue;
                short[] joined = new short[total];
                int at = 0;
                for (short[] piece : pieces) {
                    System.arraycopy(piece, 0, joined, at, piece.length);
                    at += piece.length;
                }
                add(system, resample(joined, rate), System.currentTimeMillis());
            }
        } finally {
            if (started) call(client, 11);
            if (format != null) coFree.invoke(new Object[]{format});
            release(capture);
            release(client);
            release(device);
            release(enumerator);
        }
    }

    private static void decode(byte[] raw, int frames, int channels, int bits, boolean floating, int blockAlign,
                               short[] stereo) {
        int bytes = bits / 8;
        for (int i = 0; i < frames; i++) {
            int base = i * blockAlign;
            short left = sample(raw, base, bytes, floating);
            short right = channels >= 2 ? sample(raw, base + bytes, bytes, floating) : left;
            stereo[i * 2] = left;
            stereo[i * 2 + 1] = right;
        }
    }

    private static short sample(byte[] raw, int at, int bytes, boolean floating) {
        if (floating && bytes == 4) {
            int bitsValue = (raw[at] & 0xFF) | ((raw[at + 1] & 0xFF) << 8) | ((raw[at + 2] & 0xFF) << 16) | (raw[at + 3] << 24);
            float value = Float.intBitsToFloat(bitsValue);
            return (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, Math.round(value * 32767f)));
        }
        return switch (bytes) {
            case 2 -> (short) ((raw[at] & 0xFF) | (raw[at + 1] << 8));
            case 3 -> (short) ((raw[at + 1] & 0xFF) | (raw[at + 2] << 8));
            case 4 -> (short) ((raw[at + 2] & 0xFF) | (raw[at + 3] << 8));
            default -> 0;
        };
    }

    // ---- COM plumbing (same approach as DxgiGrabber) -----------------------------------------------

    private static int call(Pointer object, int slot, Object... arguments) {
        Pointer method = object.getPointer(0).getPointer((long) slot * POINTER_SIZE);
        Object[] all = new Object[arguments.length + 1];
        all[0] = object;
        System.arraycopy(arguments, 0, all, 1, arguments.length);
        return Function.getFunction(method).invokeInt(all);
    }

    private static void release(Pointer object) {
        if (object != null) call(object, 2);
    }

    private static void check(int result, String what) {
        if (result < 0) throw new IllegalStateException(what + " failed (0x" + Integer.toHexString(result) + ")");
    }

    private static void writeGuid(Memory target, String guid) {
        UUID uuid = UUID.fromString(guid);
        long high = uuid.getMostSignificantBits();
        long low = uuid.getLeastSignificantBits();
        target.setInt(0, (int) (high >>> 32));
        target.setShort(4, (short) (high >>> 16));
        target.setShort(6, (short) high);
        for (int i = 0; i < 8; i++) target.setByte(8 + i, (byte) (low >>> (56 - 8 * i)));
    }
}
