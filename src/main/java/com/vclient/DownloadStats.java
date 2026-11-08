package com.vclient;

import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Live numbers for the install progress bar: bytes downloaded so far (added to by the downloader), a smoothed speed,
 * and an ETA based on how many files are done out of the total ("Asset preparation progress: 250/3000").
 */
final class DownloadStats {
    private static final Pattern PROGRESS = Pattern.compile("progress:\\s*(\\d+)\\s*/\\s*(\\d+)");
    private static final AtomicLong TOTAL_BYTES = new AtomicLong();

    private long startNanos;
    private long lastNanos;
    private long lastBytes;
    private double bytesPerSecond;
    private int done;
    private int total;

    /** Called by the downloader for every finished file. */
    static void addBytes(long bytes) {
        if (bytes > 0) TOTAL_BYTES.addAndGet(bytes);
    }

    void start() {
        startNanos = System.nanoTime();
        lastNanos = startNanos;
        lastBytes = TOTAL_BYTES.get();
        bytesPerSecond = 0;
        done = 0;
        total = 0;
    }

    /** Feeds one launcher output line; returns true when it carried a progress count. */
    boolean accept(String line) {
        Matcher matcher = PROGRESS.matcher(line == null ? "" : line);
        if (!matcher.find()) return false;
        done = Integer.parseInt(matcher.group(1));
        total = Integer.parseInt(matcher.group(2));
        return true;
    }

    /** Re-samples the byte counter; call about once a second. */
    void sample() {
        long now = System.nanoTime();
        double seconds = (now - lastNanos) / 1e9;
        if (seconds < 0.25) return;
        long bytes = TOTAL_BYTES.get();
        double instant = (bytes - lastBytes) / seconds;
        bytesPerSecond = bytesPerSecond == 0 ? instant : bytesPerSecond * 0.7 + instant * 0.3;
        lastNanos = now;
        lastBytes = bytes;
    }

    /** 0..1, or -1 when no total is known yet (the bar then shows an indeterminate animation). */
    double fraction() {
        return total <= 0 ? -1 : Math.min(1.0, (double) done / total);
    }

    long etaSeconds() {
        if (total <= 0 || done <= 0) return -1;
        double elapsed = (System.nanoTime() - startNanos) / 1e9;
        if (elapsed < 2) return -1;
        double perItem = elapsed / done;
        return Math.round(perItem * (total - done));
    }

    String describe() {
        StringBuilder text = new StringBuilder();
        if (total > 0) text.append(done).append(" / ").append(total).append(" files");
        if (bytesPerSecond > 0) {
            if (!text.isEmpty()) text.append("  ·  ");
            text.append(formatSpeed(bytesPerSecond));
        }
        long eta = etaSeconds();
        if (eta >= 0) {
            if (!text.isEmpty()) text.append("  ·  ");
            text.append("ETA ").append(formatDuration(eta));
        }
        return text.isEmpty() ? "Preparing…" : text.toString();
    }

    static String formatSpeed(double bytesPerSecond) {
        if (bytesPerSecond >= 1024 * 1024) return String.format(java.util.Locale.ROOT, "%.1f MB/s", bytesPerSecond / (1024 * 1024));
        return String.format(java.util.Locale.ROOT, "%.0f KB/s", bytesPerSecond / 1024);
    }

    static String formatDuration(long seconds) {
        if (seconds < 60) return seconds + "s";
        if (seconds < 3600) return seconds / 60 + "m " + seconds % 60 + "s";
        return seconds / 3600 + "h " + seconds % 3600 / 60 + "m";
    }
}
