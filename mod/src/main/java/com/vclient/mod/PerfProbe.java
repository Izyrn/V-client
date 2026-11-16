package com.vclient.mod;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;

/**
 * Measures how the game really runs and writes it for the launcher (never needs a network):
 * <ul>
 *   <li>{@code vclient/perf-live.json}: session average FPS, 1% lows, memory, CPU / GPU bound share and what the
 *       game is actually running with (JVM flags, video options, performance mods, GPU). The launcher's FPS
 *       diagnostics and the "is Boost really applied" check read it.</li>
 *   <li>{@code vclient/benchmark-result.json}: a fixed-length benchmark (started by the launcher with
 *       {@code -Dvclient.benchmark.seconds=60 -Dvclient.benchmark.label=before|after}) that counts only time spent
 *       in a world and lifts the Max FPS / VSync limit while it runs, so before and after are comparable.</li>
 * </ul>
 * Frame times go into a fixed histogram, so memory use is constant however long the session is.
 */
final class PerfProbe {
    private static final int BUCKET_MICROS = 250;
    private static final int BUCKETS = 400; // 0 .. 100 ms; slower frames land in the last bucket
    private static final int WARMUP_SECONDS = 5;
    private static final String[] WATCHED_MODS = {"sodium", "lithium", "ferritecore", "immediatelyfast", "moreculling",
            "entityculling", "modernfix", "noisium", "memoryleakfix", "fabric-api", "iris", "optifine"};

    private static final ThreadMXBean THREADS = ManagementFactory.getThreadMXBean();
    private static final int BENCH_SECONDS = Math.max(0, Integer.getInteger("vclient.benchmark.seconds", 0));
    private static final String BENCH_LABEL = System.getProperty("vclient.benchmark.label", "before");
    private static final String BENCH_PROFILE = System.getProperty("vclient.benchmark.profile", "");

    /** Frame times and per-second classification for one measuring period (a session or a benchmark). */
    private static final class Stats {
        final long[] histogram = new long[BUCKETS];
        long frames;
        long frameNanos;
        int seconds;
        int cpuBound;
        int gpuBound;
        int capped;
        long memoryUsedSum;
        int minFps = Integer.MAX_VALUE;
        int maxFps;

        void frame(long nanos) {
            frames++;
            frameNanos += nanos;
            int bucket = (int) Math.min(BUCKETS - 1, nanos / 1000 / BUCKET_MICROS);
            histogram[bucket]++;
        }

        double averageFps() {
            return frameNanos == 0 ? 0 : frames * 1e9 / frameNanos;
        }

        /** FPS of the slowest {@code share} of frames (0.01 gives the "1% low"). */
        double low(double share) {
            if (frames == 0) return 0;
            long skip = Math.max(1, (long) Math.ceil(frames * share));
            long seen = 0;
            for (int i = BUCKETS - 1; i >= 0; i--) {
                seen += histogram[i];
                if (seen >= skip) {
                    double millis = (i + 1) * BUCKET_MICROS / 1000.0;
                    return 1000.0 / millis;
                }
            }
            return averageFps();
        }
    }

    private static final Stats SESSION = new Stats();
    private static Stats benchmark;
    private static boolean benchmarkDone;
    private static int benchmarkWarmup;
    private static Integer savedMaxFps;
    private static Boolean savedVsync;

    private static long lastFrame;
    private static long lastSecondWall;
    private static long lastSecondCpu;
    private static long framesThisSecond;
    private static int writeCountdown;
    private static String renderer = "";
    private static boolean failed;

    private PerfProbe() {}

    /** Called once per rendered frame (from the HUD render hook). */
    static void frame() {
        long now = System.nanoTime();
        if (lastFrame != 0) {
            long delta = now - lastFrame;
            MinecraftClient client = MinecraftClient.getInstance();
            if (delta < 2_000_000_000L && client != null && client.world != null && !client.isPaused()) {
                SESSION.frame(delta);
                framesThisSecond++;
                if (benchmark != null && benchmarkWarmup <= 0) benchmark.frame(delta);
            }
        }
        lastFrame = now;
        if (renderer.isEmpty()) readRenderer();
    }

    private static void readRenderer() {
        try {
            String value = org.lwjgl.opengl.GL11.glGetString(org.lwjgl.opengl.GL11.GL_RENDERER);
            renderer = value == null ? "unknown" : value;
        } catch (RuntimeException | LinkageError e) {
            renderer = "unknown";
        }
    }

    /** Called every client tick; does its work once per second. */
    static void tick(MinecraftClient client) {
        if (failed) return;
        if (client.world == null || client.player == null) {
            lastSecondWall = 0;
            return;
        }
        long wall = System.nanoTime();
        if (lastSecondWall == 0) {
            lastSecondWall = wall;
            lastSecondCpu = cpuTime();
            framesThisSecond = 0;
            startBenchmarkIfRequested(client);
            return;
        }
        if (wall - lastSecondWall < 1_000_000_000L) return;
        double elapsed = (wall - lastSecondWall) / 1e9;
        long cpu = cpuTime();
        double mainThreadLoad = cpu < 0 || lastSecondCpu < 0 ? -1 : (cpu - lastSecondCpu) / (double) (wall - lastSecondWall);
        int fps = (int) Math.round(framesThisSecond / elapsed);
        lastSecondWall = wall;
        lastSecondCpu = cpu;
        framesThisSecond = 0;
        if (client.isPaused()) return;

        classify(SESSION, client, fps, mainThreadLoad);
        if (benchmark != null) {
            if (benchmarkWarmup > 0) {
                benchmarkWarmup--;
                if (benchmarkWarmup == 0) client.inGameHud.setOverlayMessage(
                        Text.literal("V-Client benchmark: measuring " + BENCH_SECONDS + " s - keep playing normally"), false);
            } else {
                classify(benchmark, client, fps, mainThreadLoad);
                if (benchmark.seconds >= BENCH_SECONDS) finishBenchmark(client);
            }
        }
        if (--writeCountdown <= 0) {
            writeCountdown = 2;
            writeLive(client);
        }
    }

    private static void classify(Stats stats, MinecraftClient client, int fps, double mainThreadLoad) {
        stats.seconds++;
        stats.minFps = Math.min(stats.minFps, fps);
        stats.maxFps = Math.max(stats.maxFps, fps);
        Runtime runtime = Runtime.getRuntime();
        stats.memoryUsedSum += (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
        int limit = limiter(client);
        if (limit > 0 && fps >= limit * 0.95) stats.capped++;
        else if (mainThreadLoad >= 0.85) stats.cpuBound++;
        else if (mainThreadLoad >= 0) stats.gpuBound++;
    }

    /** The FPS limit currently in force (Max FPS, or the monitor rate with VSync), or 0 for none. */
    private static int limiter(MinecraftClient client) {
        try {
            int max = client.options.getMaxFps().getValue();
            int limit = max >= 260 ? 0 : max;
            if (client.options.getEnableVsync().getValue()) {
                var monitor = client.getWindow().getMonitor();
                int rate = monitor == null ? 60 : monitor.getCurrentVideoMode().getRefreshRate();
                limit = limit == 0 ? rate : Math.min(limit, rate);
            }
            return limit;
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static long cpuTime() {
        try {
            return THREADS.isCurrentThreadCpuTimeSupported() ? THREADS.getCurrentThreadCpuTime() : -1;
        } catch (RuntimeException e) {
            return -1;
        }
    }

    /** A few lines of live numbers for the in-game FrostBoost page. */
    static String[] liveLines() {
        if (SESSION.seconds < 3) return new String[] {"Not enough data yet: play for a few seconds in a world."};
        Runtime runtime = Runtime.getRuntime();
        long used = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
        int seconds = SESSION.seconds;
        String limit = SESSION.capped * 100 / seconds >= 60 ? "FPS limit (Max FPS / VSync)"
                : SESSION.cpuBound > SESSION.gpuBound + seconds * 15 / 100 ? "CPU"
                : SESSION.gpuBound > SESSION.cpuBound + seconds * 15 / 100 ? "Graphics card" : "None clear";
        return new String[] {
                String.format(Locale.ROOT, "Average FPS: %.0f", SESSION.averageFps()),
                String.format(Locale.ROOT, "1%% lows: %.0f FPS", SESSION.low(0.01)),
                "Memory: " + used + " / " + (runtime.maxMemory() / (1024 * 1024)) + " MB",
                "Bottleneck: " + limit};
    }

    // ---- benchmark ----------------------------------------------------------------------------------

    private static void startBenchmarkIfRequested(MinecraftClient client) {
        if (BENCH_SECONDS <= 0 || benchmark != null || benchmarkDone) return;
        benchmark = new Stats();
        benchmarkWarmup = WARMUP_SECONDS;
        try {
            savedMaxFps = client.options.getMaxFps().getValue();
            savedVsync = client.options.getEnableVsync().getValue();
            client.options.getMaxFps().setValue(260);
            client.options.getEnableVsync().setValue(false);
        } catch (RuntimeException e) {
            VClientMod.LOGGER.warn("Benchmark could not lift the FPS limit: {}", e.toString());
        }
        client.inGameHud.setOverlayMessage(Text.literal("V-Client benchmark starting: " + WARMUP_SECONDS
                + " s warm-up, then " + BENCH_SECONDS + " s measured"), false);
    }

    private static void restoreLimits(MinecraftClient client) {
        try {
            if (savedMaxFps != null) client.options.getMaxFps().setValue(savedMaxFps);
            if (savedVsync != null) client.options.getEnableVsync().setValue(savedVsync);
        } catch (RuntimeException e) {
            VClientMod.LOGGER.warn("Benchmark could not restore the FPS limit: {}", e.toString());
        }
        savedMaxFps = null;
        savedVsync = null;
    }

    private static void finishBenchmark(MinecraftClient client) {
        Stats result = benchmark;
        benchmark = null;
        benchmarkDone = true;
        restoreLimits(client);
        StringBuilder json = new StringBuilder("{");
        json.append("\"label\":").append(quote(BENCH_LABEL));
        json.append(",\"profile\":").append(quote(BENCH_PROFILE));
        json.append(",\"finishedAt\":").append(System.currentTimeMillis());
        json.append(",\"seconds\":").append(result.seconds);
        appendStats(json, result);
        appendRuntime(json, client);
        json.append("}");
        write("benchmark-result.json", json.toString());
        client.inGameHud.setOverlayMessage(Text.literal(String.format(Locale.ROOT,
                "V-Client benchmark done: %.0f FPS average, %.0f FPS 1%% low. See the launcher's Performance tab.",
                result.averageFps(), result.low(0.01))), false);
    }

    // ---- JSON ---------------------------------------------------------------------------------------

    private static void writeLive(MinecraftClient client) {
        StringBuilder json = new StringBuilder("{");
        json.append("\"updatedAt\":").append(System.currentTimeMillis());
        json.append(",\"seconds\":").append(SESSION.seconds);
        appendStats(json, SESSION);
        appendRuntime(json, client);
        json.append("}");
        write("perf-live.json", json.toString());
    }

    private static void appendStats(StringBuilder json, Stats stats) {
        int seconds = Math.max(1, stats.seconds);
        Runtime runtime = Runtime.getRuntime();
        json.append(String.format(Locale.ROOT,
                ",\"avgFps\":%.1f,\"low1\":%.1f,\"low01\":%.1f,\"minFps\":%d,\"maxFps\":%d"
                        + ",\"cpuBoundPct\":%.1f,\"gpuBoundPct\":%.1f,\"cappedPct\":%.1f"
                        + ",\"memoryUsedMb\":%d,\"memoryMaxMb\":%d,\"frames\":%d",
                stats.averageFps(), stats.low(0.01), stats.low(0.001),
                stats.minFps == Integer.MAX_VALUE ? 0 : stats.minFps, stats.maxFps,
                100.0 * stats.cpuBound / seconds, 100.0 * stats.gpuBound / seconds, 100.0 * stats.capped / seconds,
                stats.memoryUsedSum / seconds, runtime.maxMemory() / (1024 * 1024), stats.frames));
    }

    /** What the game is really running with, so the launcher can check Boost was applied. */
    private static void appendRuntime(StringBuilder json, MinecraftClient client) {
        json.append(",\"runtime\":{");
        json.append("\"minecraft\":").append(quote(FabricLoader.getInstance().getModContainer("minecraft")
                .map(mod -> mod.getMetadata().getVersion().getFriendlyString()).orElse("")));
        json.append(",\"renderer\":").append(quote(renderer));
        json.append(",\"cpuThreads\":").append(Runtime.getRuntime().availableProcessors());
        json.append(",\"maxHeapMb\":").append(Runtime.getRuntime().maxMemory() / (1024 * 1024));
        json.append(",\"jvmArgs\":[");
        List<String> args = ManagementFactory.getRuntimeMXBean().getInputArguments();
        for (int i = 0; i < args.size(); i++) json.append(i == 0 ? "" : ",").append(quote(args.get(i)));
        json.append("],\"mods\":{");
        for (int i = 0; i < WATCHED_MODS.length; i++) {
            var container = FabricLoader.getInstance().getModContainer(WATCHED_MODS[i]);
            json.append(i == 0 ? "" : ",").append(quote(WATCHED_MODS[i])).append(':')
                    .append(container.isPresent() ? quote(container.get().getMetadata().getVersion().getFriendlyString()) : "null");
        }
        json.append("},\"options\":{");
        try {
            var options = client.options;
            json.append("\"renderDistance\":").append(options.getViewDistance().getValue());
            json.append(",\"simulationDistance\":").append(options.getSimulationDistance().getValue());
            json.append(",\"maxFps\":").append(options.getMaxFps().getValue());
            json.append(",\"enableVsync\":").append(options.getEnableVsync().getValue());
            json.append(",\"entityShadows\":").append(options.getEntityShadows().getValue());
            json.append(",\"biomeBlendRadius\":").append(options.getBiomeBlendRadius().getValue());
            json.append(",\"mipmapLevels\":").append(options.getMipmapLevels().getValue());
            json.append(",\"entityDistanceScaling\":").append(
                    String.format(Locale.ROOT, "%.2f", options.getEntityDistanceScaling().getValue()));
        } catch (RuntimeException | LinkageError e) {
            // Leave the rest out: the launcher falls back to options.txt for what is missing.
        }
        json.append("}}");
    }

    private static String quote(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> out.append(c < 0x20 ? " " : String.valueOf(c));
            }
        }
        return out.append('"').toString();
    }

    private static void write(String name, String content) {
        try {
            Path directory = FabricLoader.getInstance().getGameDir().resolve("vclient");
            Files.createDirectories(directory);
            Path temporary = Files.createTempFile(directory, ".perf-", ".tmp");
            Files.writeString(temporary, content, StandardCharsets.UTF_8);
            Files.move(temporary, directory.resolve(name), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException e) {
            failed = true;
            VClientMod.LOGGER.warn("Performance data could not be written: {}", e.toString());
        }
    }
}
