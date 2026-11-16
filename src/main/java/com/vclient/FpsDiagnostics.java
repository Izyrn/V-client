package com.vclient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reads the live performance data the in-game mod writes ({@code vclient/perf-live.json}) and explains it:
 * average FPS, 1% lows, memory use and whether the CPU or the graphics card is the limit.
 */
final class FpsDiagnostics {
    private static final ObjectMapper JSON = new ObjectMapper();

    /** What the game was actually running with. {@code options} keeps the raw option values as text. */
    record Environment(String minecraft, String renderer, int cpuThreads, long maxHeapMb, List<String> jvmArgs,
                   Map<String, String> mods, Map<String, String> options) {}

    record Snapshot(long updatedAt, int seconds, double avgFps, double low1, double low01, int minFps, int maxFps,
                    double cpuBoundPct, double gpuBoundPct, double cappedPct, long memoryUsedMb, long memoryMaxMb,
                    Environment runtime) {
        double memoryShare() {
            return memoryMaxMb <= 0 ? 0 : (double) memoryUsedMb / memoryMaxMb;
        }
    }

    enum Bottleneck {
        CPU("CPU"), GPU("Graphics card"), LIMITER("FPS limit (Max FPS or VSync)"), BALANCED("None clear"), UNKNOWN("Not measured");

        private final String label;

        Bottleneck(String label) {
            this.label = label;
        }

        String label() {
            return label;
        }
    }

    private FpsDiagnostics() {}

    static Path liveFile(Path minecraftDirectory) {
        return minecraftDirectory.resolve("vclient").resolve("perf-live.json");
    }

    /** The latest snapshot, or null when the game has not written one yet. */
    static Snapshot read(Path minecraftDirectory) {
        Path file = liveFile(minecraftDirectory);
        if (!Files.isRegularFile(file)) return null;
        try {
            return parse(JSON.readTree(file.toFile()));
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    static Snapshot parse(JsonNode node) {
        if (node == null || !node.isObject() || node.path("seconds").asInt(0) <= 0) return null;
        JsonNode runtime = node.path("runtime");
        List<String> args = new ArrayList<>();
        runtime.path("jvmArgs").forEach(arg -> args.add(arg.asText("")));
        Map<String, String> mods = new LinkedHashMap<>();
        runtime.path("mods").fields().forEachRemaining(entry ->
                mods.put(entry.getKey(), entry.getValue().isNull() ? null : entry.getValue().asText("")));
        Map<String, String> options = new LinkedHashMap<>();
        runtime.path("options").fields().forEachRemaining(entry -> options.put(entry.getKey(), entry.getValue().asText("")));
        return new Snapshot(node.path("updatedAt").asLong(0), node.path("seconds").asInt(), node.path("avgFps").asDouble(),
                node.path("low1").asDouble(), node.path("low01").asDouble(), node.path("minFps").asInt(),
                node.path("maxFps").asInt(), node.path("cpuBoundPct").asDouble(), node.path("gpuBoundPct").asDouble(),
                node.path("cappedPct").asDouble(), node.path("memoryUsedMb").asLong(), node.path("memoryMaxMb").asLong(),
                new Environment(runtime.path("minecraft").asText(""), runtime.path("renderer").asText(""),
                        runtime.path("cpuThreads").asInt(), runtime.path("maxHeapMb").asLong(), args, mods, options));
    }

    static Bottleneck bottleneck(double cpuPct, double gpuPct, double cappedPct) {
        if (cpuPct + gpuPct + cappedPct <= 0) return Bottleneck.UNKNOWN;
        if (cappedPct >= 60) return Bottleneck.LIMITER;
        if (cpuPct >= gpuPct + 15) return Bottleneck.CPU;
        if (gpuPct >= cpuPct + 15) return Bottleneck.GPU;
        return Bottleneck.BALANCED;
    }

    static Bottleneck bottleneck(Snapshot snapshot) {
        return bottleneck(snapshot.cpuBoundPct(), snapshot.gpuBoundPct(), snapshot.cappedPct());
    }

    /** Plain-language findings with the fix that goes with each. Empty when nothing stands out. */
    static List<String> findings(Snapshot snapshot, PcAnalysis.Hardware hardware) {
        List<String> notes = new ArrayList<>();
        if (snapshot == null) return notes;
        switch (bottleneck(snapshot)) {
            case CPU -> notes.add("CPU bound: the game's main thread was the limit for "
                    + Math.round(snapshot.cpuBoundPct()) + "% of the time. Lower simulation distance and entity distance; "
                    + "Lithium and EntityCulling help most.");
            case GPU -> notes.add("Graphics card bound: the GPU was the limit for " + Math.round(snapshot.gpuBoundPct())
                    + "% of the time. Lower render distance, clouds, particles and resolution; Sodium helps most.");
            case LIMITER -> notes.add("FPS was held back by the Max FPS or VSync setting for "
                    + Math.round(snapshot.cappedPct()) + "% of the time. Raise Max FPS or turn VSync off to see the true speed.");
            default -> { }
        }
        if (snapshot.avgFps() > 0 && snapshot.low1() < snapshot.avgFps() * 0.5) {
            notes.add(String.format(Locale.ROOT, "Uneven frames: 1%% lows (%.0f) are less than half the average (%.0f). "
                    + "Usually garbage-collection pauses or chunk loading; Boost's JVM flags and ModernFix target this.",
                    snapshot.low1(), snapshot.avgFps()));
        }
        if (snapshot.memoryShare() >= 0.85) {
            notes.add("Memory is nearly full (" + snapshot.memoryUsedMb() + " of " + snapshot.memoryMaxMb()
                    + " MB used on average). Give the game more memory or lower the render distance.");
        } else if (snapshot.memoryMaxMb() >= 8192 && snapshot.memoryShare() < 0.2) {
            notes.add("The game has far more memory than it uses (" + snapshot.memoryUsedMb() + " of "
                    + snapshot.memoryMaxMb() + " MB). Less memory means shorter garbage-collection pauses.");
        }
        String renderer = snapshot.runtime().renderer().toLowerCase(Locale.ROOT);
        if (hardware != null && PcAnalysis.hybridGraphics(hardware) && !renderer.isBlank() && PcAnalysis.integratedGraphics(renderer)) {
            notes.add("Minecraft is drawing on the integrated graphics (" + snapshot.runtime().renderer()
                    + ") although a faster card is installed. Windows Settings > System > Display > Graphics > javaw.exe > "
                    + "High performance. This is the most common cause of low FPS on laptops.");
        }
        return notes;
    }
}
