package com.vclient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

/**
 * VYRN's recordings folder, its favourites, and the importer that picks up videos other recorders (OBS, Xbox
 * Game Bar, GeForce, the replay buffer) wrote while Minecraft was running. Imported videos are copied, so the
 * recorder's own file stays where it was.
 */
final class RecordingLibrary {
    static final Set<String> VIDEO_EXTENSIONS = Set.of("mp4", "mkv", "mov", "webm", "avi", "flv", "m4v");
    private static final ObjectMapper JSON = new ObjectMapper();

    record Recording(Path file, String name, long modifiedMillis, long sizeBytes, long durationMillis,
                     boolean favorite) {}

    private final Path folder;
    private final Path indexFile;
    private final Set<String> favorites = new LinkedHashSet<>();
    private final Set<String> importedSources = new LinkedHashSet<>();

    RecordingLibrary(Path folder) {
        this.folder = folder;
        this.indexFile = folder.resolve("recordings.json");
        load();
    }

    static Path defaultFolder() {
        return AppInfo.dataDir().resolve("recordings");
    }

    Path folder() {
        return folder;
    }

    /** Folders where recorders commonly write, plus the game's own. Only folders that exist are returned. */
    static List<Path> watchedFolders(Path minecraftDirectory) {
        Path home = Path.of(System.getProperty("user.home"));
        List<Path> candidates = List.of(
                home.resolve("Videos"), home.resolve("Videos").resolve("Captures"),
                home.resolve("Videos").resolve("OBS"),
                minecraftDirectory.resolve("replay_recordings"), minecraftDirectory.resolve("clips"));
        List<Path> existing = new ArrayList<>();
        for (Path candidate : candidates) if (Files.isDirectory(candidate)) existing.add(candidate);
        return existing;
    }

    static boolean isVideo(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        return dot > 0 && VIDEO_EXTENSIONS.contains(name.substring(dot + 1));
    }

    // ---- listing ---------------------------------------------------------------------------------

    synchronized List<Recording> list() {
        List<Recording> result = new ArrayList<>();
        if (!Files.isDirectory(folder)) return result;
        try (Stream<Path> files = Files.list(folder)) {
            for (Path file : (Iterable<Path>) files.filter(Files::isRegularFile).filter(RecordingLibrary::isVideo)::iterator) {
                try {
                    result.add(new Recording(file, file.getFileName().toString(),
                            Files.getLastModifiedTime(file).toMillis(), Files.size(file), durationMillis(file),
                            favorites.contains(file.getFileName().toString())));
                } catch (IOException e) {
                    // A file that vanished while listing is skipped.
                }
            }
        } catch (IOException e) {
            return result;
        }
        result.sort(Comparator.comparing(Recording::favorite).reversed()
                .thenComparing(Comparator.comparingLong(Recording::modifiedMillis).reversed()));
        return result;
    }

    synchronized void setFavorite(Path file, boolean favorite) {
        String name = file.getFileName().toString();
        if (favorite) favorites.add(name);
        else favorites.remove(name);
        save();
    }

    /** Deletes the video from disk. Returns false when it could not be removed. */
    synchronized boolean delete(Path file) {
        Path normalized = file.toAbsolutePath().normalize();
        if (!normalized.startsWith(folder.toAbsolutePath().normalize())) return false;
        try {
            Files.deleteIfExists(normalized);
        } catch (IOException e) {
            return false;
        }
        favorites.remove(normalized.getFileName().toString());
        save();
        return true;
    }

    // ---- importing -------------------------------------------------------------------------------

    /**
     * Videos in {@code sources} written at or after {@code sinceMillis} that are no longer growing (their size
     * matches {@code lastSizes}'s earlier reading) and were not imported before.
     */
    synchronized List<Path> findImportable(List<Path> sources, long sinceMillis, java.util.Map<Path, Long> lastSizes) {
        List<Path> ready = new ArrayList<>();
        Path own = folder.toAbsolutePath().normalize();
        for (Path source : sources) {
            if (source.toAbsolutePath().normalize().equals(own) || !Files.isDirectory(source)) continue;
            try (Stream<Path> files = Files.list(source)) {
                for (Path file : (Iterable<Path>) files.filter(Files::isRegularFile).filter(RecordingLibrary::isVideo)::iterator) {
                    try {
                        long modified = Files.getLastModifiedTime(file).toMillis();
                        long size = Files.size(file);
                        if (modified < sinceMillis || size <= 0 || importedSources.contains(sourceKey(file, size))) continue;
                        Long previous = lastSizes.put(file, size);
                        if (previous != null && previous == size) ready.add(file);
                    } catch (IOException e) {
                        // Locked or removed while a recorder is still writing it: try again next round.
                    }
                }
            } catch (IOException e) {
                // Unreadable folder: nothing to import from it.
            }
        }
        return ready;
    }

    /** Copies a finished recording into the library and remembers it so it is never imported twice. */
    synchronized Path importFile(Path source) throws IOException {
        Files.createDirectories(folder);
        long size = Files.size(source);
        Path target = uniqueTarget(source.getFileName().toString());
        Path temporary = folder.resolve(target.getFileName() + ".part");
        try {
            Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING);
            Files.setLastModifiedTime(temporary, Files.getLastModifiedTime(source));
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temporary);
        }
        importedSources.add(sourceKey(source, size));
        save();
        return target;
    }

    private Path uniqueTarget(String name) {
        Path target = folder.resolve(name);
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String extension = dot > 0 ? name.substring(dot) : "";
        for (int n = 2; Files.exists(target); n++) target = folder.resolve(stem + " (" + n + ")" + extension);
        return target;
    }

    private static String sourceKey(Path file, long size) {
        return file.toAbsolutePath().normalize() + "|" + size;
    }

    // ---- persistence -----------------------------------------------------------------------------

    private void load() {
        try {
            if (!Files.isRegularFile(indexFile)) return;
            JsonNode root = JSON.readTree(indexFile.toFile());
            root.path("favorites").forEach(f -> favorites.add(f.asText()));
            root.path("importedSources").forEach(f -> importedSources.add(f.asText()));
        } catch (IOException e) {
            // A damaged index only forgets favourites.
        }
    }

    private void save() {
        try {
            Files.createDirectories(folder);
            ObjectNode root = JSON.createObjectNode();
            var fav = root.putArray("favorites");
            favorites.forEach(fav::add);
            var imported = root.putArray("importedSources");
            importedSources.forEach(imported::add);
            JSON.writerWithDefaultPrettyPrinter().writeValue(indexFile.toFile(), root);
        } catch (IOException e) {
            // Favourites are a convenience; the videos themselves are untouched.
        }
    }

    // ---- duration --------------------------------------------------------------------------------

    /** Length in milliseconds for MP4/MOV (movie header) and VYRN's own AVI clips; -1 when unknown. */
    static long durationMillis(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".avi")) {
            MjpegAvi.Info info = MjpegAvi.read(file);
            return info == null ? -1 : info.durationMillis();
        }
        if (name.endsWith(".mp4") || name.endsWith(".mov") || name.endsWith(".m4v")) return mp4Duration(file);
        return -1;
    }

    /** Reads {@code moov/mvhd} (timescale and duration) by walking the top-level boxes. */
    static long mp4Duration(Path file) {
        try (RandomAccessFile in = new RandomAccessFile(file.toFile(), "r")) {
            long position = 0;
            long length = in.length();
            while (position + 8 <= length) {
                in.seek(position);
                long size = readUnsigned(in, 4);
                String type = readType(in);
                long header = 8;
                if (size == 1) {
                    size = readUnsigned(in, 8);
                    header = 16;
                } else if (size == 0) {
                    size = length - position;
                }
                if (size < header) return -1;
                if (type.equals("moov")) return findMvhd(in, position + header, position + size);
                position += size;
            }
        } catch (IOException | RuntimeException e) {
            // Fall through: unknown.
        }
        return -1;
    }

    private static long findMvhd(RandomAccessFile in, long start, long end) throws IOException {
        long position = start;
        while (position + 8 <= end) {
            in.seek(position);
            long size = readUnsigned(in, 4);
            String type = readType(in);
            if (size < 8) return -1;
            if (type.equals("mvhd")) {
                int version = in.read();
                in.skipBytes(3);
                long timescale;
                long duration;
                if (version == 1) {
                    in.skipBytes(16);
                    timescale = readUnsigned(in, 4);
                    duration = readUnsigned(in, 8);
                } else {
                    in.skipBytes(8);
                    timescale = readUnsigned(in, 4);
                    duration = readUnsigned(in, 4);
                }
                return timescale <= 0 ? -1 : duration * 1000 / timescale;
            }
            position += size;
        }
        return -1;
    }

    private static long readUnsigned(RandomAccessFile in, int bytes) throws IOException {
        long value = 0;
        for (int i = 0; i < bytes; i++) value = (value << 8) | in.read();
        return value;
    }

    private static String readType(RandomAccessFile in) throws IOException {
        byte[] type = new byte[4];
        in.readFully(type);
        return new String(type, StandardCharsets.ISO_8859_1);
    }
}
