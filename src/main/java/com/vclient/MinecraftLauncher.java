package com.vclient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.BufferedWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

final class MinecraftLauncher {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = PlatformTls.httpClientBuilder(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL).build();
    private static final String ELY_BY_AUTH_SERVER = "https://authserver.ely.by/api/authlib-injector";
    private static final String VERSION_MANIFEST_URL =
            "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
    private static final String LIBRARIES_URL = "https://libraries.minecraft.net/";
    private static final String ASSETS_URL = "https://resources.download.minecraft.net/";
    private static final Pattern VERSION_ID = Pattern.compile("[A-Za-z0-9._+-]+");
    private static final Pattern PROFILE_ID = Pattern.compile(
            "[A-Za-z0-9](?:[A-Za-z0-9._+ -]{0,126}[A-Za-z0-9_+-])?");
    private static final int MAX_DOWNLOADS = 8;
    private final JavaRuntimeResolver javaRuntimeResolver;
    private volatile java.util.function.Predicate<PlayerAccount> cheatPermission = account -> false;
    private volatile java.util.function.Supplier<String> backendUrl = () -> "";
    private volatile java.util.function.Predicate<PlayerAccount> adminPermission = account -> false;
    private volatile java.util.function.Supplier<BoostProfile.Tuning> boostTuning = () -> null;
    private final ElyByAuthService elyByAuthService = new ElyByAuthService();

    @FunctionalInterface
    interface JavaRuntimeResolver {
        Path resolve(int javaMajor, Consumer<String> onOutput, boolean allowDownloads) throws Exception;
    }

    /** Decides per account whether the in-game cheat modules are unlocked ({@code -Dvclient.cheats=true}). */
    void setCheatPermission(java.util.function.Predicate<PlayerAccount> cheatPermission) {
        this.cheatPermission = cheatPermission;
    }

    /** VSMP Boost Mode: returns the tuning to apply at launch, or null while Boost Mode is off. */
    void setBoostTuning(java.util.function.Supplier<BoostProfile.Tuning> boostTuning) {
        this.boostTuning = boostTuning;
    }

    /** Admin accounts get every cosmetic unlocked in the game ({@code -Dvclient.cosmetics.unlockAll=true}). */
    void setAdminPermission(java.util.function.Predicate<PlayerAccount> adminPermission) {
        this.adminPermission = adminPermission;
    }

    /** Backend address handed to the in-game mod ({@code -Dvclient.backend}) for the cosmetics catalog. */
    void setBackendUrl(java.util.function.Supplier<String> backendUrl) {
        this.backendUrl = backendUrl;
    }

    MinecraftLauncher() {
        this(new JavaRuntimeManager()::resolve);
    }

    MinecraftLauncher(JavaRuntimeResolver javaRuntimeResolver) {
        this.javaRuntimeResolver = javaRuntimeResolver;
    }

    record InstalledVersion(String id, String type, Path jsonFile, String loader) {
        boolean fabric() {
            return "Fabric".equals(loader);
        }

        String displayName() {
            return id + (loader.equals("Vanilla") ? "" : "   ·   " + loader);
        }

        @Override
        public String toString() {
            return displayName();
        }
    }

    record AvailableVersion(String id, String type, String url, String sha1, String releaseTime) {
        @Override
        public String toString() {
            return id;
        }
    }

    record VersionCatalog(String latestReleaseId, String latestSnapshotId, List<AvailableVersion> versions) {}

    private record ResolvedVersion(ObjectNode metadata, String clientVersionId) {}

    static int requiredJavaMajor(JsonNode version, String clientVersionId) {
        if ("1.21.1".equals(clientVersionId)) return 25;
        return version.path("javaVersion").path("majorVersion").asInt(17);
    }

    static InstalledVersion preferredInstalledVersion(List<InstalledVersion> versions) {
        if (versions.isEmpty()) return null;
        for (InstalledVersion version : versions) {
            if (version.id().equals(VClientModInstaller.PROFILE_ID)) return version;
        }
        for (InstalledVersion version : versions) {
            if (version.id().equals("1.21.11")) return version;
        }
        return versions.stream()
                .filter(version -> version.loader().equals("Vanilla"))
                .filter(version -> version.type().equalsIgnoreCase("release"))
                .filter(version -> version.id().matches("\\d+(?:\\.\\d+)+"))
                .max((left, right) -> compareReleaseIds(left.id(), right.id()))
                .orElse(versions.getFirst());
    }

    private static int compareReleaseIds(String left, String right) {
        String[] leftParts = left.split("\\.");
        String[] rightParts = right.split("\\.");
        for (int index = 0; index < Math.max(leftParts.length, rightParts.length); index++) {
            int leftPart = index < leftParts.length ? Integer.parseInt(leftParts[index]) : 0;
            int rightPart = index < rightParts.length ? Integer.parseInt(rightParts[index]) : 0;
            int comparison = Integer.compare(leftPart, rightPart);
            if (comparison != 0) return comparison;
        }
        return 0;
    }

    List<InstalledVersion> findVersions(Path minecraftDirectory) throws IOException {
        Path versionsDirectory = minecraftDirectory.resolve("versions");
        if (!Files.isDirectory(versionsDirectory)) return List.of();
        List<InstalledVersion> versions = new ArrayList<>();
        try (var directories = Files.list(versionsDirectory)) {
            for (Path directory : directories.filter(Files::isDirectory).toList()) {
                String folderId = directory.getFileName().toString();
                if (!PROFILE_ID.matcher(folderId).matches()) continue;
                Path jsonFile = directory.resolve(folderId + ".json");
                if (!Files.isRegularFile(jsonFile)) continue;
                JsonNode metadata = JSON.readTree(jsonFile.toFile());
                String id = metadata.path("id").asText(folderId);
                if (!id.equals(folderId)) {
                    throw new IOException("The installed version metadata ID does not match its folder: " + jsonFile);
                }
                versions.add(new InstalledVersion(id, metadata.path("type").asText("custom"),
                        jsonFile, detectLoader(id, metadata)));
            }
        }
        versions.sort((left, right) -> right.id().compareToIgnoreCase(left.id()));
        return List.copyOf(versions);
    }

    VersionCatalog fetchVersionCatalog(Consumer<String> onOutput) throws IOException, InterruptedException {
        onOutput.accept("Fetching available Minecraft versions from Mojang...");
        JsonNode manifest = getJson(VERSION_MANIFEST_URL, onOutput);
        if (!"piston-meta.mojang.com".equalsIgnoreCase(URI.create(VERSION_MANIFEST_URL).getHost())
                || !manifest.path("versions").isArray()) {
            throw new IOException("Mojang returned an invalid Minecraft version manifest.");
        }
        String latestRelease = manifest.path("latest").path("release").asText("");
        String latestSnapshot = manifest.path("latest").path("snapshot").asText("");
        if (!VERSION_ID.matcher(latestRelease).matches()
                || !VERSION_ID.matcher(latestSnapshot).matches()) {
            throw new IOException("Mojang's version manifest is missing its latest release or snapshot.");
        }
        List<AvailableVersion> versions = new ArrayList<>();
        for (JsonNode version : manifest.path("versions")) {
            String id = version.path("id").asText("");
            String type = version.path("type").asText("");
            if (!VERSION_ID.matcher(id).matches()
                    || !(type.equals("release") || type.equals("snapshot"))) continue;
            String url = version.path("url").asText("");
            URI metadataUri;
            try {
                metadataUri = URI.create(url);
            } catch (IllegalArgumentException e) {
                throw new IOException("Mojang version " + id + " has an invalid metadata URL.", e);
            }
            if (!"https".equalsIgnoreCase(metadataUri.getScheme())
                    || !"piston-meta.mojang.com".equalsIgnoreCase(metadataUri.getHost())) {
                throw new IOException("Refusing version metadata for " + id + " from a non-Mojang host.");
            }
            String sha1 = version.path("sha1").asText("");
            if (!sha1.matches("[0-9a-fA-F]{40}")) {
                throw new IOException("Mojang version " + id + " has an invalid metadata checksum.");
            }
            versions.add(new AvailableVersion(id, type, url, sha1,
                    version.path("releaseTime").asText("")));
        }
        if (versions.stream().noneMatch(version -> version.id().equals(latestRelease)
                && version.type().equals("release"))
                || versions.stream().noneMatch(version -> version.id().equals(latestSnapshot)
                && version.type().equals("snapshot"))) {
            throw new IOException("Mojang's latest version IDs were not present in its version list.");
        }
        onOutput.accept("Mojang version catalog loaded: " + versions.size()
                + " releases and snapshots; latest release=" + latestRelease
                + ", latest snapshot=" + latestSnapshot + ".");
        return new VersionCatalog(latestRelease, latestSnapshot, List.copyOf(versions));
    }

    InstalledVersion installVersion(Path minecraftDirectory, AvailableVersion available,
                                    Consumer<String> onOutput) throws Exception {
        if (available == null || !VERSION_ID.matcher(available.id()).matches()
                || !available.sha1().matches("[0-9a-fA-F]{40}")) {
            throw new IOException("The selected Mojang version descriptor is invalid.");
        }
        Path versionDirectory = minecraftDirectory.resolve("versions").resolve(available.id());
        Files.createDirectories(versionDirectory);
        Path metadataFile = versionDirectory.resolve(available.id() + ".json");
        if (!isCachedFileValid(metadataFile, available.sha1())) {
            Path temporaryMetadata = Files.createTempFile(versionDirectory, available.id(), ".json.tmp");
            try {
                onOutput.accept("Downloading " + available.id() + " version manifest...");
                ensureDownload(available.url(), temporaryMetadata, available.sha1(), onOutput, false);
                JsonNode metadata = JSON.readTree(temporaryMetadata.toFile());
                if (!available.id().equals(metadata.path("id").asText(""))
                        || !available.type().equals(metadata.path("type").asText(""))) {
                    throw new IOException("Downloaded Minecraft manifest does not match selected version "
                            + available.id() + ".");
                }
                try {
                    Files.move(temporaryMetadata, metadataFile, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                    Files.move(temporaryMetadata, metadataFile,
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporaryMetadata);
            }
        }
        InstalledVersion installed = new InstalledVersion(available.id(), available.type(), metadataFile, "Vanilla");
        prepareVersionFiles(minecraftDirectory, installed, onOutput);
        return installed;
    }

    InstalledVersion installLatestRelease(Path minecraftDirectory, Consumer<String> onOutput) throws Exception {
        onOutput.accept("Checking Mojang for the latest stable Minecraft release...");
        JsonNode manifest = getJson(VERSION_MANIFEST_URL, onOutput);
        String latestReleaseId = manifest.path("latest").path("release").asText("");
        if (!PROFILE_ID.matcher(latestReleaseId).matches()) {
            throw new IOException("Mojang's version manifest did not contain a valid latest release ID.");
        }
        JsonNode release = null;
        for (JsonNode version : manifest.path("versions")) {
            if (version.path("id").asText("").equals(latestReleaseId)
                    && version.path("type").asText("").equalsIgnoreCase("release")) {
                release = version;
                break;
            }
        }
        if (release == null) {
            throw new IOException("Mojang's version manifest did not list its latest stable release.");
        }
        String metadataUrl = release.path("url").asText("");
        URI metadataUri;
        try {
            metadataUri = URI.create(metadataUrl);
        } catch (IllegalArgumentException e) {
            throw new IOException("Mojang's version manifest contains an invalid metadata URL.", e);
        }
        if (!"https".equalsIgnoreCase(metadataUri.getScheme())
                || !"piston-meta.mojang.com".equalsIgnoreCase(metadataUri.getHost())) {
            throw new IOException("Refusing Minecraft version metadata from a non-Mojang host.");
        }
        String metadataSha1 = release.path("sha1").asText("");
        if (!metadataSha1.matches("[0-9a-fA-F]{40}")) {
            throw new IOException("Mojang's version manifest did not provide a valid metadata checksum.");
        }

        Path versionDirectory = minecraftDirectory.resolve("versions").resolve(latestReleaseId);
        Files.createDirectories(versionDirectory);
        Path metadataFile = versionDirectory.resolve(latestReleaseId + ".json");
        if (!isCachedFileValid(metadataFile, metadataSha1)) {
            Path temporaryMetadata = Files.createTempFile(versionDirectory, latestReleaseId, ".json.tmp");
            try {
                ensureDownload(metadataUrl, temporaryMetadata, metadataSha1, onOutput, false);
                JsonNode metadata = JSON.readTree(temporaryMetadata.toFile());
                if (!latestReleaseId.equals(metadata.path("id").asText(""))
                        || !"release".equalsIgnoreCase(metadata.path("type").asText(""))) {
                    throw new IOException("Downloaded Minecraft metadata did not match the requested stable release.");
                }
                try {
                    Files.move(temporaryMetadata, metadataFile, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                    Files.move(temporaryMetadata, metadataFile,
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporaryMetadata);
            }
        }
        JsonNode installedMetadata = JSON.readTree(metadataFile.toFile());
        if (!latestReleaseId.equals(installedMetadata.path("id").asText(""))
                || !"release".equalsIgnoreCase(installedMetadata.path("type").asText(""))) {
            throw new IOException("The installed Minecraft version metadata failed validation.");
        }
        onOutput.accept("Minecraft " + latestReleaseId
                + " is ready to install its client, libraries, assets, and Java in V-Client's folder.");
        return new InstalledVersion(latestReleaseId, "release", metadataFile, "Vanilla");
    }

    void launchSingleplayer(Path minecraftDirectory, InstalledVersion selected, int memoryGb,
                            PlayerAccount account, Consumer<String> onOutput) throws Exception {
        onOutput.accept("Starting Minecraft using V-Client's files; missing game files will be downloaded.");
        launch(minecraftDirectory, selected, memoryGb, account, onOutput, false);
    }

    private void prepareVersionFiles(Path minecraftDirectory, InstalledVersion selected,
                                     Consumer<String> onOutput) throws Exception {
        Path versionFile = selected.jsonFile().toAbsolutePath().normalize();
        ResolvedVersion resolved = loadVersion(versionFile, new ArrayList<>());
        ObjectNode version = resolved.metadata();
        String mainClass = version.path("mainClass").asText("");
        if (!mainClass.matches("[A-Za-z_$][A-Za-z0-9_$.]*")) {
            throw new IOException("Minecraft version " + selected.id()
                    + " has an invalid or missing mainClass in its manifest.");
        }
        if (!version.path("libraries").isArray()) {
            throw new IOException("Minecraft version " + selected.id() + " has no libraries list.");
        }
        onOutput.accept("Installing client files for Minecraft " + selected.id() + "...");
        Path clientJar = minecraftDirectory.resolve("versions")
                .resolve(resolved.clientVersionId()).resolve(resolved.clientVersionId() + ".jar");
        JsonNode clientDownload = version.path("downloads").path("client");
        ensureDownload(clientDownload.path("url").asText(""), clientJar,
                clientDownload.path("sha1").asText(""), onOutput, false);
        if (!Files.isRegularFile(clientJar)) {
            throw new IOException("Minecraft client JAR is missing and its manifest has no download URL: "
                    + clientJar);
        }

        Path natives = minecraftDirectory.resolve("versions")
                .resolve(selected.id() + "-natives").toAbsolutePath().normalize();
        Files.createDirectories(natives);
        List<String> classpath = new ArrayList<>();
        for (JsonNode library : version.path("libraries")) {
            if (allowed(library.path("rules"))) {
                addLibrary(minecraftDirectory, library, classpath, natives, onOutput, false);
            }
        }
        classpath.add(clientJar.toString());
        for (String entry : classpath) {
            if (!Files.isRegularFile(Path.of(entry))) {
                throw new IOException("Minecraft installation is missing a required library/client file: " + entry);
            }
        }
        onOutput.accept("All " + classpath.size() + " client and library files are present.");

        String assetIndexId = version.path("assetIndex").path("id")
                .asText(version.path("assets").asText(""));
        if (!assetIndexId.isBlank()) {
            onOutput.accept("Downloading assets for Minecraft " + selected.id() + "...");
            prepareAssets(minecraftDirectory.resolve("assets"), version.path("assetIndex"),
                    assetIndexId, onOutput, false);
        } else {
            onOutput.accept("Minecraft version " + selected.id() + " has no asset index.");
        }

        int javaMajor = requiredJavaMajor(version, resolved.clientVersionId());
        onOutput.accept("Installing/checking required Java " + javaMajor + " runtime...");
        Path java = javaRuntimeResolver.resolve(javaMajor, onOutput, true);
        if (!Files.isRegularFile(java)) {
            throw new IOException("Required Java " + javaMajor + " runtime is missing: " + java);
        }
        onOutput.accept("Minecraft " + selected.id() + " is installed and ready.");
    }

    void launch(Path minecraftDirectory, InstalledVersion selected, int memoryGb,
                PlayerAccount account, Consumer<String> onOutput) throws Exception {
        launch(minecraftDirectory, selected, memoryGb, account, onOutput, false);
    }

    void launchToServer(Path minecraftDirectory, InstalledVersion selected, int memoryGb,
                        PlayerAccount account, String host, int port, Consumer<String> onOutput) throws Exception {
        validateServerAddress(host, port);
        onOutput.accept("Joining multiplayer server " + host + ":" + port + ".");
        launch(minecraftDirectory, selected, memoryGb, account, onOutput, false, host, port);
    }

    void launchToServerLocally(Path minecraftDirectory, InstalledVersion selected, int memoryGb,
                               PlayerAccount account, String host, int port, Consumer<String> onOutput)
            throws Exception {
        validateServerAddress(host, port);
        onOutput.accept("Preparing a local-only launch for multiplayer server " + host + ":" + port
                + "; V-Client will not refresh accounts or download files. Minecraft itself must connect to the server.");
        launch(minecraftDirectory, selected, memoryGb, account, onOutput, true, host, port);
    }

    private void validateServerAddress(String host, int port) throws IOException {
        if (host == null || !host.matches("(?i)[a-z0-9](?:[a-z0-9.-]{0,251}[a-z0-9])?")
                || host.contains("..") || port < 1 || port > 65535) {
            throw new IOException("The multiplayer server address or port is invalid.");
        }
    }

    void launchSingleplayerLocal(Path minecraftDirectory, InstalledVersion selected, int memoryGb,
                                 PlayerAccount account, Consumer<String> onOutput) throws Exception {
        onOutput.accept("Starting Minecraft using only local files; V-Client will not refresh accounts or download files.");
        launch(minecraftDirectory, selected, memoryGb, account, onOutput, true);
    }

    private void launch(Path minecraftDirectory, InstalledVersion selected, int memoryGb,
                        PlayerAccount account, Consumer<String> onOutput, boolean offlineOnly) throws Exception {
        launch(minecraftDirectory, selected, memoryGb, account, onOutput, offlineOnly, "", 0);
    }

    private void launch(Path minecraftDirectory, InstalledVersion selected, int memoryGb,
                        PlayerAccount account, Consumer<String> onOutput, boolean offlineOnly,
                        String serverHost, int serverPort) throws Exception {
        try (LaunchLog launchLog = new LaunchLog(minecraftDirectory, onOutput)) {
            launchInternal(minecraftDirectory, selected, memoryGb, account, launchLog.output(), offlineOnly,
                    serverHost, serverPort);
        }
    }

    private void launchInternal(Path minecraftDirectory, InstalledVersion selected, int memoryGb,
                                PlayerAccount account, Consumer<String> onOutput, boolean offlineOnly,
                                String serverHost, int serverPort) throws Exception {
        onOutput.accept("Launch diagnostics started. Game directory: "
                + minecraftDirectory.toAbsolutePath().normalize());
        if (account == null) throw new IOException("Select a Microsoft, Ely.by, or offline account before playing.");
        if (memoryGb < 1 || memoryGb > LauncherSettings.maxMemory()) {
            throw new IOException("Selected game memory is outside the available range.");
        }
        Path versionsDirectory = minecraftDirectory.resolve("versions").toAbsolutePath().normalize();
        Path selectedJson = selected.jsonFile().toAbsolutePath().normalize();
        if (!selectedJson.startsWith(versionsDirectory) || !Files.isRegularFile(selectedJson)) {
            throw new IOException("The selected Minecraft profile is no longer installed.");
        }

        onOutput.accept("Loading version manifest: " + selectedJson);
        ResolvedVersion resolved;
        try {
            resolved = loadVersion(selectedJson, new ArrayList<>());
        } catch (IOException e) {
            throw new IOException("Could not load or validate Minecraft version manifest "
                    + selectedJson + ": " + e.getMessage(), e);
        }
        ObjectNode version = resolved.metadata();
        String id = version.path("id").asText(selected.id());
        onOutput.accept("Validated version metadata: profile=" + id
                + ", client=" + resolved.clientVersionId()
                + ", type=" + version.path("type").asText("unknown")
                + ", manifest=" + selectedJson);
        if (!PROFILE_ID.matcher(id).matches()) {
            throw new IOException("The selected Minecraft profile contains an invalid version ID.");
        }
        String mainClass = version.path("mainClass").asText("");
        if (mainClass.isBlank() || !mainClass.matches("[A-Za-z_$][A-Za-z0-9_$.]*")) {
            throw new IOException("The selected Minecraft profile has an invalid or missing mainClass entry: "
                    + mainClass + ". Reinstall the profile with its mod loader.");
        }
        if (!version.path("libraries").isArray()) {
            throw new IOException("Minecraft version manifest has no valid libraries array: " + selectedJson);
        }
        Path clientJar = minecraftDirectory.resolve("versions")
                .resolve(resolved.clientVersionId()).resolve(resolved.clientVersionId() + ".jar");
        JsonNode clientDownload = version.path("downloads").path("client");
        onOutput.accept("Checking Minecraft client JAR: " + clientJar);
        ensureDownload(clientDownload.path("url").asText(""), clientJar,
                clientDownload.path("sha1").asText(""), onOutput, offlineOnly);
        if (!Files.isRegularFile(clientJar)) {
            throw new IOException("Minecraft's client JAR is missing from this profile and its metadata has no official download. Reinstall the Minecraft version.");
        }
        onOutput.accept("Minecraft client ready: " + resolved.clientVersionId() + ".");

        int javaMajor = requiredJavaMajor(version, resolved.clientVersionId());
        onOutput.accept("Minecraft requires Java major version " + javaMajor + ".");
        Path javaExecutable = javaRuntimeResolver.resolve(javaMajor, onOutput, !offlineOnly);
        if (!Files.isRegularFile(javaExecutable)) {
            throw new IOException("The selected 64-bit Azul Zulu Java " + javaMajor + " executable is missing: "
                    + javaExecutable);
        }
        onOutput.accept("Java runtime version details: " + JavaRuntimeManager.describeRuntime(javaExecutable));

        Path nativesDirectory = minecraftDirectory.resolve("versions")
                .resolve(selected.id() + "-natives").toAbsolutePath().normalize();
        Files.createDirectories(nativesDirectory);
        List<String> classpath = new ArrayList<>();
        int libraryCount = 0;
        for (JsonNode library : version.path("libraries")) {
            if (!allowed(library.path("rules"))) continue;
            libraryCount++;
            addLibrary(minecraftDirectory, library, classpath, nativesDirectory, onOutput, offlineOnly);
        }
        classpath.add(clientJar.toString());
        onOutput.accept("Library preparation complete: " + libraryCount + " metadata entries, "
                + classpath.size() + " classpath files.");
        for (String entry : classpath) {
            if (!Files.isRegularFile(Path.of(entry))) {
                throw new IOException("Classpath validation failed: required library or client JAR is missing: "
                        + entry);
            }
        }
        onOutput.accept("Classpath validated (" + classpath.size() + " existing files):");
        classpath.forEach(entry -> onOutput.accept("  CLASSPATH " + entry));
        String classpathValue = String.join(System.getProperty("path.separator"), classpath);

        Path assetsDirectory = minecraftDirectory.resolve("assets");
        String assetIndexId = version.path("assetIndex").path("id")
                .asText(version.path("assets").asText(""));
        if (!assetIndexId.isBlank()) {
            onOutput.accept("Preparing Minecraft assets: index=" + assetIndexId
                    + ", directory=" + assetsDirectory.toAbsolutePath().normalize());
            prepareAssets(assetsDirectory, version.path("assetIndex"), assetIndexId, onOutput, offlineOnly);
        } else {
            onOutput.accept("Version metadata has no asset index; skipping asset download.");
        }

        String uuid = account.uuid();
        Map<String, String> substitutions = new HashMap<>();
        substitutions.put("auth_player_name", account.name());
        substitutions.put("version_name", id);
        substitutions.put("game_directory", minecraftDirectory.toAbsolutePath().normalize().toString());
        substitutions.put("assets_root", assetsDirectory.toAbsolutePath().normalize().toString());
        substitutions.put("assets_index_name", assetIndexId.isBlank()
                ? version.path("assets").asText("") : assetIndexId);
        substitutions.put("auth_uuid", uuid.replace("-", ""));
        substitutions.put("auth_access_token", account.accessToken());
        substitutions.put("auth_session", "token:" + account.accessToken()
                + ":" + uuid.replace("-", ""));
        substitutions.put("user_properties", account.gameProperties(JSON));
        substitutions.put("clientid", "00000000402b5328");
        substitutions.put("auth_xuid", account.xuid());
        substitutions.put("user_type", account.type().minecraftUserType());
        substitutions.put("version_type", version.path("type").asText("release"));
        substitutions.put("natives_directory", nativesDirectory.toString());
        substitutions.put("library_directory", minecraftDirectory.resolve("libraries").toAbsolutePath().normalize().toString());
        substitutions.put("classpath_separator", System.getProperty("path.separator"));
        substitutions.put("launcher_name", "V-Client");
        substitutions.put("launcher_version", "1.0.0");
        substitutions.put("classpath", classpathValue);

        try {
            if (BundledResourcePack.supports(resolved.clientVersionId())) {
                BundledResourcePack.ensureInstalledAndEnabled(minecraftDirectory, onOutput);
            } else {
                BundledResourcePack.ensureInstalled(minecraftDirectory, onOutput);
            }
        } catch (IOException e) {
            onOutput.accept("Could not install the bundled resource pack: " + e.getMessage());
        }

        List<String> command = new ArrayList<>();
        onOutput.accept("Java runtime requested: " + javaMajor + "; executable: " + javaExecutable);
        command.add(javaExecutable.toString());
        boolean secureDns = false;
        if (GameDnsAgent.supports(javaMajor)) {
            try {
                command.add("-javaagent:" + GameDnsAgent.ensure());
                secureDns = true;
                onOutput.accept("The game can look up server names over secure DNS when this network's DNS blocks them.");
            } catch (IOException e) {
                onOutput.accept("Could not prepare the game's secure-DNS helper: " + e.getMessage());
            }
        }
        BoostProfile.Tuning tuning = boostTuning.get();
        if (tuning != null) {
            onOutput.accept("VSMP Boost Mode: " + tuning.profile().label() + " - " + tuning.memoryGb()
                    + " GB memory (-Xms" + tuning.initialHeap() + "), " + tuning.jvmFlags().size() + " JVM flags, "
                    + tuning.options().size() + " video settings.");
            try {
                int changed = BoostOptions.apply(minecraftDirectory, tuning.options(), onOutput);
                onOutput.accept("VSMP Boost Mode: " + changed + " video settings changed in options.txt.");
            } catch (IOException e) {
                onOutput.accept("VSMP Boost Mode: could not write options.txt, launching with your own settings: " + e.getMessage());
            }
            command.add("-Xmx" + tuning.memoryGb() + "G");
            command.add("-Xms" + tuning.initialHeap());
            command.addAll(tuning.jvmFlags());
        } else {
            command.add("-Xmx" + memoryGb + "G");
            command.add("-Xms512M");
        }
        if (account.type() == PlayerAccount.Type.ELY_BY) {
            Path authlibInjector = offlineOnly
                    ? findCachedAuthlibInjector(minecraftDirectory, onOutput)
                    : ensureAuthlibInjector(minecraftDirectory, onOutput);
            if (authlibInjector != null) {
                // authlib-injector exits the JVM with code 1 when it cannot fetch the server metadata,
                // so an Ely.by outage would otherwise stop Minecraft from starting at all.
                String outage = elyByAuthServerOutage();
                if (outage == null) {
                    command.add(authlibInjectorArgument(authlibInjector));
                } else {
                    onOutput.accept("Ely.by authentication is unavailable (" + outage + "); launching without authlib-injector. "
                            + "Servers that require Ely.by login will reject you until Ely.by is back.");
                }
            }
        }
        if (cheatPermission.test(account)) command.add("-Dvclient.cheats=true");
        if (adminPermission.test(account)) command.add("-Dvclient.cosmetics.unlockAll=true");
        command.addAll(skinArguments(account, offlineOnly, onOutput));
        String backend = backendUrl.get();
        if (backend != null && !backend.isBlank()) command.add("-Dvclient.backend=" + backend.strip());
        command.addAll(arguments(version.path("arguments").path("jvm"), substitutions));
        if (!version.path("arguments").path("jvm").isArray()) {
            command.add("-Djava.library.path=" + nativesDirectory);
            command.add("-cp");
            command.add(classpathValue);
        }

        JsonNode logging = version.path("logging").path("client");
        if (logging.isObject()) {
            String loggingId = logging.path("file").path("id").asText("");
            String loggingUrl = logging.path("file").path("url").asText("");
            if (!loggingId.isBlank() && !loggingUrl.isBlank()) {
                Path loggingFile = assetsDirectory.resolve("log_configs").resolve(loggingId).normalize();
                if (offlineOnly && !Files.isRegularFile(loggingFile)) {
                    onOutput.accept("Optional logging configuration is not cached; using Minecraft's default logging configuration.");
                } else {
                    ensureDownload(loggingUrl, loggingFile,
                            logging.path("file").path("sha1").asText(""), onOutput, offlineOnly);
                    substitutions.put("path", loggingFile.toString());
                    substitutions.put("path_separator", System.getProperty("path.separator"));
                    String loggingArgument = logging.path("argument").asText("");
                    if (!loggingArgument.isBlank()) command.addAll(legacyArguments(loggingArgument, substitutions));
                }
            }
        }

        command.add(mainClass);
        JsonNode gameArguments = version.path("arguments").path("game");
        if (gameArguments.isArray()) {
            command.addAll(arguments(gameArguments, substitutions));
        } else {
            command.addAll(legacyArguments(version.path("minecraftArguments").asText(""), substitutions));
        }
        String joinHost = serverHost;
        if (!serverHost.isBlank() && !secureDns && !serverHost.matches("[0-9.:]+")) {
            // Without the helper the game cannot get past a DNS filter, so join on the real address instead.
            joinHost = DnsBlock.bypass(serverHost);
            if (!joinHost.equals(serverHost)) {
                onOutput.accept("This network's DNS blocks " + serverHost + "; joining " + joinHost + " instead.");
            }
        }
        if (!serverHost.isBlank()) {
            if (version.toString().contains("quickPlayMultiplayer")) {
                // Minecraft 1.20+ ignores --server/--port ("Completely ignored arguments"); quick play is how it joins.
                Path quickPlayLog = minecraftDirectory.resolve("quickPlay").resolve("java").resolve("multiplayer.json");
                Files.createDirectories(quickPlayLog.getParent());
                command.add("--quickPlayPath");
                command.add(quickPlayLog.toString());
                command.add("--quickPlayMultiplayer");
                command.add(joinHost + ":" + serverPort);
            } else {
                command.add("--server");
                command.add(joinHost);
                command.add("--port");
                command.add(Integer.toString(serverPort));
            }
        }
        if (command.stream().anyMatch(argument -> argument.contains("${"))) {
            throw new IOException("The selected profile uses game arguments this launcher cannot resolve. Update or reinstall its loader.");
        }

        int mainClassIndex = command.indexOf(mainClass);
        if (mainClassIndex < 0 || !mainClass.equals(command.get(mainClassIndex))) {
            throw new IOException("Launch argument validation failed: Minecraft main class is missing.");
        }
        onOutput.accept("JVM arguments (sensitive values redacted):");
        logCommandArguments(command.subList(1, mainClassIndex), onOutput);
        onOutput.accept("Minecraft main class: " + mainClass);
        onOutput.accept("Minecraft arguments (sensitive values redacted):");
        logCommandArguments(command.subList(mainClassIndex + 1, command.size()), onOutput);
        onOutput.accept("Launching " + selected.displayName()
                + " with working directory " + minecraftDirectory.toAbsolutePath().normalize() + "...");
        long launchStartTime = System.currentTimeMillis();
        Process process;
        try {
            process = new ProcessBuilder(command).directory(minecraftDirectory.toFile())
                    .redirectErrorStream(true).start();
        } catch (IOException e) {
            throw new IOException("Could not start Minecraft's Java process. Check the Java runtime required by "
                    + selected.id() + ": " + e.getMessage(), e);
        }
        onOutput.accept("Started Minecraft process (PID " + process.pid() + ").");
        GameSession.started(selected.id());
        onOutput.accept("Capturing Minecraft stdout/stderr and watching for a crash report.");
        Thread outputReader = new Thread(() -> {
            try (var lines = process.inputReader()) {
                String line;
                while ((line = lines.readLine()) != null) onOutput.accept(line);
            } catch (IOException e) {
                onOutput.accept("Could not read game output: " + e.getMessage());
            }
        }, "vclient-game-output");
        outputReader.setDaemon(true);
        outputReader.start();
        int exitCode;
        try {
            exitCode = process.waitFor();
        } finally {
            GameSession.ended();
        }
        outputReader.join();
        onOutput.accept(exitCode == 0 ? "Minecraft closed." : "Minecraft exited with code " + exitCode + ".");
        if (exitCode != 0) {
            String crashReport = latestCrashReport(minecraftDirectory, onOutput, launchStartTime - 2_000);
            throw new IOException("Minecraft exited with code " + exitCode + "."
                    + (crashReport.isBlank() ? " No crash report was found; review the captured game output."
                    : " Crash report details follow:\n" + crashReport));
        }
    }

    /**
     * Hands the account's current skin to the in-game mod. Vanilla only fetches the local player's
     * profile once at start-up and never retries, so one failed request leaves Steve/Alex for the whole
     * session. The mod applies this skin itself whenever vanilla did not resolve one.
     */
    private List<String> skinArguments(PlayerAccount account, boolean offlineOnly, Consumer<String> onOutput) {
        if (account.type() != PlayerAccount.Type.ELY_BY) return List.of();
        String skin = account.skinUrl();
        String cape = account.capeUrl();
        String model = "default";
        if (offlineOnly) {
            onOutput.accept("Ely.by skin: offline launch; using the last saved skin ("
                    + (skin.isBlank() ? "none" : skin) + ").");
        } else {
            onOutput.accept("Ely.by login: signed in as " + account.name() + " (" + account.uuid() + ").");
            try {
                ElyByAuthService.SkinProfile profile = elyByAuthService.fetchSkinProfile(
                        account.uuid(), account.name(), onOutput);
                // The fresh profile is the truth: a cape or skin removed on Ely.by must not linger from a
                // saved copy (a stale cape address would only produce a 404 in the game).
                skin = profile.skinUrl();
                cape = profile.capeUrl();
                model = profile.model();
            } catch (IOException e) {
                onOutput.accept("Ely.by skin: could not refresh the skin, using the last saved one ("
                        + (skin.isBlank() ? "none" : skin) + "): " + e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        List<String> arguments = new ArrayList<>();
        if (!skin.isBlank()) {
            arguments.add("-Dvclient.skin.url=" + skin);
            arguments.add("-Dvclient.skin.model=" + model);
            arguments.add("-Dvclient.skin.uuid=" + account.uuid().replace("-", ""));
            if (!cape.isBlank()) arguments.add("-Dvclient.cape.url=" + cape);
            onOutput.accept("Ely.by skin: passing skin to the game (model=" + model + ").");
        } else {
            onOutput.accept("Ely.by skin: this account has no skin equipped; the game will use the default skin.");
        }
        return arguments;
    }

    static void logCommandArguments(List<String> arguments, Consumer<String> onOutput) {
        boolean redactNext = false;
        for (int index = 0; index < arguments.size(); index++) {
            String argument = arguments.get(index);
            if (redactNext) {
                onOutput.accept("  [REDACTED]");
                redactNext = false;
                continue;
            }
            String lower = argument.toLowerCase(Locale.ROOT);
            if (List.of("--accesstoken", "--clienttoken", "--session", "--userproperties",
                    "--xuid", "--authorization").contains(lower)) {
                onOutput.accept("  " + argument);
                redactNext = true;
            } else if (lower.startsWith("--accesstoken=") || lower.startsWith("--clienttoken=")
                    || lower.startsWith("--session=") || lower.startsWith("--userproperties=")
                    || lower.startsWith("--authorization=")) {
                onOutput.accept("  " + argument.substring(0, argument.indexOf('=') + 1) + "[REDACTED]");
            } else {
                onOutput.accept("  " + argument);
            }
        }
    }

    static String latestCrashReport(Path minecraftDirectory, Consumer<String> onOutput) {
        return latestCrashReport(minecraftDirectory, onOutput, Long.MIN_VALUE);
    }

    private static String latestCrashReport(Path minecraftDirectory, Consumer<String> onOutput,
                                            long notBeforeMillis) {
        Path crashDirectory = minecraftDirectory.resolve("crash-reports");
        if (!Files.isDirectory(crashDirectory)) {
            onOutput.accept("No crash-reports directory exists.");
            return "";
        }
        try (var reports = Files.list(crashDirectory)) {
            Path newest = reports.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().startsWith("crash-")
                            && path.getFileName().toString().endsWith(".txt"))
                    .filter(path -> {
                        try {
                            return Files.getLastModifiedTime(path).toMillis() >= notBeforeMillis;
                        } catch (IOException e) {
                            return false;
                        }
                    })
                    .max((left, right) -> {
                        try {
                            return Files.getLastModifiedTime(left).compareTo(Files.getLastModifiedTime(right));
                        } catch (IOException e) {
                            return left.getFileName().compareTo(right.getFileName());
                        }
                    }).orElse(null);
            if (newest == null) {
                onOutput.accept("No Minecraft crash report was generated.");
                return "";
            }
            String report = readTail(newest, 24_000);
            onOutput.accept("Minecraft crash report: " + newest);
            report.lines().forEach(line -> onOutput.accept("CRASH: " + line));
            return "Crash report: " + newest + System.lineSeparator() + report;
        } catch (IOException e) {
            onOutput.accept("Could not read Minecraft crash reports: " + e.getMessage());
            return "Could not read crash reports in " + crashDirectory + ": " + e.getMessage();
        }
    }

    static String readTail(Path file, int maximumCharacters) throws IOException {
        long size = Files.size(file);
        int bytesToRead = (int) Math.min(size, maximumCharacters);
        try (var input = Files.newInputStream(file)) {
            long bytesToSkip = size - bytesToRead;
            while (bytesToSkip > 0) {
                long skipped = input.skip(bytesToSkip);
                if (skipped == 0) {
                    if (input.read() == -1) break;
                    skipped = 1;
                }
                bytesToSkip -= skipped;
            }
            String contents = new String(input.readNBytes(bytesToRead),
                    java.nio.charset.StandardCharsets.UTF_8);
            if (size <= maximumCharacters) return contents;
            return "[Earlier log content omitted. Showing last " + maximumCharacters + " bytes.]\n"
                    + contents;
        }
    }

    private static final class LaunchLog implements AutoCloseable {
        private final BufferedWriter writer;
        private final Consumer<String> downstream;

        private LaunchLog(Path minecraftDirectory, Consumer<String> downstream) throws IOException {
            Path logFile = minecraftDirectory.resolve("logs").resolve("vclient-launch.log");
            Files.createDirectories(logFile.getParent());
            writer = Files.newBufferedWriter(logFile, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            this.downstream = downstream;
            output().accept("Detailed launch log: " + logFile.toAbsolutePath().normalize());
        }

        private Consumer<String> output() {
            return line -> {
                synchronized (writer) {
                    try {
                        writer.write(line);
                        writer.newLine();
                        writer.flush();
                    } catch (IOException e) {
                        throw new java.io.UncheckedIOException("Could not write the Minecraft launch log.", e);
                    }
                }
                downstream.accept(line);
            };
        }

        @Override
        public void close() throws IOException {
            writer.close();
        }
    }

    private void addLibrary(Path minecraftDirectory, JsonNode library, List<String> classpath,
                            Path nativesDirectory, Consumer<String> onOutput,
                            boolean offlineOnly) throws Exception {
        String libraryName = library.path("name").asText("(unnamed library)");
        onOutput.accept("Checking library: " + libraryName);
        JsonNode downloads = library.path("downloads");
        JsonNode artifact = downloads.path("artifact");
        if (artifact.isObject()) {
            String artifactPath = artifact.path("path").asText("");
            if (artifactPath.isBlank()) {
                artifactPath = mavenArtifactPath(library.path("name").asText(""));
            }
            if (artifactPath.isBlank()) {
                throw new IOException("Minecraft library has no artifact path or usable Maven coordinate: "
                        + library.path("name").asText());
            }
            Path artifactFile = libraryPath(minecraftDirectory, artifactPath);
            onOutput.accept("  Library artifact: " + artifactFile);
            String artifactUrl = artifact.path("url").asText("");
            String artifactSha1 = artifact.path("sha1").asText("");
            if (offlineOnly) {
                ensureDownload(artifactUrl, artifactFile, artifactSha1, onOutput, true);
            } else if (artifactUrl.isBlank() && !isCachedFileValid(artifactFile, artifactSha1)) {
                artifactUrl = resolveMavenUrl(library.path("url").asText(defaultRepository(library)),
                        artifactPath);
                ensureDownload(artifactUrl, artifactFile, artifactSha1, onOutput, false);
            } else {
                ensureDownload(artifactUrl, artifactFile, artifactSha1, onOutput, false);
            }
            if (!Files.isRegularFile(artifactFile)) {
                throw new IOException("The library is missing its artifact file: " + artifactPath);
            }
            classpath.add(artifactFile.toString());
            onOutput.accept("  Library ready: " + artifactPath);
        } else {
            String artifactPath = mavenArtifactPath(library.path("name").asText(""));
            if (artifactPath.isEmpty()) {
                if (downloads.path("classifiers").isObject()) {
                    throw new IOException("The Forge/Fabric library has no supported Windows artifact: "
                            + library.path("name").asText());
                }
            } else {
                Path artifactFile = libraryPath(minecraftDirectory, artifactPath);
                if (!Files.isRegularFile(artifactFile)) {
                    if (offlineOnly) {
                        ensureDownload("", artifactFile, "", onOutput, true);
                    }
                    String baseUrl = library.path("url").asText(defaultRepository(library));
                    ensureDownload(resolveMavenUrl(baseUrl, artifactPath), artifactFile, "", onOutput, false);
                }
                classpath.add(artifactFile.toString());
                onOutput.accept("  Library ready: " + artifactPath);
            }
        }

        JsonNode natives = library.path("natives");
        String classifier = natives.path("windows").asText("").replace("${arch}",
                System.getProperty("os.arch").contains("64") ? "64" : "32");
        if (classifier.isBlank()) return;
        JsonNode nativeArtifact = downloads.path("classifiers").path(classifier);
        if (nativeArtifact.isObject()) {
            String nativePath = nativeArtifact.path("path").asText("");
            if (nativePath.isBlank()) {
                String baseArtifact = mavenArtifactPath(library.path("name").asText(""));
                if (baseArtifact.isBlank()) {
                    throw new IOException("Minecraft native library has no artifact path or usable Maven coordinate: "
                            + library.path("name").asText());
                }
                int extension = baseArtifact.lastIndexOf('.');
                nativePath = baseArtifact.substring(0, extension) + "-" + classifier
                        + baseArtifact.substring(extension);
            }
            Path nativeFile = libraryPath(minecraftDirectory, nativePath);
            ensureDownload(nativeArtifact.path("url").asText(""), nativeFile,
                    nativeArtifact.path("sha1").asText(""), onOutput, offlineOnly);
            extractNatives(nativeFile, nativesDirectory);
            return;
        }
        String baseArtifact = mavenArtifactPath(library.path("name").asText(""));
        if (baseArtifact.isEmpty()) {
            throw new IOException("The loader native library has no Windows download: "
                    + library.path("name").asText());
        }
        int extension = baseArtifact.lastIndexOf('.');
        String nativePath = baseArtifact.substring(0, extension) + "-" + classifier + baseArtifact.substring(extension);
        String baseUrl = library.path("url").asText(defaultRepository(library));
        Path nativeFile = libraryPath(minecraftDirectory, nativePath);
        if (!Files.isRegularFile(nativeFile)) {
            if (offlineOnly) {
                ensureDownload("", nativeFile, "", onOutput, true);
            }
            ensureDownload(resolveMavenUrl(baseUrl, nativePath), nativeFile, "", onOutput, false);
        }
        extractNatives(nativeFile, nativesDirectory);
    }

    private void prepareAssets(Path assetsDirectory, JsonNode descriptor, String assetIndexId,
                               Consumer<String> onOutput, boolean offlineOnly) throws Exception {
        if (!VERSION_ID.matcher(assetIndexId).matches()) {
            throw new IOException("The selected Minecraft profile contains an invalid asset-index ID.");
        }
        Path indexes = assetsDirectory.resolve("indexes");
        Path indexFile = indexes.resolve(assetIndexId + ".json");
        onOutput.accept("Checking asset index file: " + indexFile);
        ensureDownload(descriptor.path("url").asText(""), indexFile,
                descriptor.path("sha1").asText(""), onOutput, offlineOnly);
        if (!Files.isRegularFile(indexFile)) {
            throw new IOException("The Minecraft asset index is missing and its profile contains no official download.");
        }
        JsonNode index = JSON.readTree(indexFile.toFile());
        JsonNode objects = index.path("objects");
        if (!objects.isObject()) throw new IOException("The Minecraft asset index contains no objects map.");
        onOutput.accept("Asset index validated: " + objects.size() + " objects; checking cached asset files.");

        List<CompletableFuture<Void>> pending = new ArrayList<>(MAX_DOWNLOADS);
        java.util.concurrent.atomic.AtomicInteger completedAssets = new java.util.concurrent.atomic.AtomicInteger();
        try (ExecutorService downloads = Executors.newFixedThreadPool(MAX_DOWNLOADS, gameDownloadThreadFactory())) {
            var objectNames = objects.fieldNames();
            while (objectNames.hasNext()) {
                String objectName = objectNames.next();
                Path objectNamePath = Path.of(objectName).normalize();
                if (objectNamePath.isAbsolute() || objectNamePath.startsWith("..")) {
                    throw new IOException("Minecraft asset has an unsafe logical path: " + objectName);
                }
                JsonNode object = objects.path(objectName);
                String hash = object.path("hash").asText("");
                if (!hash.matches("[0-9a-fA-F]{40}")) {
                    throw new IOException("Minecraft asset has an invalid SHA-1 hash: " + objectName);
                }
                Path asset = assetsDirectory.resolve("objects").resolve(hash.substring(0, 2))
                        .resolve(hash).normalize();
                Path objectRoot = assetsDirectory.resolve("objects").toAbsolutePath().normalize();
                if (!asset.toAbsolutePath().normalize().startsWith(objectRoot)) {
                    throw new IOException("Minecraft asset path escaped its object directory.");
                }
                String url = ASSETS_URL + hash.substring(0, 2) + "/" + hash;
                pending.add(CompletableFuture.runAsync(() -> {
                    try {
                        ensureDownload(url, asset, hash, onOutput, offlineOnly);
                        int completed = completedAssets.incrementAndGet();
                        if (completed % 250 == 0 || completed == objects.size()) {
                            onOutput.accept("Asset preparation progress: " + completed + "/" + objects.size());
                        }
                    } catch (Exception e) {
                        throw new java.util.concurrent.CompletionException(e);
                    }
                }, downloads));
                if (pending.size() == MAX_DOWNLOADS) awaitDownloads(pending);
            }
            awaitDownloads(pending);
        } catch (java.util.concurrent.CompletionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception exception) throw exception;
            throw e;
        }

        if (index.path("virtual").asBoolean(false)) {
            Path virtualRoot = assetsDirectory.resolve("virtual").resolve(assetIndexId);
            for (var fields = objects.fields(); fields.hasNext();) {
                var object = fields.next();
                copyAsset(assetsDirectory, object.getKey(), object.getValue(), virtualRoot);
            }
        }
        if (index.path("map_to_resources").asBoolean(false)) {
            Path resources = assetsDirectory.resolve("resources");
            for (var fields = objects.fields(); fields.hasNext();) {
                var object = fields.next();
                copyAsset(assetsDirectory, object.getKey(), object.getValue(), resources);
            }
        }
        onOutput.accept("Minecraft assets ready (" + objects.size() + " objects).");
    }

    private static void copyAsset(Path assetsDirectory, String name, JsonNode object,
                                  Path destination) throws IOException {
        Path relative = Path.of(name).normalize();
        if (relative.isAbsolute() || relative.startsWith("..")) {
            throw new IOException("The Minecraft asset index contains an unsafe path: " + name);
        }
        String hash = object.path("hash").asText("");
        Path source = assetsDirectory.resolve("objects").resolve(hash.substring(0, 2)).resolve(hash);
        Path root = destination.toAbsolutePath().normalize();
        Path target = root.resolve(relative).normalize();
        if (!target.startsWith(root)) throw new IOException("The Minecraft asset path escaped its destination.");
        Files.createDirectories(target.getParent());
        if (!Files.isRegularFile(target) || Files.mismatch(source, target) != -1) {
            Files.copy(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static ThreadFactory gameDownloadThreadFactory() {
        return runnable -> {
            Thread thread = new Thread(runnable, "vclient-asset-download");
            thread.setDaemon(true);
            return thread;
        };
    }

    private static void awaitDownloads(List<CompletableFuture<Void>> pending) throws Exception {
        try {
            CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new)).join();
        } catch (java.util.concurrent.CompletionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception exception) throw exception;
            throw e;
        } finally {
            pending.clear();
        }
    }

    private static ResolvedVersion loadVersion(Path file, List<Path> visited) throws IOException {
        Path normalized = file.toAbsolutePath().normalize();
        if (visited.contains(normalized)) throw new IOException("Circular Minecraft version inheritance: " + normalized);
        visited.add(normalized);
        JsonNode childNode = JSON.readTree(normalized.toFile());
        if (!childNode.isObject()) throw new IOException("Minecraft version metadata is not a JSON object: " + normalized);
        ObjectNode version = (ObjectNode) childNode;
        String directoryId = normalized.getParent().getFileName().toString();
        String versionId = version.path("id").asText(directoryId);
        if (!PROFILE_ID.matcher(directoryId).matches() || !versionId.equals(directoryId)) {
            throw new IOException("Minecraft version metadata ID does not match its version folder: " + normalized);
        }
        String parentId = version.path("inheritsFrom").asText("");
        if (parentId.isBlank()) return new ResolvedVersion(version, versionId);
        if (!PROFILE_ID.matcher(parentId).matches()) {
            throw new IOException("The loader profile contains an invalid inherited Minecraft version ID.");
        }
        Path parentFile = normalized.getParent().getParent().resolve(parentId).resolve(parentId + ".json");
        if (!Files.isRegularFile(parentFile)) {
            throw new IOException("The parent Minecraft version " + parentId + " is missing. Install it with the official Minecraft Launcher.");
        }
        ResolvedVersion parentResolved = loadVersion(parentFile, visited);
        ObjectNode parent = parentResolved.metadata();
        ArrayNode combinedLibraries = JSON.createArrayNode();
        if (parent.path("libraries").isArray()) combinedLibraries.addAll((ArrayNode) parent.path("libraries"));
        if (version.path("libraries").isArray()) combinedLibraries.addAll((ArrayNode) version.path("libraries"));
        if (!combinedLibraries.isEmpty()) version.set("libraries", combinedLibraries);

        ObjectNode combinedArguments = JSON.createObjectNode();
        JsonNode parentArguments = parent.path("arguments");
        JsonNode childArguments = version.path("arguments");
        for (String property : List.of("jvm", "game")) {
            ArrayNode values = JSON.createArrayNode();
            if (parentArguments.path(property).isArray()) values.addAll((ArrayNode) parentArguments.path(property));
            if (childArguments.path(property).isArray()) values.addAll((ArrayNode) childArguments.path(property));
            if (!values.isEmpty()) combinedArguments.set(property, values);
        }
        if (!combinedArguments.isEmpty()) version.set("arguments", combinedArguments);
        for (String property : List.of("mainClass", "assets", "assetIndex", "type", "minecraftArguments",
                "javaVersion", "downloads", "logging", "complianceLevel")) {
            if (!version.has(property) && parent.has(property)) version.set(property, parent.get(property));
        }
        return new ResolvedVersion(version, parentResolved.clientVersionId());
    }

    private static String detectLoader(String id, JsonNode metadata) {
        String lowerId = id.toLowerCase(Locale.ROOT);
        if (lowerId.contains("fabric") || hasLibrary(metadata, "net.fabricmc:fabric-loader")) return "Fabric";
        if (lowerId.contains("neoforge") || hasLibrary(metadata, "net.neoforged:neoforge")) {
            return "NeoForge";
        }
        if (lowerId.contains("quilt") || hasLibrary(metadata, "org.quiltmc:quilt-loader")) return "Quilt";
        if (lowerId.contains("forge") || hasLibrary(metadata, "net.minecraftforge:forge")
                || hasLibrary(metadata, "net.minecraftforge:fml")) return "Forge";
        return "Vanilla";
    }

    private static boolean hasLibrary(JsonNode metadata, String prefix) {
        for (JsonNode library : metadata.path("libraries")) {
            if (library.path("name").asText("").startsWith(prefix)) return true;
        }
        return false;
    }

    private static Path libraryPath(Path minecraftDirectory, String artifactPath) throws IOException {
        if (artifactPath.isBlank()) throw new IOException("The Minecraft library metadata contains an empty artifact path.");
        Path root = minecraftDirectory.resolve("libraries").toAbsolutePath().normalize();
        Path artifact = root.resolve(artifactPath).normalize();
        if (!artifact.startsWith(root)) throw new IOException("A Minecraft library path escaped the libraries directory.");
        return artifact;
    }

    private static String mavenArtifactPath(String coordinate) throws IOException {
        if (coordinate.isBlank()) return "";
        String[] extensionParts = coordinate.split("@", -1);
        if (extensionParts.length > 2 || extensionParts[0].isBlank()) {
            throw new IOException("Invalid Maven library coordinate: " + coordinate);
        }
        String extension = extensionParts.length == 2 ? extensionParts[1] : "jar";
        String[] parts = extensionParts[0].split(":", -1);
        if (parts.length < 3 || parts.length > 4 || extension.isBlank()) {
            throw new IOException("Invalid Maven library coordinate: " + coordinate);
        }
        for (String part : parts) {
            if (!VERSION_ID.matcher(part).matches()) throw new IOException("Invalid Maven library coordinate: " + coordinate);
        }
        String groupPath = parts[0].replace('.', '/');
        String classifier = parts.length == 4 ? "-" + parts[3] : "";
        return groupPath + "/" + parts[1] + "/" + parts[2] + "/" + parts[1] + "-" + parts[2]
                + classifier + "." + extension;
    }

    private static String resolveMavenUrl(String baseUrl, String artifactPath) throws IOException {
        String base = baseUrl.isBlank() ? LIBRARIES_URL : baseUrl;
        URI uri = URI.create(base.endsWith("/") ? base : base + "/");
        if (!"https".equalsIgnoreCase(uri.getScheme())
                || uri.getHost() == null || artifactPath.contains("..")) {
            throw new IOException("A Minecraft library has an invalid or insecure repository URL.");
        }
        return uri.resolve(artifactPath).toString();
    }

    private static String defaultRepository(JsonNode library) {
        String coordinate = library.path("name").asText("");
        if (coordinate.startsWith("net.fabricmc:")) return "https://maven.fabricmc.net/";
        if (coordinate.startsWith("net.minecraftforge:")) return "https://maven.minecraftforge.net/";
        return LIBRARIES_URL;
    }

    private static List<String> arguments(JsonNode values, Map<String, String> substitutions) {
        List<String> result = new ArrayList<>();
        if (!values.isArray()) return result;
        for (JsonNode item : values) {
            JsonNode value = item;
            if (item.isObject()) {
                if (!allowed(item.path("rules"))) continue;
                value = item.path("value");
            }
            if (value.isArray()) {
                for (JsonNode element : value) result.add(substitute(element.asText(), substitutions));
            } else if (value.isTextual()) {
                result.add(substitute(value.asText(), substitutions));
            }
        }
        return result;
    }

    private static List<String> legacyArguments(String value, Map<String, String> substitutions) {
        List<String> result = new ArrayList<>();
        for (String part : value.split("\\s+")) {
            if (!part.isBlank()) result.add(substitute(part, substitutions));
        }
        return result;
    }

    private static String substitute(String value, Map<String, String> substitutions) {
        String result = value;
        for (var entry : substitutions.entrySet()) {
            result = result.replace("${" + entry.getKey() + "}", entry.getValue());
        }
        return result;
    }

    private static boolean allowed(JsonNode rules) {
        if (!rules.isArray() || rules.isEmpty()) return true;
        boolean permitted = false;
        String osName = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        Map<String, Boolean> features = Map.of(
                "is_demo_user", false,
                "has_custom_resolution", false,
                "has_quick_plays_support", false,
                "is_quick_play_singleplayer", false,
                "is_quick_play_multiplayer", false,
                "is_quick_play_realms", false);
        for (JsonNode rule : rules) {
            JsonNode os = rule.path("os");
            String name = os.path("name").asText("");
            if (!name.isBlank() && !(name.equals("windows") && osName.contains("win"))
                    && !(name.equals("osx") && (osName.contains("mac") || osName.contains("darwin")))
                    && !(name.equals("linux") && osName.contains("linux"))) continue;
            String arch = os.path("arch").asText("");
            if (!arch.isBlank() && !Pattern.matches(arch, System.getProperty("os.arch"))) continue;
            String version = os.path("version").asText("");
            if (!version.isBlank() && !Pattern.matches(version, System.getProperty("os.version"))) continue;
            JsonNode conditions = rule.path("features");
            boolean featureMatch = true;
            for (var entries = conditions.fields(); entries.hasNext();) {
                var feature = entries.next();
                if (features.getOrDefault(feature.getKey(), false) != feature.getValue().asBoolean()) {
                    featureMatch = false;
                    break;
                }
            }
            if (!featureMatch) continue;
            permitted = "allow".equals(rule.path("action").asText());
        }
        return permitted;
    }

    private static void ensureDownload(String url, Path file, String sha1,
                                       Consumer<String> onOutput, boolean offlineOnly) throws Exception {
        if (isCachedFileValid(file, sha1)) return;
        if (offlineOnly) {
            throw new IOException("This launch uses local files only. A required Minecraft file is missing or "
                    + "failed its integrity check: " + file + ". Install or repair the selected profile's files "
                    + "before launching.");
        }
        if (url.isBlank()) return;
        URI downloadUri = URI.create(url);
        if (!"https".equalsIgnoreCase(downloadUri.getScheme()) || downloadUri.getHost() == null) {
            throw new IOException("Refusing an insecure or invalid Minecraft download URL: " + url);
        }
        Files.createDirectories(file.getParent());
        HttpRequest request = HttpRequest.newBuilder(downloadUri).timeout(Duration.ofMinutes(3)).GET().build();
        HttpResponse<Path> response;
        try {
            response = PlatformTls.sendWithRetries(HTTP, request, HttpResponse.BodyHandlers.ofFile(file), onOutput);
        } catch (IOException | InterruptedException e) {
            Files.deleteIfExists(file);
            if (e instanceof IOException ioException) {
                throw JavaRuntimeManager.explainTlsFailure(url, ioException);
            }
            throw e;
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            Files.deleteIfExists(file);
            throw new IOException("Could not download Minecraft file (" + response.statusCode() + "): " + url);
        }
        if (!sha1.isBlank() && !sha1.equalsIgnoreCase(sha1(file))) {
            Files.deleteIfExists(file);
            throw new IOException("Downloaded Minecraft file failed its SHA-1 check: " + file.getFileName());
        }
    }

    private static boolean isCachedFileValid(Path file, String sha1) throws Exception {
        return Files.isRegularFile(file) && (sha1.isBlank() || sha1.equalsIgnoreCase(sha1(file)));
    }

    private static String sha1(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-1");
        try (var input = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void extractNatives(Path archive, Path destination) throws IOException {
        Path nativeRoot = destination.toAbsolutePath().normalize();
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(archive))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory() || entry.getName().startsWith("META-INF/")) continue;
                Path target = nativeRoot.resolve(entry.getName()).normalize();
                if (!target.startsWith(nativeRoot)) throw new IOException("Unsafe path in native library archive.");
                Files.createDirectories(target.getParent());
                Files.copy(zip, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private static JsonNode getJson(String endpoint) throws IOException, InterruptedException {
        return getJson(endpoint, ignored -> {});
    }

    private static JsonNode getJson(String endpoint, Consumer<String> onOutput)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json")
                .GET().build();
        HttpResponse<String> response;
        try {
            response = PlatformTls.sendWithRetries(HTTP, request,
                    HttpResponse.BodyHandlers.ofString(java.nio.charset.StandardCharsets.UTF_8), onOutput);
        } catch (IOException e) {
            throw JavaRuntimeManager.explainTlsFailure(endpoint, e);
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Official Minecraft metadata request to " + URI.create(endpoint).getHost()
                    + " failed with HTTP " + response.statusCode()
                    + ". Check whether a proxy or network filter is blocking Mojang.");
        }
        try {
            JsonNode result = JSON.readTree(response.body());
            if (result == null) throw new IOException("Official Minecraft server returned an empty JSON response.");
            return result;
        } catch (IOException e) {
            throw new IOException("Official Minecraft server returned invalid JSON.", e);
        }
    }

    private static void writeJsonAtomically(Path destination, JsonNode metadata) throws IOException {
        Path temporary = Files.createTempFile(destination.getParent(), destination.getFileName().toString(), ".tmp");
        try {
            JSON.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), metadata);
            try {
                Files.move(temporary, destination, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temporary, destination, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private Path ensureAuthlibInjector(Path minecraftDirectory, Consumer<String> onOutput) throws Exception {
        Path libraryDirectory = minecraftDirectory.resolve("libraries").resolve("vclient").resolve("authlib-injector");
        Path metadata = libraryDirectory.resolve("release.json");
        if (!Files.isRegularFile(metadata)) {
            Files.createDirectories(libraryDirectory);
            onOutput.accept("Checking the official authlib-injector release...");
            writeJsonAtomically(metadata, getJson(
                    "https://api.github.com/repos/yushijinhun/authlib-injector/releases/latest", onOutput));
        }
        JsonNode release = JSON.readTree(metadata.toFile());
        String version = release.path("tag_name").asText("").replaceFirst("^[vV]", "");
        if (!version.matches("[0-9][A-Za-z0-9._-]*")) {
            throw new IOException("GitHub's official authlib-injector release has an invalid version.");
        }
        Path destination = libraryDirectory.resolve("authlib-injector-" + version + ".jar");
        if (!Files.isRegularFile(destination)) {
            String assetUrl = "";
            for (JsonNode asset : release.path("assets")) {
                String name = asset.path("name").asText("");
                if (name.matches("authlib-injector-[0-9][A-Za-z0-9._-]*\\.jar")) {
                    assetUrl = asset.path("browser_download_url").asText("");
                    break;
                }
            }
            URI uri;
            try {
                uri = URI.create(assetUrl);
            } catch (IllegalArgumentException e) {
                throw new IOException("The authlib-injector release does not contain a valid JAR download.", e);
            }
            if (!"https".equalsIgnoreCase(uri.getScheme())
                    || !("github.com".equalsIgnoreCase(uri.getHost())
                    || "release-assets.githubusercontent.com".equalsIgnoreCase(uri.getHost()))) {
                throw new IOException("The authlib-injector download is not hosted by GitHub.");
            }
            String name = destination.getFileName().toString();
            if (!release.path("assets").toString().contains(name)) {
                throw new IOException("GitHub's authlib-injector release asset does not match its release version.");
            }
            ensureDownload(assetUrl, destination, "", onOutput, false);
        }
        validateAuthlibInjector(destination);
        return destination.toAbsolutePath().normalize();
    }

    private Path findCachedAuthlibInjector(Path minecraftDirectory, Consumer<String> onOutput)
            throws IOException {
        Path libraryDirectory = minecraftDirectory.resolve("libraries").resolve("vclient").resolve("authlib-injector");
        if (!Files.isDirectory(libraryDirectory)) {
            onOutput.accept("Ely.by authlib-injector is not cached; continuing without it. The SMP may require it for Ely.by authentication.");
            return null;
        }
        try (var paths = Files.list(libraryDirectory)) {
            Path cached = paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString()
                            .matches("authlib-injector-[0-9][A-Za-z0-9._-]*\\.jar"))
                    .sorted(java.util.Comparator.comparing((Path path) -> path.getFileName().toString()).reversed())
                    .findFirst().orElse(null);
            if (cached == null) {
                onOutput.accept("Ely.by authlib-injector is not cached; continuing without it. The SMP may require it for Ely.by authentication.");
                return null;
            }
            validateAuthlibInjector(cached);
            return cached.toAbsolutePath().normalize();
        }
    }

    private void validateAuthlibInjector(Path path) throws IOException {
        try (var jar = new java.util.jar.JarFile(path.toFile())) {
            if (jar.getManifest() == null || jar.getJarEntry("META-INF/MANIFEST.MF") == null) {
                throw new IOException("The cached authlib-injector file is not a Java agent JAR.");
            }
            String agentClass = jar.getManifest().getMainAttributes().getValue("Premain-Class");
            if (agentClass == null || agentClass.isBlank()) {
                throw new IOException("The cached authlib-injector JAR has no Java-agent entry point.");
            }
        }
    }

    /** Returns why Ely.by's authlib-injector metadata cannot be fetched, or null when it is reachable. */
    private static String elyByAuthServerOutage() {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(ELY_BY_AUTH_SERVER))
                    .timeout(Duration.ofSeconds(8)).GET().build();
            int status = HTTP.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            return status == 200 ? null : "HTTP " + status;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "interrupted";
        } catch (IOException e) {
            return e.getClass().getSimpleName();
        }
    }

    static String authlibInjectorArgument(Path authlibInjector) {
        return "-javaagent:" + authlibInjector.toAbsolutePath().normalize() + "=" + ELY_BY_AUTH_SERVER;
    }
}
