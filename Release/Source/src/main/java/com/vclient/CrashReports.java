package com.vclient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/** Finds Minecraft crash reports (and JVM hs_err dumps) in a game folder and summarises them in one line. */
final class CrashReports {
    record Report(Path file, long modifiedMillis, String summary) {
        String label() {
            return file.getFileName() + "  ·  " + java.time.format.DateTimeFormatter.ofPattern("d MMM HH:mm")
                    .withZone(java.time.ZoneId.systemDefault()).format(Instant.ofEpochMilli(modifiedMillis));
        }
    }

    private CrashReports() {}

    /** Newest first. Looks at crash-reports/crash-*.txt and hs_err_pid*.log. */
    static List<Report> list(Path gameDirectory, int limit) {
        List<Report> reports = new java.util.ArrayList<>();
        collect(gameDirectory.resolve("crash-reports"), "crash-", ".txt", reports);
        collect(gameDirectory, "hs_err_pid", ".log", reports);
        reports.sort(Comparator.comparingLong(Report::modifiedMillis).reversed());
        return reports.size() > limit ? List.copyOf(reports.subList(0, limit)) : List.copyOf(reports);
    }

    /** The newest report written at or after {@code sinceMillis}, or null. */
    static Report newestSince(Path gameDirectory, long sinceMillis) {
        return list(gameDirectory, 1).stream().filter(report -> report.modifiedMillis() >= sinceMillis).findFirst().orElse(null);
    }

    private static void collect(Path directory, String prefix, String suffix, List<Report> into) {
        if (!Files.isDirectory(directory)) return;
        try (Stream<Path> files = Files.list(directory)) {
            files.filter(Files::isRegularFile).filter(path -> {
                String name = path.getFileName().toString();
                return name.startsWith(prefix) && name.endsWith(suffix);
            }).forEach(path -> {
                try {
                    into.add(new Report(path, Files.getLastModifiedTime(path).toMillis(), summarize(path)));
                } catch (IOException ignored) {
                    // unreadable report: skip it
                }
            });
        } catch (IOException ignored) {
            // no readable folder: no reports
        }
    }

    /** The "Description:" line of a Minecraft report (for example "Description: Exception in server tick loop"). */
    static String summarize(Path file) {
        try (Stream<String> lines = Files.lines(file, java.nio.charset.StandardCharsets.UTF_8)) {
            return lines.limit(40).map(String::strip)
                    .filter(line -> line.startsWith("Description:") || line.startsWith("# A fatal error")
                            || line.startsWith("# Out of Memory") || line.startsWith("# There is insufficient memory"))
                    .findFirst().map(line -> line.startsWith("Description:") ? line.substring(12).strip() : line.replaceFirst("^#\\s*", ""))
                    .orElse("Crash report");
        } catch (IOException | java.io.UncheckedIOException e) {
            return "Crash report";
        }
    }

    static String read(Report report) throws IOException {
        return MinecraftLauncher.readTail(report.file(), 200_000);
    }
}
