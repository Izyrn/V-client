package com.vclient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Minecraft opens its pause menu whenever its window loses focus. While the replay buffer records, anything that
 * briefly takes focus from a full-screen game (a notification, a file being written, an overlay) makes the menu pop up
 * in the middle of play. This turns that option off in options.txt; it can be switched back on in Options > Controls.
 */
final class GameOptionsFix {
    private GameOptionsFix() {}

    /** Sets {@code pauseOnLostFocus:false}. Returns true when the file changed; a missing file is left alone. */
    static boolean keepRunningWhenUnfocused(Path minecraftDirectory) throws IOException {
        Path file = minecraftDirectory.toAbsolutePath().normalize().resolve("options.txt");
        if (!Files.isRegularFile(file)) return false;
        List<String> lines = new ArrayList<>(Files.readAllLines(file, StandardCharsets.UTF_8));
        boolean found = false;
        boolean changed = false;
        for (int i = 0; i < lines.size(); i++) {
            if (!lines.get(i).startsWith("pauseOnLostFocus:")) continue;
            found = true;
            if (!lines.get(i).equals("pauseOnLostFocus:false")) {
                lines.set(i, "pauseOnLostFocus:false");
                changed = true;
            }
        }
        if (!found) {
            lines.add("pauseOnLostFocus:false");
            changed = true;
        }
        if (!changed) return false;
        Path temporary = Files.createTempFile(file.getParent(), ".vclient-", ".tmp");
        try {
            Files.write(temporary, lines, StandardCharsets.UTF_8);
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
        return true;
    }
}
