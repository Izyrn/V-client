package com.vclient;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.function.Consumer;

final class LocalMinecraftImporter {
    private static final List<String> GAME_DIRECTORIES = List.of("versions", "libraries", "assets");

    private LocalMinecraftImporter() {}

    static void importGameFiles(Path source, Path destination, Consumer<String> onProgress) throws IOException {
        Path sourceRoot = source.toAbsolutePath().normalize();
        Path destinationRoot = destination.toAbsolutePath().normalize();
        if (!Files.isDirectory(sourceRoot) || !Files.isDirectory(sourceRoot.resolve("versions"))) {
            throw new IOException("Choose a Minecraft game folder that contains a versions directory.");
        }
        if (sourceRoot.equals(destinationRoot)
                || sourceRoot.startsWith(destinationRoot) || destinationRoot.startsWith(sourceRoot)) {
            throw new IOException("The import source and VYRN game folder must be separate directories.");
        }
        if (!hasProfiles(sourceRoot.resolve("versions"))) {
            throw new IOException("No installed Minecraft profiles were found in the selected source folder.");
        }

        Files.createDirectories(destinationRoot);
        int[] copied = {0};
        for (String directory : GAME_DIRECTORIES) {
            Path sourceDirectory = sourceRoot.resolve(directory);
            if (!Files.isDirectory(sourceDirectory)) continue;
            Path destinationDirectory = destinationRoot.resolve(directory);
            Files.walkFileTree(sourceDirectory, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path current, BasicFileAttributes attributes)
                        throws IOException {
                    Path target = destinationDirectory.resolve(sourceDirectory.relativize(current));
                    Files.createDirectories(target);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path current, BasicFileAttributes attributes)
                        throws IOException {
                    if (!attributes.isRegularFile()) return FileVisitResult.CONTINUE;
                    Path target = destinationDirectory.resolve(sourceDirectory.relativize(current));
                    if (Files.exists(target)) return FileVisitResult.CONTINUE;
                    Files.createDirectories(target.getParent());
                    Files.copy(current, target, StandardCopyOption.COPY_ATTRIBUTES);
                    copied[0]++;
                    if (copied[0] == 1 || copied[0] % 100 == 0) {
                        onProgress.accept("Copied " + copied[0] + " local Minecraft files...");
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        }

        if (!hasProfiles(destinationRoot.resolve("versions"))) {
            throw new IOException("The import did not produce a readable Minecraft profile in the VYRN folder.");
        }
        onProgress.accept("Local import complete. Copied " + copied[0]
                + " files into " + destinationRoot + "; the original files were left untouched.");
    }

    private static boolean hasProfiles(Path versionsDirectory) throws IOException {
        try (var directories = Files.list(versionsDirectory)) {
            return directories.filter(Files::isDirectory).anyMatch(directory -> {
                String id = directory.getFileName().toString();
                return Files.isRegularFile(directory.resolve(id + ".json"));
            });
        }
    }
}
