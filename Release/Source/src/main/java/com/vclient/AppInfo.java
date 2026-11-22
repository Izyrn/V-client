package com.vclient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Build-time constants shared by the launcher's backend features. */
final class AppInfo {
    /** The client's name as players see it (window titles, menus, installer, website). */
    static final String NAME = "VYRN";

    /** Launcher version reported to the update service. Bump together with the pom version and the published release. */
    static final String VERSION = "1.8.2";

    /**
     * The one place the API address lives (a Cloudflare Worker, see {@code cloudflare/}). Players can override it
     * on the News page and the {@code VYRN_BACKEND_URL} (or older {@code VCLIENT_BACKEND_URL}) environment variable
     * wins over both. The address keeps its old "vclient" name on purpose: every installed launcher finds its updates
     * there, so it must never move (see tools/check-update-path.ps1).
     */
    static final String DEFAULT_BACKEND_URL = "https://vclient-api.vclient-api.workers.dev";

    /** Old backends that no longer exist; a saved copy of one of these is replaced by the default. */
    static final java.util.List<String> RETIRED_BACKEND_URLS = java.util.List.of(
            "https://vsmp-client.onrender.com", "https://vsmp-client-backend.onrender.com");

    static final String PLATFORM = "windows";

    /**
     * Discord application id for Rich Presence (create one at discord.com/developers/applications, name it VYRN and
     * upload an art asset called "vyrn"). Empty = Rich Presence off.
     */
    static final String DISCORD_APP_ID = "";

    /** Folder name under %APPDATA% for accounts, settings and game files. */
    static final String DATA_FOLDER = "VYRN";
    /** Where versions before the VYRN rename (1.7.x and older, called V-Client) kept everything. */
    static final String LEGACY_DATA_FOLDER = "V-Client";

    private static volatile Path dataDirectory;

    private AppInfo() {
    }

    static boolean isRetiredBackend(String url) {
        String normalized = BackendClient.normalize(url);
        return RETIRED_BACKEND_URLS.stream().anyMatch(normalized::equalsIgnoreCase);
    }

    static Path appData() {
        return Path.of(System.getenv().getOrDefault("APPDATA",
                Path.of(System.getProperty("user.home"), "AppData", "Roaming").toString()));
    }

    /**
     * %APPDATA%\VYRN, holding accounts, settings, caches and the private Minecraft folder. On the first start after the
     * rename the old %APPDATA%\V-Client folder is moved here (same drive, so it is instant and nothing is copied). If the
     * move fails (a file is locked, for example) the old folder keeps being used so no player ever loses their data.
     */
    static Path dataDir() {
        Path cached = dataDirectory;
        if (cached != null) return cached;
        synchronized (AppInfo.class) {
            // Tests (and anyone debugging) can point the launcher at another folder so real data is never moved.
            String override = System.getProperty("vyrn.dataDir", "");
            if (dataDirectory == null) dataDirectory = override.isBlank() ? resolveDataDir(appData()) : Path.of(override);
            return dataDirectory;
        }
    }

    static Path resolveDataDir(Path appData) {
        Path current = appData.resolve(DATA_FOLDER);
        Path legacy = appData.resolve(LEGACY_DATA_FOLDER);
        if (Files.isDirectory(current) || !Files.isDirectory(legacy)) return current;
        try {
            Files.move(legacy, current);
            return current;
        } catch (IOException | RuntimeException e) {
            System.err.println(NAME + " could not move " + legacy + " to " + current + ", keeping the old folder: " + e);
            return legacy;
        }
    }

    /**
     * Rewrites a path saved by an older version that pointed inside %APPDATA%\V-Client (for example the private game
     * folder) so it points at the same place inside the current data folder. Other paths are returned unchanged.
     */
    static Path rebaseLegacyPath(Path saved, Path appData, Path dataDir) {
        Path legacy = appData.resolve(LEGACY_DATA_FOLDER).toAbsolutePath().normalize();
        Path normalized = saved.toAbsolutePath().normalize();
        Path target = dataDir.toAbsolutePath().normalize();
        if (legacy.equals(target) || !normalized.startsWith(legacy)) return saved;
        return target.resolve(legacy.relativize(normalized));
    }
}
