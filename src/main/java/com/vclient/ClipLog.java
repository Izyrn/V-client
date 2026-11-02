package com.vclient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * What the replay buffer did, in {@code %APPDATA%/V-Client/clip-debug.log}: which capture method it uses, whether the
 * game window was found and in front, hotkey presses, saves and every error (which used to be swallowed). Cut back to a
 * fresh start once it passes 1 MB.
 */
final class ClipLog {
    private static final Path FILE = Path.of(System.getenv().getOrDefault("APPDATA", System.getProperty("user.home")),
            "V-Client", "clip-debug.log");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS");

    private ClipLog() {}

    static synchronized void write(String line) {
        try {
            Files.createDirectories(FILE.getParent());
            if (Files.isRegularFile(FILE) && Files.size(FILE) > 1_000_000) Files.delete(FILE);
            Files.writeString(FILE, LocalDateTime.now().format(TIME) + " " + line + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            // diagnostics only
        }
    }
}
