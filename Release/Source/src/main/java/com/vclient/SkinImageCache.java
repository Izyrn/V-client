package com.vclient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.function.Consumer;

/**
 * Disk cache for skin and cape PNGs shown in the launcher. Entries are keyed by the texture URL, and
 * Ely.by / Mojang texture URLs change whenever the skin changes, so a changed skin is fetched fresh
 * while an unchanged one loads instantly and still works offline.
 */
final class SkinImageCache {
    /** Largest PNG accepted, matching the previous in-memory limit. */
    static final int MAX_BYTES = 4_000_000;

    @FunctionalInterface
    interface Fetcher {
        /** Returns the image bytes, or {@code null} when the texture could not be downloaded. */
        byte[] fetch(String url) throws IOException, InterruptedException;
    }

    private final Path directory;
    private final Consumer<String> log;

    SkinImageCache(Path directory, Consumer<String> log) {
        this.directory = directory;
        this.log = log;
    }

    static Path defaultDirectory() {
        return AppInfo.dataDir().resolve("skin-cache");
    }

    /** Cached bytes for the URL, downloading (and caching) them first when missing. */
    byte[] load(String url, Fetcher fetcher, boolean allowDownload) throws IOException, InterruptedException {
        if (url == null || url.isBlank()) return null;
        Path file = directory.resolve(key(url) + ".png");
        if (Files.isRegularFile(file)) {
            byte[] cached = Files.readAllBytes(file);
            if (cached.length > 0 && cached.length <= MAX_BYTES) {
                log.accept("Skin cache hit: " + url);
                return cached;
            }
        }
        if (!allowDownload) {
            log.accept("Skin cache miss while offline: " + url);
            return null;
        }
        log.accept("Skin download started: " + url);
        byte[] downloaded = fetcher.fetch(url);
        if (downloaded == null || downloaded.length == 0 || downloaded.length > MAX_BYTES) {
            log.accept("Skin download failed or was rejected: " + url);
            return null;
        }
        Files.createDirectories(directory);
        Path temporary = Files.createTempFile(directory, "skin", ".tmp");
        try {
            Files.write(temporary, downloaded);
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
        log.accept("Skin downloaded and cached (" + downloaded.length + " bytes): " + url);
        return downloaded;
    }

    static String key(String url) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(url.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
