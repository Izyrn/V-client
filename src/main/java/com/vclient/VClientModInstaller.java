package com.vclient;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

/**
 * Installs the bundled "VYRN 1.21.11" Fabric profile (Fabric Loader, its libraries,
 * and the VYRN in-game mod) from the launcher's own resources. Nothing is downloaded.
 */
final class VClientModInstaller {
    static final String PROFILE_ID = "VYRN 1.21.11";
    /** The same profile before the VYRN rename; removed once the new one is installed so the list shows it once. */
    static final String LEGACY_PROFILE_ID = "V-Client 1.21.11";
    static final String PARENT_VERSION = "1.21.11";
    private static final String RESOURCE_ROOT = "/vclient-fabric/";

    private VClientModInstaller() {}

    /**
     * Copies any missing or outdated bundled files into the game folder. Returns false
     * without changing anything when Minecraft 1.21.11 itself is not installed there.
     */
    static boolean ensureInstalled(Path minecraftDirectory, Consumer<String> onOutput) throws IOException {
        Path root = minecraftDirectory.toAbsolutePath().normalize();
        Path parent = root.resolve("versions").resolve(PARENT_VERSION).resolve(PARENT_VERSION + ".json");
        if (!Files.isRegularFile(parent)) return false;
        int updated = 0;
        for (String entry : manifest()) {
            Path target = root.resolve(entry).normalize();
            if (!target.startsWith(root.resolve("libraries")) && !target.startsWith(root.resolve("versions"))) {
                throw new IOException("A bundled VYRN file has an unsafe path: " + entry);
            }
            byte[] bundled = resource(entry);
            if (Files.isRegularFile(target) && Arrays.equals(Files.readAllBytes(target), bundled)) continue;
            Files.createDirectories(target.getParent());
            Path temporary = Files.createTempFile(target.getParent(), ".vclient-", ".tmp");
            try {
                Files.write(temporary, bundled);
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temporary);
            }
            updated++;
        }
        removeLegacyProfile(root, onOutput);
        if (updated > 0) {
            onOutput.accept("Installed the " + AppInfo.NAME + " " + PARENT_VERSION + " profile (Fabric + " + AppInfo.NAME + " mod): "
                    + updated + " bundled files copied into " + root + ".");
        }
        return true;
    }

    /**
     * Deletes versions/V-Client 1.21.11 left by 1.7.x. It only holds the profile JSON (libraries, mods, worlds and options
     * live elsewhere and are shared), so nothing a player made is lost. Never fails the install.
     */
    static void removeLegacyProfile(Path root, Consumer<String> onOutput) {
        Path legacy = root.resolve("versions").resolve(LEGACY_PROFILE_ID);
        if (!Files.isDirectory(legacy)) return;
        try (var files = Files.walk(legacy)) {
            for (Path file : files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
            onOutput.accept("Removed the old " + LEGACY_PROFILE_ID + " profile (now " + PROFILE_ID + ").");
        } catch (IOException | RuntimeException e) {
            onOutput.accept("Could not remove the old " + LEGACY_PROFILE_ID + " profile: " + e.getMessage());
        }
    }

    private static List<String> manifest() throws IOException {
        return new String(resource("manifest.txt"), StandardCharsets.UTF_8).lines()
                .map(String::strip).filter(line -> !line.isEmpty()).toList();
    }

    private static byte[] resource(String entry) throws IOException {
        try (InputStream input = VClientModInstaller.class.getResourceAsStream(RESOURCE_ROOT + entry)) {
            if (input == null) throw new IOException("The launcher is missing its bundled file: " + entry);
            return input.readAllBytes();
        }
    }
}
