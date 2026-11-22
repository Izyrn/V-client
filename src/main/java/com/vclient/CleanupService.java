package com.vclient;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * One-click cleanup of the disposable parts of VYRN's game folder: logs, caches, temporary files and the
 * versions nobody uses. It never touches worlds, mods, configs, screenshots, recordings or saved accounts.
 */
final class CleanupService {
    enum Category {
        LOGS("Logs", "Game logs, crash reports and JVM crash dumps"),
        CACHE("Cache", "Skin cache and web cache"),
        TEMP("Temporary files", "Half-finished downloads and leftover .tmp / .part files"),
        OLD_VERSIONS("Old versions", "Installed versions other than the newest one and the VYRN profile");

        private final String label;
        private final String detail;

        Category(String label, String detail) {
            this.label = label;
            this.detail = detail;
        }

        String label() {
            return label;
        }

        String detail() {
            return detail;
        }
    }

    record Item(Category category, Path path, long bytes) {}

    record Result(int deleted, long bytesFreed, int failed) {}

    private CleanupService() {}

    /** Everything a cleanup of these categories would delete. {@code keepVersionIds} are never listed. */
    static List<Item> scan(Path minecraftDirectory, Path launcherDirectory, Set<Category> categories,
                           Set<String> keepVersionIds) {
        List<Item> items = new ArrayList<>();
        if (categories.contains(Category.LOGS)) {
            addChildren(items, Category.LOGS, minecraftDirectory.resolve("logs"));
            addChildren(items, Category.LOGS, minecraftDirectory.resolve("crash-reports"));
            addMatching(items, Category.LOGS, minecraftDirectory, "hs_err_pid", ".log");
        }
        if (categories.contains(Category.CACHE)) {
            addChildren(items, Category.CACHE, launcherDirectory.resolve("skin-cache"));
            addChildren(items, Category.CACHE, minecraftDirectory.resolve("webcache2"));
            addChildren(items, Category.CACHE, minecraftDirectory.resolve(".fabric").resolve("processedMods"));
        }
        if (categories.contains(Category.TEMP)) {
            addTemporary(items, minecraftDirectory);
            addTemporary(items, launcherDirectory);
        }
        if (categories.contains(Category.OLD_VERSIONS)) {
            addOldVersions(items, minecraftDirectory.resolve("versions"), keepVersionIds);
        }
        return items;
    }

    static long totalBytes(List<Item> items, Category category) {
        return items.stream().filter(item -> item.category() == category).mapToLong(Item::bytes).sum();
    }

    static Result delete(List<Item> items, Path minecraftDirectory, Path launcherDirectory) {
        int deleted = 0;
        int failed = 0;
        long freed = 0;
        Path gameRoot = minecraftDirectory.toAbsolutePath().normalize();
        Path launcherRoot = launcherDirectory.toAbsolutePath().normalize();
        for (Item item : items) {
            Path path = item.path().toAbsolutePath().normalize();
            // Only ever delete inside the two folders VYRN owns.
            if (!(path.startsWith(gameRoot) || path.startsWith(launcherRoot)) || path.equals(gameRoot)
                    || path.equals(launcherRoot)) {
                failed++;
                continue;
            }
            try {
                deleteTree(path);
                deleted++;
                freed += item.bytes();
            } catch (IOException e) {
                failed++;
            }
        }
        return new Result(deleted, freed, failed);
    }

    // ---- scanning helpers -------------------------------------------------------------------------

    private static void addChildren(List<Item> items, Category category, Path directory) {
        if (!Files.isDirectory(directory)) return;
        try (Stream<Path> children = Files.list(directory)) {
            for (Path child : (Iterable<Path>) children::iterator) {
                // The log the running game is writing right now cannot be removed.
                if (category == Category.LOGS && child.getFileName().toString().equals("latest.log")
                        && GameSession.running()) continue;
                items.add(new Item(category, child, sizeOf(child)));
            }
        } catch (IOException e) {
            // Unreadable folder: skip it.
        }
    }

    private static void addMatching(List<Item> items, Category category, Path directory, String prefix, String suffix) {
        if (!Files.isDirectory(directory)) return;
        try (Stream<Path> children = Files.list(directory)) {
            for (Path child : (Iterable<Path>) children.filter(Files::isRegularFile)::iterator) {
                String name = child.getFileName().toString();
                if (name.startsWith(prefix) && name.endsWith(suffix)) items.add(new Item(category, child, sizeOf(child)));
            }
        } catch (IOException e) {
            // Skip.
        }
    }

    private static void addTemporary(List<Item> items, Path directory) {
        if (!Files.isDirectory(directory)) return;
        try (Stream<Path> children = Files.list(directory)) {
            for (Path child : (Iterable<Path>) children.filter(Files::isRegularFile)::iterator) {
                String name = child.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
                if (name.endsWith(".tmp") || name.endsWith(".part") || name.endsWith(".download")) {
                    items.add(new Item(Category.TEMP, child, sizeOf(child)));
                }
            }
        } catch (IOException e) {
            // Skip.
        }
    }

    private static void addOldVersions(List<Item> items, Path versions, Set<String> keep) {
        if (!Files.isDirectory(versions)) return;
        List<Path> candidates = new ArrayList<>();
        try (Stream<Path> children = Files.list(versions)) {
            children.filter(Files::isDirectory).forEach(candidates::add);
        } catch (IOException e) {
            return;
        }
        // Newest by modification time is kept besides the explicitly protected ids.
        candidates.sort(Comparator.comparingLong(CleanupService::lastModified).reversed());
        Set<String> protectedIds = new java.util.HashSet<>(keep);
        if (!candidates.isEmpty()) protectedIds.add(candidates.getFirst().getFileName().toString());
        for (Path directory : candidates) {
            String name = directory.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
            if (name.contains("vyrn") || name.contains("v-client")) {
                protectedIds.add(directory.getFileName().toString());
            }
        }
        // A kept mod-loader profile still needs the vanilla version it inherits from.
        boolean grew = true;
        while (grew) {
            grew = false;
            for (String id : Set.copyOf(protectedIds)) {
                String parent = inheritsFrom(versions.resolve(id).resolve(id + ".json"));
                if (!parent.isBlank() && protectedIds.add(parent)) grew = true;
            }
        }
        for (Path directory : candidates) {
            if (!protectedIds.contains(directory.getFileName().toString())) {
                items.add(new Item(Category.OLD_VERSIONS, directory, sizeOf(directory)));
            }
        }
    }

    private static String inheritsFrom(Path versionJson) {
        try {
            if (!Files.isRegularFile(versionJson)) return "";
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(versionJson.toFile())
                    .path("inheritsFrom").asText("");
        } catch (IOException e) {
            return "";
        }
    }

    private static long lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return 0;
        }
    }

    static long sizeOf(Path path) {
        if (Files.isRegularFile(path)) {
            try {
                return Files.size(path);
            } catch (IOException e) {
                return 0;
            }
        }
        long[] total = {0};
        try {
            Files.walkFileTree(path, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                    total[0] += attributes.size();
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exception) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            // Partial size is fine.
        }
        return total[0];
    }

    private static void deleteTree(Path path) throws IOException {
        if (!Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return;
        Files.walkFileTree(path, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException exception) throws IOException {
                Files.deleteIfExists(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double value = bytes / 1024.0;
        String[] units = {"KB", "MB", "GB", "TB"};
        int unit = 0;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024;
            unit++;
        }
        return String.format(java.util.Locale.ROOT, value >= 100 ? "%.0f %s" : "%.1f %s", value, units[unit]);
    }
}
