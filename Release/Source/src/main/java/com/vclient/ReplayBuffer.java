package com.vclient;

import com.sun.jna.platform.win32.WinDef;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Keeps the last 30 seconds of the Minecraft window as JPEG frames in memory and writes them to an AVI clip
 * when the hotkey is pressed. It only captures while a game started by V-Client is running <em>and</em> the
 * Minecraft window is the foreground window, and nothing is written to disk until the player presses the hotkey.
 */
final class ReplayBuffer implements GameSession.Listener {
    static final int SECONDS = 30;
    /** Target rate; the display's refresh rate is the ceiling for screen capture. */
    private static final int FRAMES_PER_SECOND = 60;
    /** Frames are grabbed by several threads in turn, since one thread can only copy the screen about 30 times a second. */
    private static final int CAPTURE_THREADS = 3;
    private static final int MAX_WIDTH = 960;
    private static final long MAX_BYTES = 160L * 1024 * 1024;
    private static final float JPEG_QUALITY = 0.7f;

    private record Frame(long timeMillis, byte[] jpeg) {}

    private final Path recordingsFolder;
    private final Supplier<String> hotkeyName;
    private final BooleanSupplier enabled;
    private final Consumer<String> status;
    private final Consumer<Path> onSaved;
    private final Supplier<Path> stateFile;
    private volatile long savedAtMillis;
    private final ArrayDeque<Frame> frames = new ArrayDeque<>();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(CAPTURE_THREADS + 2, runnable -> {
        Thread thread = new Thread(runnable, "vclient-replay-buffer");
        thread.setDaemon(true);
        return thread;
    });
    private final java.util.concurrent.ExecutorService workers = Executors.newFixedThreadPool(3, runnable -> {
        Thread thread = new Thread(runnable, "vclient-replay-encoder");
        thread.setPriority(Thread.NORM_PRIORITY - 2);
        thread.setDaemon(true);
        return thread;
    });
    private final java.util.concurrent.atomic.AtomicInteger inFlight = new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.List<java.util.concurrent.ScheduledFuture<?>> tasks = new ArrayList<>();
    private Robot robot;
    private long bufferedBytes;
    private int width;
    private int height;
    private boolean hotkeyWasDown;
    private volatile boolean capturing;
    private volatile boolean saving;

    ReplayBuffer(Path recordingsFolder, Supplier<String> hotkeyName, BooleanSupplier enabled,
                 Consumer<String> status, Consumer<Path> onSaved, Supplier<Path> stateFile) {
        this.recordingsFolder = recordingsFolder;
        this.hotkeyName = hotkeyName;
        this.enabled = enabled;
        this.status = status;
        this.onSaved = onSaved;
        this.stateFile = stateFile;
    }

    @Override
    public synchronized void started(String versionId, long startedAtMillis) {
        if (!WinInput.available() || !tasks.isEmpty()) return;
        try {
            robot = new Robot();
        } catch (Exception e) {
            status.accept("Replay buffer unavailable: this system does not allow screen capture.");
            return;
        }
        synchronized (frames) {
            frames.clear();
            bufferedBytes = 0;
        }
        long frameMillis = 1000 / FRAMES_PER_SECOND;
        for (int slot = 0; slot < CAPTURE_THREADS; slot++) {
            int mine = slot;
            tasks.add(scheduler.scheduleAtFixedRate(() -> captureFrame(mine), 500 + slot * frameMillis,
                    frameMillis * CAPTURE_THREADS, TimeUnit.MILLISECONDS));
        }
        tasks.add(scheduler.scheduleWithFixedDelay(this::pollHotkey, 500, 40, TimeUnit.MILLISECONDS));
        tasks.add(scheduler.scheduleWithFixedDelay(this::writeState, 500, 500, TimeUnit.MILLISECONDS));
    }

    @Override
    public synchronized void ended(String versionId, long startedAtMillis, long endedAtMillis) {
        tasks.forEach(task -> task.cancel(false));
        tasks.clear();
        capturing = false;
        for (int slot = 0; slot < CAPTURE_THREADS; slot++) {
            synchronized (grabLocks[slot]) {
                if (grabbers[slot] != null) grabbers[slot].close();
                grabbers[slot] = null;
            }
        }
        writeState();
        synchronized (frames) {
            frames.clear();
            bufferedBytes = 0;
        }
    }

    boolean capturing() {
        return capturing;
    }

    /**
     * Tells the in-game V-Client mod whether the buffer is recording, as {@code recording|savedAtMillis|timeMillis}
     * in {@code <game>/vclient/recording.state}; the mod shows a REC marker while it is fresh.
     */
    private void writeState() {
        try {
            Path file = stateFile.get();
            if (file == null) return;
            java.nio.file.Files.createDirectories(file.getParent());
            java.nio.file.Files.writeString(file, (capturing ? 1 : 0) + "|" + savedAtMillis + "|" + System.currentTimeMillis());
        } catch (Throwable e) {
            // The marker is optional.
        }
    }

    // ---- capture ---------------------------------------------------------------------------------

    private void captureFrame(int slot) {
        try {
            if (!enabled.getAsBoolean() || saving) {
                capturing = false;
                return;
            }
            WinDef.HWND window = gameWindow();
            if (window == null || !WinInput.isForeground(window)) {
                capturing = false;
                return;
            }
            BufferedImage fast = grabFast(window, slot);
            final BufferedImage shot;
            if (fast != null) {
                shot = fast;
            } else {
                Rectangle bounds = WinInput.bounds(window, displayScale());
                if (bounds == null) {
                    capturing = false;
                    return;
                }
                synchronized (robot) {
                    shot = robot.createScreenCapture(bounds);
                }
            }
            long taken = System.currentTimeMillis();
            capturing = true;
            if (inFlight.get() >= 12) return;
            inFlight.incrementAndGet();
            workers.execute(() -> {
                try {
                    store(shot, taken);
                } catch (Throwable e) {
                    // Drop this frame.
                } finally {
                    inFlight.decrementAndGet();
                }
            });
        } catch (Throwable e) {
            capturing = false;
        }
    }

    private final ScreenGrabber[] grabbers = new ScreenGrabber[CAPTURE_THREADS];
    private final Object[] grabLocks = {new Object(), new Object(), new Object()};
    private volatile WinDef.HWND cachedWindow;
    private volatile long cachedWindowAt;

    /** Looking the game window up walks every window, so with several capture threads it is looked up twice a second. */
    private WinDef.HWND gameWindow() {
        long now = System.currentTimeMillis();
        WinDef.HWND window = cachedWindow;
        if (window == null || now - cachedWindowAt > 500) {
            window = WinInput.findWindowStartingWith("Minecraft");
            cachedWindow = window;
            cachedWindowAt = now;
        }
        return window;
    }

    /**
     * Copies the game window straight into a shrunken bitmap with GDI (about 3x faster than Robot, so the clip keeps
     * its frame rate). Returns null if that is not possible and the caller falls back to Robot.
     */
    private BufferedImage grabFast(WinDef.HWND window, int slot) {
        try {
            Rectangle physical = WinInput.bounds(window, 1.0);
            if (physical == null) return null;
            int targetWidth = Math.max(2, Math.min(MAX_WIDTH, physical.width) & ~1);
            int targetHeight = Math.max(2, (int) Math.round((double) physical.height * targetWidth / physical.width) & ~1);
            synchronized (grabLocks[slot]) {
                ScreenGrabber grabber = grabbers[slot];
                if (grabber == null || grabber.width() != targetWidth || grabber.height() != targetHeight) {
                    if (grabber != null) grabber.close();
                    grabber = new ScreenGrabber(targetWidth, targetHeight);
                    grabbers[slot] = grabber;
                }
                return grabber.grab(physical);
            }
        } catch (Throwable e) {
            return null;
        }
    }

    /** Scales and encodes one captured frame on a worker thread, so capturing is not slowed by encoding. */
    private void store(BufferedImage shot, long taken) throws IOException {
        double scale = Math.min(1.0, (double) MAX_WIDTH / shot.getWidth());
        int targetWidth = Math.max(2, ((int) Math.round(shot.getWidth() * scale)) & ~1);
        int targetHeight = Math.max(2, ((int) Math.round(shot.getHeight() * scale)) & ~1);
        BufferedImage scaled = shot;
        if (targetWidth != shot.getWidth() || targetHeight != shot.getHeight()) {
            scaled = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = scaled.createGraphics();
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.drawImage(shot, 0, 0, targetWidth, targetHeight, null);
            graphics.dispose();
        }
        // JPEG encoding is much faster from a 3-byte BGR image than from the packed-int one the capture produces.
        BufferedImage bgr = new BufferedImage(scaled.getWidth(), scaled.getHeight(), BufferedImage.TYPE_3BYTE_BGR);
        Graphics2D convert = bgr.createGraphics();
        convert.drawImage(scaled, 0, 0, null);
        convert.dispose();
        byte[] jpeg = encode(bgr);
        synchronized (frames) {
            if (tasks.isEmpty()) return;
            if (targetWidth != width || targetHeight != height) {
                frames.clear();
                bufferedBytes = 0;
                width = targetWidth;
                height = targetHeight;
            }
            frames.addLast(new Frame(taken, jpeg));
            bufferedBytes += jpeg.length;
            while (!frames.isEmpty() && (taken - frames.peekFirst().timeMillis() > SECONDS * 1000L
                    || bufferedBytes > MAX_BYTES)) {
                bufferedBytes -= frames.removeFirst().jpeg().length;
            }
        }
    }

    private static double displayScale() {
        try {
            return java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice()
                    .getDefaultConfiguration().getDefaultTransform().getScaleX();
        } catch (Throwable e) {
            return 1.0;
        }
    }

    static byte[] encode(BufferedImage image) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream(64 * 1024);
             MemoryCacheImageOutputStream out = new MemoryCacheImageOutputStream(bytes)) {
            ImageWriteParam parameters = writer.getDefaultWriteParam();
            parameters.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            parameters.setCompressionQuality(JPEG_QUALITY);
            writer.setOutput(out);
            writer.write(null, new IIOImage(image, null, null), parameters);
            out.flush();
            return bytes.toByteArray();
        } finally {
            writer.dispose();
        }
    }

    // ---- hotkey and saving -------------------------------------------------------------------------

    private void pollHotkey() {
        try {
            Integer code = WinInput.HOTKEYS.get(hotkeyName.get());
            if (code == null || !enabled.getAsBoolean()) return;
            boolean down = WinInput.keyDown(code);
            boolean pressed = down && !hotkeyWasDown;
            hotkeyWasDown = down;
            if (!pressed) return;
            WinDef.HWND window = WinInput.findWindowStartingWith("Minecraft");
            if (window == null || !WinInput.isForeground(window)) return;
            saveClip();
        } catch (Throwable e) {
            // Keep polling.
        }
    }

    /** Writes whatever the buffer holds right now to a new clip. */
    void saveClip() {
        if (saving) return;
        List<Frame> snapshot;
        int clipWidth;
        int clipHeight;
        synchronized (frames) {
            snapshot = new ArrayList<>(frames);
            snapshot.sort(java.util.Comparator.comparingLong(Frame::timeMillis));
            clipWidth = width;
            clipHeight = height;
        }
        if (snapshot.size() < 2) {
            status.accept("Nothing to save yet: the replay buffer is still filling.");
            return;
        }
        saving = true;
        workers.execute(() -> {
            try {
                long span = snapshot.getLast().timeMillis() - snapshot.getFirst().timeMillis();
                double fps = span <= 0 ? FRAMES_PER_SECOND : (snapshot.size() - 1) * 1000.0 / span;
                Path file = recordingsFolder.resolve("Clip " + LocalDateTime.now()
                        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH-mm-ss")) + ".avi");
                java.nio.file.Files.createDirectories(recordingsFolder);
                MjpegAvi.write(file, snapshot.stream().map(Frame::jpeg).toList(), clipWidth, clipHeight, fps);
                status.accept("Saved the last " + Math.round(span / 1000.0) + " seconds as " + file.getFileName());
                savedAtMillis = System.currentTimeMillis();
                writeState();
                onSaved.accept(file);
            } catch (IOException e) {
                status.accept("Could not save the clip: " + e.getMessage());
            } finally {
                saving = false;
            }
        });
    }
}
