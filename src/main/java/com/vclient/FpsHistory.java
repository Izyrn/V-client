package com.vclient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads the FPS samples the in-game VYRN mod writes (one {@code epochSeconds,fps} line per second to
 * {@code <game>/vclient/fps-history.csv}) and summarises them per play session.
 */
final class FpsHistory {
    /** Seconds without a sample that start a new session. */
    static final long SESSION_GAP_SECONDS = 120;

    record Sample(long epochSeconds, int fps) {}

    record Session(long startSeconds, long endSeconds, int samples, double average, int lowest, int highest,
                   List<Integer> series) {}

    private FpsHistory() {}

    static Path file(Path minecraftDirectory) {
        return minecraftDirectory.resolve("vclient").resolve("fps-history.csv");
    }

    static List<Sample> read(Path file) {
        List<Sample> samples = new ArrayList<>();
        if (!Files.isRegularFile(file)) return samples;
        try {
            for (String line : Files.readAllLines(file)) {
                int comma = line.indexOf(',');
                if (comma <= 0) continue;
                try {
                    long time = Long.parseLong(line.substring(0, comma).trim());
                    int fps = Integer.parseInt(line.substring(comma + 1).trim());
                    if (fps >= 0 && fps < 10_000) samples.add(new Sample(time, fps));
                } catch (NumberFormatException e) {
                    // Skip a half-written line.
                }
            }
        } catch (IOException e) {
            // Unreadable history: show nothing.
        }
        return samples;
    }

    /** Splits samples into sessions (a gap of two minutes starts a new one), oldest first. */
    static List<Session> sessions(List<Sample> samples) {
        List<Session> result = new ArrayList<>();
        List<Sample> current = new ArrayList<>();
        for (Sample sample : samples) {
            if (!current.isEmpty() && sample.epochSeconds() - current.getLast().epochSeconds() > SESSION_GAP_SECONDS) {
                result.add(summarise(current));
                current = new ArrayList<>();
            }
            current.add(sample);
        }
        if (!current.isEmpty()) result.add(summarise(current));
        return result;
    }

    static Session summarise(List<Sample> samples) {
        long sum = 0;
        int low = Integer.MAX_VALUE;
        int high = 0;
        List<Integer> series = new ArrayList<>(samples.size());
        for (Sample sample : samples) {
            sum += sample.fps();
            low = Math.min(low, sample.fps());
            high = Math.max(high, sample.fps());
            series.add(sample.fps());
        }
        return new Session(samples.getFirst().epochSeconds(), samples.getLast().epochSeconds(), samples.size(),
                (double) sum / samples.size(), low, high, series);
    }

    /** Mean of the per-sample values over every sample, or 0 with no data. */
    static double overallAverage(List<Sample> samples) {
        return samples.stream().mapToInt(Sample::fps).average().orElse(0);
    }

    /** Reduces a long series to at most {@code points} values by averaging buckets, so a graph stays light. */
    static List<Double> downsample(List<Integer> series, int points) {
        List<Double> result = new ArrayList<>();
        if (series.isEmpty() || points <= 0) return result;
        if (series.size() <= points) {
            series.forEach(value -> result.add(value.doubleValue()));
            return result;
        }
        for (int bucket = 0; bucket < points; bucket++) {
            int from = (int) ((long) bucket * series.size() / points);
            int to = Math.max(from + 1, (int) ((long) (bucket + 1) * series.size() / points));
            double sum = 0;
            for (int i = from; i < to; i++) sum += series.get(i);
            result.add(sum / (to - from));
        }
        return result;
    }
}
