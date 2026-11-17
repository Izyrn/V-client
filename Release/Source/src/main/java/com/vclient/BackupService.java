package com.vclient;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Backs up the parts of a V-Client install worth keeping into one zip: mods, settings, cosmetics and profiles.
 * Sign-in tokens and saved accounts are never put in a backup, and a restore keeps this PC's game folder and
 * sign-in. Restoring adds and overwrites files; it never deletes anything.
 */
final class BackupService {
    enum Category {
        MODS("Mods", "mods"), SETTINGS("Settings", "settings"), COSMETICS("Cosmetics", "cosmetics"),
        PROFILES("Profiles", "profiles");

        private final String label;
        private final String folder;

        Category(String label, String folder) {
            this.label = label;
            this.folder = folder;
        }

        String label() {
            return label;
        }

        String folder() {
            return folder;
        }
    }

    record Backup(Path file, long modifiedMillis, long sizeBytes) {}

    record Result(int files, long bytes) {}

    private static final ObjectMapper JSON = new ObjectMapper();
    /** Settings that belong to this PC or this sign-in and are kept as they are when settings are restored. */
    private static final List<String> LOCAL_ONLY_SETTINGS = List.of("minecraftDirectory", "backendTokens",
            "backendToken", "installId", "lastAccountId");

    private BackupService() {}

    static Path defaultFolder(Path launcherDirectory) {
        return launcherDirectory.resolve("backups");
    }

    static List<Backup> list(Path folder) {
        if (!Files.isDirectory(folder)) return List.of();
        try (Stream<Path> files = Files.list(folder)) {
            List<Backup> backups = new ArrayList<>();
            for (Path file : (Iterable<Path>) files.filter(f -> f.getFileName().toString().endsWith(".zip"))::iterator) {
                backups.add(new Backup(file, Files.getLastModifiedTime(file).toMillis(), Files.size(file)));
            }
            backups.sort(Comparator.comparingLong(Backup::modifiedMillis).reversed());
            return backups;
        } catch (IOException e) {
            return List.of();
        }
    }

    // ---- create ------------------------------------------------------------------------------------

    static Path create(Path folder, Path minecraftDirectory, Path launcherDirectory, Set<Category> categories)
            throws IOException {
        Files.createDirectories(folder);
        Path target = folder.resolve("V-Client backup " + LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH-mm-ss")) + ".zip");
        Path temporary = folder.resolve(target.getFileName() + ".part");
        int[] count = {0};
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(temporary))) {
            ObjectNode manifest = JSON.createObjectNode();
            manifest.put("created", LocalDateTime.now().toString());
            manifest.put("launcherVersion", AppInfo.VERSION);
            var included = manifest.putArray("categories");
            categories.forEach(category -> included.add(category.name()));
            put(zip, "manifest.json", JSON.writeValueAsBytes(manifest));

            if (categories.contains(Category.MODS)) addTree(zip, "mods/", minecraftDirectory.resolve("mods"), count);
            if (categories.contains(Category.SETTINGS)) {
                Path settings = launcherDirectory.resolve("settings.json");
                if (Files.isRegularFile(settings)) {
                    ObjectNode node = (ObjectNode) JSON.readTree(settings.toFile());
                    LOCAL_ONLY_SETTINGS.forEach(node::remove);
                    put(zip, "settings/launcher-settings.json", JSON.writeValueAsBytes(node));
                    count[0]++;
                }
                addFile(zip, "settings/options.txt", minecraftDirectory.resolve("options.txt"), count);
            }
            if (categories.contains(Category.COSMETICS)) {
                Path config = minecraftDirectory.resolve("config");
                if (Files.isDirectory(config)) {
                    try (Stream<Path> files = Files.list(config)) {
                        for (Path file : (Iterable<Path>) files.filter(Files::isRegularFile)
                                .filter(f -> f.getFileName().toString().toLowerCase().startsWith("vclient"))::iterator) {
                            addFile(zip, "cosmetics/" + file.getFileName(), file, count);
                        }
                    }
                }
            }
            if (categories.contains(Category.PROFILES)) {
                addProfileJsons(zip, minecraftDirectory.resolve("versions"), count);
                addTree(zip, "profiles/skin-presets/", launcherDirectory.resolve("skin-presets"), count);
                addFile(zip, "profiles/friends.json", launcherDirectory.resolve("friends.json"), count);
            }
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(temporary);
            throw e instanceof IOException io ? io : new IOException(e.getMessage(), e);
        }
        Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
        return target;
    }

    private static void addProfileJsons(ZipOutputStream zip, Path versions, int[] count) throws IOException {
        if (!Files.isDirectory(versions)) return;
        try (Stream<Path> directories = Files.list(versions)) {
            for (Path directory : (Iterable<Path>) directories.filter(Files::isDirectory)::iterator) {
                String id = directory.getFileName().toString();
                addFile(zip, "profiles/versions/" + id + "/" + id + ".json", directory.resolve(id + ".json"), count);
            }
        }
    }

    private static void addTree(ZipOutputStream zip, String prefix, Path root, int[] count) throws IOException {
        if (!Files.isDirectory(root)) return;
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : (Iterable<Path>) files.filter(Files::isRegularFile)::iterator) {
                addFile(zip, prefix + root.relativize(file).toString().replace('\\', '/'), file, count);
            }
        }
    }

    private static void addFile(ZipOutputStream zip, String name, Path file, int[] count) throws IOException {
        if (!Files.isRegularFile(file)) return;
        zip.putNextEntry(new ZipEntry(name));
        Files.copy(file, zip);
        zip.closeEntry();
        count[0]++;
    }

    private static void put(ZipOutputStream zip, String name, byte[] bytes) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(bytes);
        zip.closeEntry();
    }

    // ---- restore -----------------------------------------------------------------------------------

    /** Categories a backup holds, read from its entries. */
    static Set<Category> contents(Path backup) throws IOException {
        Set<Category> found = EnumSet.noneOf(Category.class);
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(backup))) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
                for (Category category : Category.values()) {
                    if (entry.getName().startsWith(category.folder() + "/")) found.add(category);
                }
            }
        }
        return found;
    }

    static Result restore(Path backup, Path minecraftDirectory, Path launcherDirectory, Set<Category> categories)
            throws IOException {
        int files = 0;
        long bytes = 0;
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(backup))) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
                if (entry.isDirectory()) continue;
                String name = entry.getName();
                Category category = categoryOf(name);
                if (category == null || !categories.contains(category)) continue;
                Path destination = destinationOf(name, minecraftDirectory, launcherDirectory);
                if (destination == null) continue;
                byte[] data = readLimited(zip);
                if (name.equals("settings/launcher-settings.json")) data = mergeSettings(data, destination);
                Files.createDirectories(destination.getParent());
                Files.write(destination, data);
                files++;
                bytes += data.length;
            }
        }
        return new Result(files, bytes);
    }

    private static Category categoryOf(String name) {
        for (Category category : Category.values()) if (name.startsWith(category.folder() + "/")) return category;
        return null;
    }

    /** Maps an entry to its place on this PC, or null when it would land outside the allowed folders. */
    static Path destinationOf(String name, Path minecraftDirectory, Path launcherDirectory) {
        Path game = minecraftDirectory.toAbsolutePath().normalize();
        Path launcher = launcherDirectory.toAbsolutePath().normalize();
        Path destination;
        Path root;
        if (name.startsWith("mods/")) {
            root = game.resolve("mods");
            destination = game.resolve(name);
        } else if (name.equals("settings/launcher-settings.json")) {
            root = launcher;
            destination = launcher.resolve("settings.json");
        } else if (name.equals("settings/options.txt")) {
            root = game;
            destination = game.resolve("options.txt");
        } else if (name.startsWith("cosmetics/")) {
            root = game.resolve("config");
            destination = game.resolve("config").resolve(name.substring("cosmetics/".length()));
        } else if (name.startsWith("profiles/versions/")) {
            root = game.resolve("versions");
            destination = game.resolve("versions").resolve(name.substring("profiles/versions/".length()));
        } else if (name.startsWith("profiles/skin-presets/")) {
            root = launcher.resolve("skin-presets");
            destination = launcher.resolve("skin-presets").resolve(name.substring("profiles/skin-presets/".length()));
        } else if (name.equals("profiles/friends.json")) {
            root = launcher;
            destination = launcher.resolve("friends.json");
        } else {
            return null;
        }
        destination = destination.normalize();
        return destination.startsWith(root.normalize()) ? destination : null;
    }

    /** Takes the backed-up settings but keeps this PC's folder, sign-in and install id. */
    private static byte[] mergeSettings(byte[] backedUp, Path currentFile) throws IOException {
        ObjectNode restored = (ObjectNode) JSON.readTree(backedUp);
        if (Files.isRegularFile(currentFile)) {
            ObjectNode current = (ObjectNode) JSON.readTree(currentFile.toFile());
            for (String key : LOCAL_ONLY_SETTINGS) if (current.has(key)) restored.set(key, current.get(key));
        }
        return JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(restored);
    }

    private static byte[] readLimited(InputStream input) throws IOException {
        byte[] data = input.readNBytes(512 * 1024 * 1024 + 1);
        if (data.length > 512 * 1024 * 1024) throw new IOException("A file in the backup is too large.");
        return data;
    }
}
