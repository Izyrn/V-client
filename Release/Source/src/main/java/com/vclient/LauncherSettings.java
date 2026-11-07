package com.vclient;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

final class LauncherSettings {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path SETTINGS_FILE = Path.of(
            System.getenv().getOrDefault("APPDATA", System.getProperty("user.home")),
            "V-Client", "settings.json");

    private Path minecraftDirectory;
    private int memoryGb;
    private int javaMajorOverride;
    private String lastAccountId;
    private String discordInvite;
    private boolean offlineMode;
    private String backendUrl = "";
    /** V-Client account session per game account, so signing in as one player never signs in the others. */
    private final java.util.Map<String, String> backendTokens = new java.util.LinkedHashMap<>();
    private String installId = "";
    private boolean autoUpdate = true;
    /** Logs every backend request with its URL, status and stack traces of failures. */
    private boolean debugMode;
    /** VSMP Boost Mode: when on, the chosen profile's video settings, JVM flags and memory apply at every launch. */
    private boolean boostEnabled;
    private BoostProfile boostProfile = BoostProfile.BALANCED;
    /** Keeps the last 30 seconds of the game window in memory so the clip hotkey can save them. */
    private boolean replayBufferEnabled = true;
    /** Also record the microphone into clips (game/system sound is always recorded). Off by default for privacy. */
    private boolean clipMicrophone;
    /** A {@link Theme} preset id, or "custom" to use {@link #customAccent}. */
    private String themeId = "night";
    private String customAccent = "#e6bd59";
    /** Name of the key that saves the last 30 seconds (a key of {@link WinInput#HOTKEYS}). */
    private String clipHotkey = WinInput.DEFAULT_HOTKEY;
    /**
     * The only admin: the Ely.by account Izyrn, identified by its permanent Ely.by UUID (names can change, the UUID
     * cannot). It is fixed here on purpose and is not read from settings.json, so editing that file cannot grant it.
     */
    static final String ADMIN_ELY_UUID = "1331c81d33844eb6baf6a511c8298af3";
    /** Ids of accounts whose owner picked "Hacker / Cheater" instead of "Normal player". */
    private final Set<String> hackerAccountIds = new LinkedHashSet<>();

    private LauncherSettings(Path minecraftDirectory, int memoryGb, int javaMajorOverride,
                             String lastAccountId, String discordInvite, boolean offlineMode) {
        this.minecraftDirectory = minecraftDirectory;
        this.memoryGb = memoryGb;
        this.javaMajorOverride = javaMajorOverride;
        this.lastAccountId = lastAccountId;
        this.discordInvite = discordInvite;
        this.offlineMode = offlineMode;
    }

    /** Settings with defaults and nothing read from or written to disk (for tests). */
    static LauncherSettings defaults(Path minecraftDirectory) {
        return new LauncherSettings(minecraftDirectory, 4, 0, "", "", false);
    }

    static LauncherSettings load() throws IOException {
        Path appData = Path.of(System.getenv().getOrDefault("APPDATA",
                Path.of(System.getProperty("user.home"), "AppData", "Roaming").toString()));
        Path defaultDirectory = privateMinecraftDirectory(appData);
        if (!Files.exists(SETTINGS_FILE)) {
            LauncherSettings fresh = new LauncherSettings(defaultDirectory, defaultMemory(), 0, "", "", true);
            fresh.installId = java.util.UUID.randomUUID().toString();
            return fresh;
        }
        ObjectNode saved = (ObjectNode) JSON.readTree(SETTINGS_FILE.toFile());
        String directory = saved.path("minecraftDirectory").asText(defaultDirectory.toString());
        int memory = saved.path("memoryGb").asInt(defaultMemory());
        Path selectedDirectory = Path.of(directory);
        Path minecraftDirectory = migrateLegacyDirectory(
                normalizeMinecraftDirectory(selectedDirectory), appData);
        LauncherSettings settings = new LauncherSettings(minecraftDirectory,
                Math.clamp(memory, 1, maxMemory()), saved.path("javaMajorOverride").asInt(0) == 25 ? 25 : 0,
                saved.path("lastAccountId").asText(""),
                saved.path("discordInvite").asText(""),
                saved.path("offlineMode").asBoolean(true));
        settings.backendUrl = saved.path("backendUrl").asText("");
        if (BackendClient.normalize(settings.backendUrl).equalsIgnoreCase(AppInfo.RETIRED_BACKEND_URL)) {
            settings.backendUrl = "";
        }
        saved.path("backendTokens").fields().forEachRemaining(entry -> {
            if (!entry.getValue().asText("").isBlank()) settings.backendTokens.put(entry.getKey(), entry.getValue().asText());
        });
        // Older versions kept one session for the whole launcher; give it to the account that was active then.
        String legacyToken = saved.path("backendToken").asText("");
        if (!legacyToken.isBlank() && settings.backendTokens.isEmpty() && settings.lastAccountId != null
                && !settings.lastAccountId.isBlank()) {
            settings.backendTokens.put(settings.lastAccountId, legacyToken);
        }
        settings.autoUpdate = saved.path("autoUpdate").asBoolean(true);
        settings.debugMode = saved.path("debugMode").asBoolean(false);
        settings.boostEnabled = saved.path("boostEnabled").asBoolean(false);
        settings.boostProfile = BoostProfile.parse(saved.path("boostProfile").asText(""), BoostProfile.BALANCED);
        settings.replayBufferEnabled = saved.path("replayBufferEnabled").asBoolean(true);
        settings.clipMicrophone = saved.path("clipMicrophone").asBoolean(false);
        settings.themeId = saved.path("themeId").asText("night");
        String accent = saved.path("customAccent").asText("#e6bd59");
        settings.customAccent = Theme.validHex(accent) ? accent : "#e6bd59";
        String hotkey = saved.path("clipHotkey").asText(WinInput.DEFAULT_HOTKEY);
        settings.clipHotkey = WinInput.HOTKEYS.containsKey(hotkey) ? hotkey : WinInput.DEFAULT_HOTKEY;
        settings.installId = saved.path("installId").asText("");
        saved.path("hackerAccountIds").forEach(id -> settings.hackerAccountIds.add(id.asText("")));
        boolean newInstallId = settings.installId.isBlank();
        if (newInstallId) settings.installId = java.util.UUID.randomUUID().toString();
        if (!minecraftDirectory.equals(selectedDirectory) || newInstallId) settings.save();
        return settings;
    }

    static Path privateMinecraftDirectory(Path appData) {
        return appData.resolve("V-Client").resolve("minecraft");
    }

    static Path migrateLegacyDirectory(Path configured, Path appData) {
        Path oldLauncherRoot = appData.resolve("V-Client").toAbsolutePath().normalize();
        Path oldDefaultDirectory = appData.resolve(".minecraft").toAbsolutePath().normalize();
        Path normalizedConfigured = configured.toAbsolutePath().normalize();
        if (normalizedConfigured.equals(oldLauncherRoot) || normalizedConfigured.equals(oldDefaultDirectory)) {
            return privateMinecraftDirectory(appData);
        }
        return configured;
    }

    static Path normalizeMinecraftDirectory(Path directory) {
        Path versionsDirectory = directory.getParent();
        if (versionsDirectory == null || versionsDirectory.getFileName() == null
                || !versionsDirectory.getFileName().toString().equalsIgnoreCase("versions")
                || directory.getFileName() == null) {
            return directory;
        }
        Path profileMetadata = directory.resolve(directory.getFileName() + ".json");
        if (!Files.isRegularFile(profileMetadata)) return directory;
        Path gameDirectory = versionsDirectory.getParent();
        return gameDirectory == null ? directory : gameDirectory;
    }

    void save() throws IOException {
        Files.createDirectories(SETTINGS_FILE.getParent());
        ObjectNode saved = JSON.createObjectNode();
        saved.put("minecraftDirectory", minecraftDirectory.toString());
        saved.put("memoryGb", memoryGb);
        saved.put("javaMajorOverride", javaMajorOverride);
        saved.put("lastAccountId", lastAccountId);
        saved.put("discordInvite", discordInvite);
        saved.put("offlineMode", offlineMode);
        saved.put("backendUrl", backendUrl);
        ObjectNode tokens = saved.putObject("backendTokens");
        backendTokens.forEach(tokens::put);
        saved.put("autoUpdate", autoUpdate);
        saved.put("debugMode", debugMode);
        saved.put("boostEnabled", boostEnabled);
        saved.put("boostProfile", boostProfile.name());
        saved.put("replayBufferEnabled", replayBufferEnabled);
        saved.put("clipMicrophone", clipMicrophone);
        saved.put("themeId", themeId);
        saved.put("customAccent", customAccent);
        saved.put("clipHotkey", clipHotkey);
        saved.put("installId", installId);
        var hackers = saved.putArray("hackerAccountIds");
        hackerAccountIds.forEach(hackers::add);
        JSON.writerWithDefaultPrettyPrinter().writeValue(SETTINGS_FILE.toFile(), saved);
    }

    Path minecraftDirectory() {
        return minecraftDirectory;
    }

    void setMinecraftDirectory(Path directory) {
        minecraftDirectory = directory;
    }

    int memoryGb() {
        return memoryGb;
    }

    int javaMajorOverride() {
        return javaMajorOverride;
    }

    void setJavaMajorOverride(int javaMajorOverride) {
        if (javaMajorOverride != 0 && javaMajorOverride != 25) {
            throw new IllegalArgumentException("Only Java 25 can be selected as a runtime override.");
        }
        this.javaMajorOverride = javaMajorOverride;
    }

    String lastAccountId() {
        return lastAccountId;
    }

    void setLastAccountId(String lastAccountId) {
        this.lastAccountId = lastAccountId;
    }

    void setMemoryGb(int memoryGb) {
        this.memoryGb = memoryGb;
    }

    String discordInvite() {
        return discordInvite;
    }

    void setDiscordInvite(String discordInvite) {
        this.discordInvite = discordInvite;
    }

    boolean offlineMode() {
        return offlineMode;
    }

    void setOfflineMode(boolean offlineMode) {
        this.offlineMode = offlineMode;
    }

    boolean boostEnabled() {
        return boostEnabled;
    }

    void setBoostEnabled(boolean boostEnabled) {
        this.boostEnabled = boostEnabled;
    }

    BoostProfile boostProfile() {
        return boostProfile;
    }

    void setBoostProfile(BoostProfile boostProfile) {
        this.boostProfile = boostProfile == null ? BoostProfile.BALANCED : boostProfile;
    }

    String themeId() {
        return themeId;
    }

    String customAccent() {
        return customAccent;
    }

    void setTheme(String themeId, String customAccent) {
        this.themeId = themeId == null || themeId.isBlank() ? "night" : themeId;
        if (Theme.validHex(customAccent)) this.customAccent = customAccent;
    }

    boolean replayBufferEnabled() {
        return replayBufferEnabled;
    }

    void setReplayBufferEnabled(boolean replayBufferEnabled) {
        this.replayBufferEnabled = replayBufferEnabled;
    }

    boolean clipMicrophone() {
        return clipMicrophone;
    }

    void setClipMicrophone(boolean clipMicrophone) {
        this.clipMicrophone = clipMicrophone;
    }

    String clipHotkey() {
        return clipHotkey;
    }

    void setClipHotkey(String clipHotkey) {
        this.clipHotkey = WinInput.HOTKEYS.containsKey(clipHotkey) ? clipHotkey : WinInput.DEFAULT_HOTKEY;
    }

    boolean debugMode() {
        return debugMode;
    }

    void setDebugMode(boolean debugMode) {
        this.debugMode = debugMode;
    }

    String backendUrl() {
        return backendUrl;
    }

    void setBackendUrl(String backendUrl) {
        this.backendUrl = backendUrl == null ? "" : backendUrl;
    }

    /** Session token of the V-Client account signed in for the active game account; empty when signed out. */
    String backendToken() {
        return backendTokens.getOrDefault(lastAccountId == null ? "" : lastAccountId, "");
    }

    void setBackendToken(String backendToken) {
        String key = lastAccountId == null ? "" : lastAccountId;
        if (backendToken == null || backendToken.isBlank()) backendTokens.remove(key);
        else if (!key.isBlank()) backendTokens.put(key, backendToken);
    }

    /** Random id for anonymous usage counts. It is not derived from the PC or the player. */
    String installId() {
        return installId;
    }

    boolean autoUpdate() {
        return autoUpdate;
    }

    void setAutoUpdate(boolean autoUpdate) {
        this.autoUpdate = autoUpdate;
    }

    boolean isAdmin(PlayerAccount account) {
        return account != null && account.type() == PlayerAccount.Type.ELY_BY && account.uuid() != null
                && ADMIN_ELY_UUID.equalsIgnoreCase(account.uuid().replace("-", ""));
    }

    boolean hackerSelected(PlayerAccount account) {
        return account != null && hackerAccountIds.contains(account.id());
    }

    void setHackerSelected(PlayerAccount account, boolean hacker) {
        if (account == null) return;
        // Only the admin can ever turn Hacker / Cheater on; for anyone else the choice is ignored.
        if (hacker && !isAdmin(account)) return;
        if (hacker) hackerAccountIds.add(account.id());
        else hackerAccountIds.remove(account.id());
    }

    /** Cheat modules are enabled only for an admin account that chose Hacker / Cheater. */
    boolean cheatsEnabled(PlayerAccount account) {
        return isAdmin(account) && hackerSelected(account);
    }

    /** The most game memory the sliders offer. It used to follow the launcher's own heap (9 GB here). */
    static final int MAX_MEMORY_GB = 32;

    static int maxMemory() {
        return MAX_MEMORY_GB;
    }

    private static int defaultMemory() {
        return Math.clamp((int) (Runtime.getRuntime().maxMemory() / (1024 * 1024 * 1024)) / 2, 2, maxMemory());
    }
}
