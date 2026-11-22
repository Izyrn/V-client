package com.vclient;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class LocalMinecraftImporterTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void copiesProfilesLibrariesAndAssetsWithoutChangingSource() throws Exception {
        Path source = temporaryDirectory.resolve("ExistingMinecraft");
        Path profile = source.resolve("versions").resolve("Fabric 1.21.11");
        Path library = source.resolve("libraries").resolve("fabric-loader.jar");
        Path asset = source.resolve("assets").resolve("objects").resolve("test-asset");
        Files.createDirectories(profile);
        Files.createDirectories(library.getParent());
        Files.createDirectories(asset.getParent());
        Files.writeString(profile.resolve("Fabric 1.21.11.json"), "{\"id\":\"Fabric 1.21.11\"}");
        Files.writeString(library, "library");
        Files.writeString(asset, "asset");
        Path destination = temporaryDirectory.resolve("VYRN").resolve("minecraft");
        List<String> progress = new ArrayList<>();

        LocalMinecraftImporter.importGameFiles(source, destination, progress::add);

        assertEquals(Files.readString(profile.resolve("Fabric 1.21.11.json")),
                Files.readString(destination.resolve("versions/Fabric 1.21.11/Fabric 1.21.11.json")));
        assertEquals("library", Files.readString(destination.resolve("libraries/fabric-loader.jar")));
        assertEquals("asset", Files.readString(destination.resolve("assets/objects/test-asset")));
        assertTrue(Files.exists(profile.resolve("Fabric 1.21.11.json")));
        assertTrue(progress.getLast().contains("original files were left untouched"));
    }

    @Test
    void keepsExistingDestinationFilesAndRejectsNonGameFolders() throws Exception {
        Path source = temporaryDirectory.resolve("source");
        Path profile = source.resolve("versions").resolve("1.21.11");
        Files.createDirectories(profile);
        Files.writeString(profile.resolve("1.21.11.json"), "{\"id\":\"1.21.11\"}");
        Path destination = temporaryDirectory.resolve("destination");
        Path destinationMetadata = destination.resolve("versions/1.21.11/1.21.11.json");
        Files.createDirectories(destinationMetadata.getParent());
        Files.writeString(destinationMetadata, "keep");

        LocalMinecraftImporter.importGameFiles(source, destination, ignored -> {});

        assertEquals("keep", Files.readString(destinationMetadata));
        assertThrows(IOException.class,
                () -> LocalMinecraftImporter.importGameFiles(temporaryDirectory, destination, ignored -> {}));
    }

    @Test
    void refusesImportIntoOrFromNestedGameDirectories() throws Exception {
        Path source = temporaryDirectory.resolve("source");
        Path profile = source.resolve("versions").resolve("1.21.11");
        Files.createDirectories(profile);
        Files.writeString(profile.resolve("1.21.11.json"), "{\"id\":\"1.21.11\"}");

        assertThrows(IOException.class, () ->
                LocalMinecraftImporter.importGameFiles(source, source.resolve("VYRN"), ignored -> {}));
    }
}
