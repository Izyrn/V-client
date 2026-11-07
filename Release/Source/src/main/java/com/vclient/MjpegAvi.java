package com.vclient;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * A minimal Motion-JPEG AVI muxer and reader. The replay buffer stores JPEG frames, and an AVI of JPEGs needs
 * no video encoder, so clips are saved without bundling ffmpeg. VLC, Windows players with an MJPEG codec and
 * V-Client's own player open the result.
 */
final class MjpegAvi {
    private MjpegAvi() {}

    /** Header facts plus where each frame sits in the file. */
    record Info(int width, int height, int frameCount, double framesPerSecond, long[] offsets, int[] sizes,
                int audioRate, int audioChannels, long[] audioOffsets, int[] audioSizes) {
        boolean hasAudio() {
            return audioRate > 0 && audioOffsets.length > 0;
        }

        long durationMillis() {
            return framesPerSecond <= 0 ? 0 : Math.round(frameCount * 1000.0 / framesPerSecond);
        }
    }

    static void write(Path file, List<byte[]> jpegFrames, int width, int height, double framesPerSecond)
            throws IOException {
        write(file, jpegFrames, width, height, framesPerSecond, null, 0);
    }

    /**
     * Writes the frames plus, when {@code audio} is not null, an uncompressed PCM track of interleaved 16-bit stereo
     * samples at {@code audioRate} Hz, cut into one slice per video frame so players keep sound and picture together.
     */
    static void write(Path file, List<byte[]> jpegFrames, int width, int height, double framesPerSecond,
                      short[] audio, int audioRate) throws IOException {
        if (jpegFrames.isEmpty()) throw new IOException("There are no frames to save.");
        int fps = (int) Math.max(1, Math.round(framesPerSecond));
        int microsPerFrame = 1_000_000 / fps;
        long maxFrame = 0;
        long moviSize = 4;
        for (byte[] frame : jpegFrames) {
            maxFrame = Math.max(maxFrame, frame.length);
            moviSize += 8 + padded(frame.length);
        }
        int count = jpegFrames.size();
        boolean withAudio = audio != null && audioRate > 0 && audio.length >= 2;
        int audioFrames = withAudio ? audio.length / 2 : 0;
        // sample-frame index where each video frame's slice starts (the last entry is the end)
        int[] sliceStart = new int[count + 1];
        int audioChunks = 0;
        if (withAudio) {
            for (int i = 0; i <= count; i++) sliceStart[i] = (int) Math.min(audioFrames, (long) i * audioFrames / count);
            for (int i = 0; i < count; i++) {
                int bytes = (sliceStart[i + 1] - sliceStart[i]) * 4;
                if (bytes > 0) {
                    moviSize += 8 + bytes;
                    audioChunks++;
                }
            }
        }

        ByteArrayOutputStream header = new ByteArrayOutputStream();
        // avih
        ByteBuffer avih = le(56);
        avih.putInt(microsPerFrame).putInt((int) Math.min(Integer.MAX_VALUE, maxFrame * fps)).putInt(0)
                .putInt(0x10).putInt(count).putInt(0).putInt(withAudio ? 2 : 1).putInt((int) maxFrame)
                .putInt(width).putInt(height).putInt(0).putInt(0).putInt(0).putInt(0);
        // strh
        ByteBuffer strh = le(56);
        strh.put("vids".getBytes(StandardCharsets.US_ASCII)).put("MJPG".getBytes(StandardCharsets.US_ASCII))
                .putInt(0).putShort((short) 0).putShort((short) 0).putInt(0).putInt(1).putInt(fps).putInt(0)
                .putInt(count).putInt((int) maxFrame).putInt(-1).putInt(0)
                .putShort((short) 0).putShort((short) 0).putShort((short) width).putShort((short) height);
        // strf: BITMAPINFOHEADER
        ByteBuffer strf = le(40);
        strf.putInt(40).putInt(width).putInt(height).putShort((short) 1).putShort((short) 24)
                .put("MJPG".getBytes(StandardCharsets.US_ASCII)).putInt(width * height * 3)
                .putInt(0).putInt(0).putInt(0).putInt(0);

        ByteArrayOutputStream strl = new ByteArrayOutputStream();
        strl.write(ascii("strl"));
        chunk(strl, "strh", strh.array());
        chunk(strl, "strf", strf.array());
        ByteArrayOutputStream hdrl = new ByteArrayOutputStream();
        hdrl.write(ascii("hdrl"));
        chunk(hdrl, "avih", avih.array());
        chunk(hdrl, "LIST", strl.toByteArray());
        if (withAudio) {
            ByteBuffer astrh = le(56);
            astrh.put("auds".getBytes(StandardCharsets.US_ASCII)).putInt(0).putInt(0).putShort((short) 0)
                    .putShort((short) 0).putInt(0).putInt(4).putInt(audioRate * 4).putInt(0).putInt(audioFrames)
                    .putInt(audioRate * 4).putInt(-1).putInt(4).putInt(0).putInt(0);
            ByteBuffer astrf = le(16); // WAVEFORMAT: PCM, 2 channels, 16 bit
            astrf.putShort((short) 1).putShort((short) 2).putInt(audioRate).putInt(audioRate * 4)
                    .putShort((short) 4).putShort((short) 16);
            ByteArrayOutputStream astrl = new ByteArrayOutputStream();
            astrl.write(ascii("strl"));
            chunk(astrl, "strh", astrh.array());
            chunk(astrl, "strf", astrf.array());
            chunk(hdrl, "LIST", astrl.toByteArray());
        }

        long idxSize = 16L * (count + audioChunks);
        long riffSize = 4 + (8 + hdrl.size()) + (8 + moviSize) + (8 + idxSize);
        if (riffSize > 0xFFFFFFFFL) throw new IOException("The clip is too large for an AVI file.");

        Path temporary = file.resolveSibling(file.getFileName() + ".part");
        try (var out = new java.io.BufferedOutputStream(Files.newOutputStream(temporary), 1 << 16)) {
            out.write(ascii("RIFF"));
            out.write(le(4).putInt((int) riffSize).array());
            out.write(ascii("AVI "));
            out.write(ascii("LIST"));
            out.write(le(4).putInt(hdrl.size()).array());
            out.write(hdrl.toByteArray());
            out.write(ascii("LIST"));
            out.write(le(4).putInt((int) moviSize).array());
            out.write(ascii("movi"));
            for (int i = 0; i < count; i++) {
                byte[] frame = jpegFrames.get(i);
                out.write(ascii("00dc"));
                out.write(le(4).putInt(frame.length).array());
                out.write(frame);
                if ((frame.length & 1) == 1) out.write(0);
                int from = sliceStart[i];
                int to = sliceStart[i + 1];
                if (withAudio && to > from) {
                    ByteBuffer slice = le((to - from) * 4);
                    for (int k = from * 2; k < to * 2; k++) slice.putShort(audio[k]);
                    out.write(ascii("01wb"));
                    out.write(le(4).putInt(slice.capacity()).array());
                    out.write(slice.array());
                }
            }
            out.write(ascii("idx1"));
            out.write(le(4).putInt((int) idxSize).array());
            int offset = 4;
            for (int i = 0; i < count; i++) {
                byte[] frame = jpegFrames.get(i);
                out.write(ascii("00dc"));
                out.write(le(12).putInt(0x10).putInt(offset).putInt(frame.length).array());
                offset += 8 + padded(frame.length);
                int bytes = withAudio ? (sliceStart[i + 1] - sliceStart[i]) * 4 : 0;
                if (bytes > 0) {
                    out.write(ascii("01wb"));
                    out.write(le(12).putInt(0x10).putInt(offset).putInt(bytes).array());
                    offset += 8 + bytes;
                }
            }
        } catch (IOException e) {
            Files.deleteIfExists(temporary);
            throw e;
        }
        Files.move(temporary, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    /** Parses an AVI written by {@link #write}; returns null for any other file. */
    static Info read(Path file) {
        try (RandomAccessFile in = new RandomAccessFile(file.toFile(), "r")) {
            if (in.length() < 12) return null;
            byte[] head = new byte[12];
            in.readFully(head);
            if (!tag(head, 0).equals("RIFF") || !tag(head, 8).equals("AVI ")) return null;
            int width = 0;
            int height = 0;
            int microsPerFrame = 0;
            List<long[]> frames = new ArrayList<>();
            List<long[]> sound = new ArrayList<>();
            int audioRate = 0;
            int audioChannels = 0;
            long position = 12;
            long length = in.length();
            while (position + 8 <= length) {
                in.seek(position);
                byte[] chunkHead = new byte[8];
                in.readFully(chunkHead);
                String id = tag(chunkHead, 0);
                long size = ByteBuffer.wrap(chunkHead, 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt() & 0xFFFFFFFFL;
                if (id.equals("LIST")) {
                    byte[] type = new byte[4];
                    in.readFully(type);
                    String listType = new String(type, StandardCharsets.US_ASCII);
                    if (listType.equals("movi")) {
                        long end = Math.min(length, position + 8 + size);
                        long cursor = position + 12;
                        while (cursor + 8 <= end) {
                            in.seek(cursor);
                            byte[] frameHead = new byte[8];
                            in.readFully(frameHead);
                            long frameSize = ByteBuffer.wrap(frameHead, 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt()
                                    & 0xFFFFFFFFL;
                            String frameId = tag(frameHead, 0);
                            if (frameId.endsWith("dc") && cursor + 8 + frameSize <= length) {
                                frames.add(new long[]{cursor + 8, frameSize});
                            } else if (frameId.endsWith("wb") && cursor + 8 + frameSize <= length) {
                                sound.add(new long[]{cursor + 8, frameSize});
                            }
                            cursor += 8 + frameSize + (frameSize & 1);
                        }
                        position += 8 + size + (size & 1);
                    } else {
                        position += 12; // descend into hdrl and friends
                    }
                    continue;
                }
                if (id.equals("avih") && size >= 40) {
                    byte[] body = new byte[(int) size];
                    in.readFully(body);
                    ByteBuffer buffer = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN);
                    microsPerFrame = buffer.getInt(0);
                    width = buffer.getInt(32);
                    height = buffer.getInt(36);
                }
                if (id.equals("strf") && size == 16) {
                    byte[] body = new byte[16];
                    in.readFully(body);
                    ByteBuffer buffer = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN);
                    if (buffer.getShort(0) == 1 && buffer.getShort(14) == 16) {
                        audioChannels = buffer.getShort(2);
                        audioRate = buffer.getInt(4);
                    }
                }
                position += 8 + size + (size & 1);
            }
            if (frames.isEmpty() || microsPerFrame <= 0 || width <= 0 || height <= 0) return null;
            long[] offsets = new long[frames.size()];
            int[] sizes = new int[frames.size()];
            for (int i = 0; i < offsets.length; i++) {
                offsets[i] = frames.get(i)[0];
                sizes[i] = (int) frames.get(i)[1];
            }
            long[] audioOffsets = new long[sound.size()];
            int[] audioSizes = new int[sound.size()];
            for (int i = 0; i < audioOffsets.length; i++) {
                audioOffsets[i] = sound.get(i)[0];
                audioSizes[i] = (int) sound.get(i)[1];
            }
            return new Info(width, height, offsets.length, 1_000_000.0 / microsPerFrame, offsets, sizes,
                    audioRate, audioChannels, audioOffsets, audioSizes);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    static byte[] frame(Path file, Info info, int index) throws IOException {
        try (RandomAccessFile in = new RandomAccessFile(file.toFile(), "r")) {
            in.seek(info.offsets()[index]);
            byte[] data = new byte[info.sizes()[index]];
            in.readFully(data);
            return data;
        }
    }

    /** All the PCM bytes of the audio track, in order (empty when the clip has none). */
    static byte[] audio(Path file, Info info) throws IOException {
        if (!info.hasAudio()) return new byte[0];
        long total = 0;
        for (int size : info.audioSizes()) total += size;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream((int) total);
        try (RandomAccessFile in = new RandomAccessFile(file.toFile(), "r")) {
            for (int i = 0; i < info.audioOffsets().length; i++) {
                in.seek(info.audioOffsets()[i]);
                byte[] data = new byte[info.audioSizes()[i]];
                in.readFully(data);
                bytes.write(data);
            }
        }
        return bytes.toByteArray();
    }

    private static void chunk(ByteArrayOutputStream out, String id, byte[] body) throws IOException {
        out.write(ascii(id));
        out.write(le(4).putInt(body.length).array());
        out.write(body);
        if ((body.length & 1) == 1) out.write(0);
    }

    private static int padded(int length) {
        return length + (length & 1);
    }

    private static ByteBuffer le(int size) {
        return ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
    }

    private static byte[] ascii(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }

    private static String tag(byte[] bytes, int offset) {
        return new String(bytes, offset, 4, StandardCharsets.US_ASCII);
    }
}
