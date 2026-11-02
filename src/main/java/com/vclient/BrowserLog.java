package com.vclient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Errors the built-in browser's pages hit (script errors, failed requests, WebSocket trouble), kept in
 * {@code %APPDATA%/V-Client/browser-debug.log} so a site that stalls can be diagnosed. The file is cut back to a fresh
 * start once it passes 1 MB.
 */
final class BrowserLog {
    private static final Path FILE = Path.of(System.getenv().getOrDefault("APPDATA", System.getProperty("user.home")),
            "V-Client", "browser-debug.log");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static final Map<String, String> URLS = new LinkedHashMap<>(256, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > 600;
        }
    };

    private BrowserLog() {}

    static synchronized void remember(String requestId, String url) {
        URLS.put(requestId, url.length() > 160 ? url.substring(0, 160) : url);
    }

    static synchronized String url(String requestId) {
        return URLS.getOrDefault(requestId, "(unknown request)");
    }

    static synchronized void write(String line) {
        try {
            Files.createDirectories(FILE.getParent());
            if (Files.isRegularFile(FILE) && Files.size(FILE) > 1_000_000) Files.delete(FILE);
            Files.writeString(FILE, LocalTime.now().format(TIME) + " " + (line.length() > 400 ? line.substring(0, 400) : line)
                            + System.lineSeparator(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            // diagnostics only
        }
    }
}
