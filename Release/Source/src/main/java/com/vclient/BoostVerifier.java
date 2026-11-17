package com.vclient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Checks that what a Boost profile promised is what the game really runs with: JVM memory and flags, video options
 * and optimisation mods. Uses the values the in-game mod reports (the truth) and falls back to files on disk.
 */
final class BoostVerifier {
    enum Status { OK, MISMATCH, UNKNOWN }

    record Check(String group, String name, String expected, String actual, Status status) {}

    private BoostVerifier() {}

    static List<Check> verify(BoostProfile.Tuning tuning, FpsDiagnostics.Snapshot live, Path minecraftDirectory) {
        List<Check> checks = new ArrayList<>();
        FpsDiagnostics.Environment runtime = live == null ? null : live.runtime();

        // Memory and JVM flags.
        if (runtime == null) {
            checks.add(new Check("Memory", "Game memory", tuning.memoryGb() + " GB", "game has not run yet", Status.UNKNOWN));
            checks.add(new Check("JVM flags", "Garbage-collector flags", tuning.jvmFlags().size() + " flags",
                    "game has not run yet", Status.UNKNOWN));
        } else {
            long expectedMb = tuning.memoryGb() * 1024L;
            long actualMb = runtime.maxHeapMb();
            boolean memoryOk = actualMb >= expectedMb * 0.85 && actualMb <= expectedMb * 1.05;
            checks.add(new Check("Memory", "Game memory", tuning.memoryGb() + " GB", Math.round(actualMb / 1024.0 * 10) / 10.0
                    + " GB", memoryOk ? Status.OK : Status.MISMATCH));
            List<String> missing = new ArrayList<>(tuning.jvmFlags());
            missing.removeIf(flag -> runtime.jvmArgs().contains(flag));
            checks.add(new Check("JVM flags", "Garbage-collector flags", tuning.jvmFlags().size() + " flags",
                    (tuning.jvmFlags().size() - missing.size()) + " active" + (missing.isEmpty() ? "" : ", missing "
                            + String.join(" ", missing)), missing.isEmpty() ? Status.OK : Status.MISMATCH));
        }

        // Video options: the running game first, options.txt for whatever it does not report.
        Map<String, String> fileOptions = readOptionsFile(minecraftDirectory);
        for (Map.Entry<String, String> entry : tuning.options().entrySet()) {
            String key = entry.getKey();
            String expected = normalise(entry.getValue());
            String live1 = runtime == null ? null : runtime.options().get(key);
            String actual = live1 != null ? normalise(live1) : fileOptions.get(key) == null ? null : normalise(fileOptions.get(key));
            Status status = actual == null ? Status.UNKNOWN : sameValue(expected, actual) ? Status.OK : Status.MISMATCH;
            checks.add(new Check("Video settings", key, expected, actual == null ? "not set" : actual, status));
        }

        // Mods.
        Path mods = minecraftDirectory.resolve("mods");
        for (BoostProfile.BoostMod mod : tuning.profile().mods()) {
            String id = mod.slug().replace("-", "");
            boolean onDisk = jarPresent(mods, id);
            String loaded = runtime == null ? null : runtime.mods().get(id);
            if (runtime != null && loaded != null) {
                checks.add(new Check("Mods", mod.name(), "loaded", "loaded (" + loaded + ")", Status.OK));
            } else if (!onDisk) {
                checks.add(new Check("Mods", mod.name(), "installed", "not installed (not released for this version, or Boost was not applied)",
                        Status.MISMATCH));
            } else if (runtime != null) {
                checks.add(new Check("Mods", mod.name(), "loaded", "installed but not loaded: wrong Minecraft version or a missing dependency",
                        Status.MISMATCH));
            } else {
                checks.add(new Check("Mods", mod.name(), "loaded", "installed, not yet seen running", Status.UNKNOWN));
            }
        }
        return checks;
    }

    static long count(List<Check> checks, Status status) {
        return checks.stream().filter(check -> check.status() == status).count();
    }

    private static boolean sameValue(String expected, String actual) {
        if (expected.equals(actual)) return true;
        try {
            return Math.abs(Double.parseDouble(expected) - Double.parseDouble(actual)) < 0.005;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static String normalise(String value) {
        return value.replace("\"", "").strip().toLowerCase(Locale.ROOT);
    }

    private static Map<String, String> readOptionsFile(Path minecraftDirectory) {
        Map<String, String> values = new java.util.HashMap<>();
        try {
            Path file = BoostOptions.optionsFile(minecraftDirectory);
            if (!Files.isRegularFile(file)) return values;
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                int colon = line.indexOf(':');
                if (colon > 0) values.putIfAbsent(line.substring(0, colon), line.substring(colon + 1));
            }
        } catch (IOException e) {
            // Treated as "not set".
        }
        return values;
    }

    static boolean jarPresent(Path modsDirectory, String token) {
        if (!Files.isDirectory(modsDirectory)) return false;
        try (Stream<Path> files = Files.list(modsDirectory)) {
            return files.anyMatch(file -> {
                String name = file.getFileName().toString().toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
                return name.contains(token) && name.endsWith(".jar");
            });
        } catch (IOException e) {
            return false;
        }
    }
}
