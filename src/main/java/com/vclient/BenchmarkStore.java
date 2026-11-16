package com.vclient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * FPS benchmark results. The in-game mod writes {@code vclient/benchmark-result.json} when a benchmark finishes;
 * the launcher moves it into {@code vclient/benchmarks.json} (the history) and compares "before" with "after".
 */
final class BenchmarkStore {
    static final int DEFAULT_SECONDS = 60;
    private static final int MAX_RESULTS = 40;
    private static final ObjectMapper JSON = new ObjectMapper();

    /** What the next game launch should measure. */
    record Request(int seconds, String label, String profile) {}

    record Result(String label, String profile, long finishedAt, int seconds, double avgFps, double low1, double low01,
                  int minFps, int maxFps, double cpuBoundPct, double gpuBoundPct, double cappedPct,
                  long memoryUsedMb, long memoryMaxMb, String renderer, String minecraft) {
        /** 1% low as a share of the average: near 1 is smooth, far below 1 means stutter. */
        double avg1LowRatio() {
            return avgFps <= 0 ? 1 : low1 / avgFps;
        }
    }

    /** Before and after side by side. {@code before} or {@code after} is null when that half has not been measured. */
    record Comparison(Result before, Result after) {
        boolean complete() {
            return before != null && after != null;
        }

        double avgGainPercent() {
            return complete() ? gainPercent(before.avgFps(), after.avgFps()) : 0;
        }

        double lowGainPercent() {
            return complete() ? gainPercent(before.low1(), after.low1()) : 0;
        }
    }

    private BenchmarkStore() {}

    static Path resultFile(Path minecraftDirectory) {
        return minecraftDirectory.resolve("vclient").resolve("benchmark-result.json");
    }

    static Path historyFile(Path minecraftDirectory) {
        return minecraftDirectory.resolve("vclient").resolve("benchmarks.json");
    }

    /** Percentage change from {@code before} to {@code after}; 0 when there is nothing to compare against. */
    static double gainPercent(double before, double after) {
        return before <= 0 ? 0 : (after - before) / before * 100.0;
    }

    /** "+77%" or "-5%". */
    static String formatPercent(double percent) {
        long rounded = Math.round(percent);
        return (rounded > 0 ? "+" : "") + rounded + "%";
    }

    /** The oldest results first. */
    static List<Result> load(Path minecraftDirectory) {
        List<Result> results = new ArrayList<>();
        Path file = historyFile(minecraftDirectory);
        if (!Files.isRegularFile(file)) return results;
        try {
            for (JsonNode node : JSON.readTree(file.toFile())) {
                Result result = parse(node);
                if (result != null) results.add(result);
            }
        } catch (IOException | RuntimeException e) {
            // A damaged history is treated as empty rather than breaking the page.
        }
        return results;
    }

    static Result parse(JsonNode node) {
        if (node == null || !node.isObject() || node.path("avgFps").asDouble(0) <= 0) return null;
        String label = node.path("label").asText("before");
        return new Result("after".equals(label) ? "after" : "before", node.path("profile").asText(""),
                node.path("finishedAt").asLong(0), node.path("seconds").asInt(0), node.path("avgFps").asDouble(),
                node.path("low1").asDouble(), node.path("low01").asDouble(), node.path("minFps").asInt(),
                node.path("maxFps").asInt(), node.path("cpuBoundPct").asDouble(), node.path("gpuBoundPct").asDouble(),
                node.path("cappedPct").asDouble(), node.path("memoryUsedMb").asLong(), node.path("memoryMaxMb").asLong(),
                node.path("runtime").path("renderer").asText(""), node.path("runtime").path("minecraft").asText(""));
    }

    /**
     * Moves a finished benchmark from the mod's output file into the history. Returns it, or empty when no new
     * result is waiting.
     */
    static Optional<Result> ingest(Path minecraftDirectory) {
        Path file = resultFile(minecraftDirectory);
        if (!Files.isRegularFile(file)) return Optional.empty();
        try {
            JsonNode node = JSON.readTree(file.toFile());
            Result result = parse(node);
            if (result == null) {
                Files.deleteIfExists(file);
                return Optional.empty();
            }
            Path history = historyFile(minecraftDirectory);
            ArrayNode all = JSON.createArrayNode();
            if (Files.isRegularFile(history) && JSON.readTree(history.toFile()) instanceof ArrayNode existing) all = existing;
            all.add(node);
            while (all.size() > MAX_RESULTS) all.remove(0);
            Files.createDirectories(history.getParent());
            Path temporary = Files.createTempFile(history.getParent(), ".bench-", ".tmp");
            try {
                JSON.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), all);
                Files.move(temporary, history, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temporary);
            }
            Files.deleteIfExists(file);
            return Optional.of(result);
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    /**
     * The newest "after" with the newest "before" that was measured earlier. With no "after" yet, the newest
     * "before" alone.
     */
    static Comparison compare(List<Result> results) {
        Result after = null;
        for (int i = results.size() - 1; i >= 0; i--) {
            if ("after".equals(results.get(i).label())) {
                after = results.get(i);
                break;
            }
        }
        Result before = null;
        for (int i = results.size() - 1; i >= 0; i--) {
            Result candidate = results.get(i);
            if ("before".equals(candidate.label()) && (after == null || candidate.finishedAt() <= after.finishedAt())) {
                before = candidate;
                break;
            }
        }
        if (before == null) after = null; // an "after" with nothing to compare to is shown on its own elsewhere
        return new Comparison(before, after);
    }

    /** The "before" result that has no "after" yet (so the next measurement should be the "after"), if any. */
    static boolean awaitingAfter(List<Result> results) {
        Comparison comparison = compare(results);
        return comparison.before() != null && comparison.after() == null;
    }

    /** Raw JSON for a result, used by tests and the export button. */
    static ObjectNode toJson(Result result) {
        ObjectNode node = JSON.createObjectNode();
        node.put("label", result.label());
        node.put("profile", result.profile());
        node.put("finishedAt", result.finishedAt());
        node.put("seconds", result.seconds());
        node.put("avgFps", result.avgFps());
        node.put("low1", result.low1());
        node.put("low01", result.low01());
        node.put("minFps", result.minFps());
        node.put("maxFps", result.maxFps());
        node.put("cpuBoundPct", result.cpuBoundPct());
        node.put("gpuBoundPct", result.gpuBoundPct());
        node.put("cappedPct", result.cappedPct());
        node.put("memoryUsedMb", result.memoryUsedMb());
        node.put("memoryMaxMb", result.memoryMaxMb());
        ObjectNode runtime = node.putObject("runtime");
        runtime.put("renderer", result.renderer());
        runtime.put("minecraft", result.minecraft());
        return node;
    }

    /** "avg 320 FPS, 1% low 210" for log lines. */
    static String summary(Result result) {
        return String.format(Locale.ROOT, "%.0f FPS average, %.0f FPS 1%% low", result.avgFps(), result.low1());
    }
}
