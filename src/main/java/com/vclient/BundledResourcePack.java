package com.vclient;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

/**
 * Installs the bundled resource packs into the game's resourcepacks folder and switches each one on the first time
 * only, so a player who turns one off in Options stays off. Nothing is downloaded. The packs only change how things
 * look, never anything that affects gameplay:
 * <ul>
 *   <li>"Ultra Smooth PvP": low fire, clear glass, clean crosshair, soft clouds, lowered shield.</li>
 *   <li>"VYRN UI": the ice-style hotbar, hearts and boss bars (Custom Hotbar / Hearts / Boss Bars).</li>
 * </ul>
 */
final class BundledResourcePack {
    static final String FILE_NAME = "Ultra Smooth PvP.zip";
    static final String UI_FILE_NAME = "VYRN UI.zip";

    /** A bundled pack and the marker file that records it was switched on once. */
    private record Pack(String fileName, String label, String marker) {
        String entry() {
            return "file/" + fileName;
        }
    }

    private static final List<Pack> PACKS = List.of(
            new Pack(FILE_NAME, "Ultra Smooth PvP", "pack-enabled"),
            new Pack(UI_FILE_NAME, "VYRN UI (ice hotbar, hearts and boss bars)", "ui-pack-enabled"));

    private BundledResourcePack() {}

    static Path markerFile(Path minecraftDirectory) {
        return minecraftDirectory.resolve("vclient").resolve("pack-enabled");
    }

    /** True for the Minecraft releases the packs were built for (resource pack formats 46..75 = 1.21.4 to 1.21.11). */
    static boolean supports(String clientVersionId) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("1\\.21\\.(\\d+)").matcher(clientVersionId == null ? "" : clientVersionId);
        if (!m.matches()) return false;
        int patch = Integer.parseInt(m.group(1));
        return patch >= 4 && patch <= 11;
    }

    /**
     * Copies the packs into resourcepacks if they are missing or outdated, so they always show up in the in-game
     * Resource Packs list. Does not switch them on. Returns true if a file was written.
     */
    static boolean ensureInstalled(Path minecraftDirectory, Consumer<String> onOutput) throws IOException {
        Path root = minecraftDirectory.toAbsolutePath().normalize();
        boolean changed = false;
        for (Pack pack : PACKS) {
            byte[] bundled;
            try (InputStream input = BundledResourcePack.class.getResourceAsStream("/vclient-pack/" + pack.fileName())) {
                if (input == null) throw new IOException("The launcher is missing its bundled resource pack " + pack.fileName());
                bundled = input.readAllBytes();
            }
            Path target = root.resolve("resourcepacks").resolve(pack.fileName());
            if (Files.isRegularFile(target) && Arrays.equals(Files.readAllBytes(target), bundled)) continue;
            Files.createDirectories(target.getParent());
            Path temporary = Files.createTempFile(target.getParent(), ".vclient-", ".tmp");
            try {
                Files.write(temporary, bundled);
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temporary);
            }
            onOutput.accept("Installed the " + pack.label() + " resource pack into " + target.getParent() + ".");
            changed = true;
        }
        return changed;
    }

    /** Installs the packs and, the first time only for each, switches it on in options.txt. True if anything changed. */
    static boolean ensureInstalledAndEnabled(Path minecraftDirectory, Consumer<String> onOutput) throws IOException {
        boolean changed = ensureInstalled(minecraftDirectory, onOutput);
        Path root = minecraftDirectory.toAbsolutePath().normalize();
        for (Pack pack : PACKS) {
            Path marker = root.resolve("vclient").resolve(pack.marker());
            if (Files.exists(marker)) continue;
            if (enable(root.resolve("options.txt"), pack.entry())) {
                onOutput.accept("Turned on the " + pack.label() + " resource pack (you can switch it off in Options > Resource Packs).");
            }
            Files.createDirectories(marker.getParent());
            Files.writeString(marker, "enabled once; delete this file to have the launcher enable the pack again\n");
            changed = true;
        }
        return changed;
    }

    /** Adds the Ultra Smooth PvP pack to options.txt (kept for callers that only know that pack). */
    static boolean enable(Path optionsFile) throws IOException {
        return enable(optionsFile, PACKS.getFirst().entry());
    }

    /** Adds a pack to the resourcePacks line of options.txt (last = highest priority). False if already listed. */
    static boolean enable(Path optionsFile, String entry) throws IOException {
        List<String> lines = Files.isRegularFile(optionsFile)
                ? new ArrayList<>(Files.readAllLines(optionsFile, StandardCharsets.UTF_8)) : new ArrayList<>();
        boolean written = false;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (!line.startsWith("resourcePacks:")) continue;
            if (line.contains("\"" + entry + "\"")) return false;
            int close = line.lastIndexOf(']');
            if (close < 0) return false; // unrecognised format: leave the player's file alone
            String head = line.substring(0, close);
            lines.set(i, head + (head.endsWith("[") ? "" : ",") + "\"" + entry + "\"]");
            written = true;
            break;
        }
        if (!written) lines.add("resourcePacks:[\"vanilla\",\"" + entry + "\"]");
        Path temporary = Files.createTempFile(optionsFile.toAbsolutePath().getParent(), ".vclient-", ".tmp");
        try {
            Files.write(temporary, lines, StandardCharsets.UTF_8);
            Files.move(temporary, optionsFile, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
        return true;
    }
}
