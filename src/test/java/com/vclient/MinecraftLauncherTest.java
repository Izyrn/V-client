package com.vclient;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MinecraftLauncherTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String VANILLA_ID = "1.20.4";
    private static final String PLAYER_MAIN_CLASS = LaunchProbe.class.getName();

    @TempDir
    Path minecraftDirectory;

    private MinecraftLauncher launcher;
    private Path versionsDirectory;

    @BeforeEach
    void installVanillaAndLoaderProfiles() throws IOException {
        String executable = System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win")
                ? "java.exe" : "java";
        Path testJava = Path.of(System.getProperty("java.home"), "bin", executable);
        launcher = new MinecraftLauncher((javaMajor, onOutput, allowDownloads) -> testJava);
        versionsDirectory = minecraftDirectory.resolve("versions");
        Path vanillaDirectory = versionsDirectory.resolve(VANILLA_ID);
        Files.createDirectories(vanillaDirectory);
        createGameJar(vanillaDirectory.resolve(VANILLA_ID + ".jar"));

        ObjectNode vanilla = JSON.createObjectNode();
        vanilla.put("id", VANILLA_ID);
        vanilla.put("type", "release");
        vanilla.put("mainClass", PLAYER_MAIN_CLASS);
        vanilla.put("assets", "test");
        vanilla.set("libraries", JSON.createArrayNode());
        ObjectNode assetIndex = vanilla.putObject("assetIndex");
        assetIndex.put("id", "test");
        assetIndex.put("sha1", "");
        ObjectNode arguments = vanilla.putObject("arguments");
        arguments.set("jvm", JSON.createArrayNode().add("-cp").add("${classpath}"));
        arguments.set("game", JSON.createArrayNode()
                .add("--username").add("${auth_player_name}")
                .add("--version").add("${version_name}")
                .add("--gameDir").add("${game_directory}"));
        writeJson(vanillaDirectory.resolve(VANILLA_ID + ".json"), vanilla);

        Path assetsIndex = minecraftDirectory.resolve("assets").resolve("indexes").resolve("test.json");
        Files.createDirectories(assetsIndex.getParent());
        ObjectNode index = JSON.createObjectNode();
        index.set("objects", JSON.createObjectNode());
        writeJson(assetsIndex, index);
        createLoaderProfile("fabric-loader-0.15.11-" + VANILLA_ID,
                "net.fabricmc:fabric-loader:0.15.11");
        createLoaderProfile(VANILLA_ID + "-forge-49.0.31",
                "net.minecraftforge:forge:" + VANILLA_ID + "-49.0.31");
    }

    @Test
    void discoversInstalledVanillaFabricAndForgeProfiles() throws Exception {
        var versions = launcher.findVersions(minecraftDirectory);

        assertEquals(3, versions.size());
        assertEquals("Fabric", versions.stream()
                .filter(version -> version.id().startsWith("fabric-loader-"))
                .findFirst().orElseThrow().loader());
        assertEquals("Forge", versions.stream()
                .filter(version -> version.id().contains("forge"))
                .findFirst().orElseThrow().loader());
        assertTrue(versions.stream().anyMatch(version -> version.id().equals(VANILLA_ID)
                && version.loader().equals("Vanilla")));
    }

    @Test
    void findsNoVersionsInAnEmptyMinecraftDirectory() throws Exception {
        assertTrue(launcher.findVersions(minecraftDirectory.resolve("empty-install")).isEmpty());
    }

    @Test
    void preferredInstalledVersionChooses1211WhenAvailable() {
        var older = new MinecraftLauncher.InstalledVersion("1.20.4", "release",
                versionsDirectory.resolve("1.20.4").resolve("1.20.4.json"), "Vanilla");
        var preferred = new MinecraftLauncher.InstalledVersion("1.21.11", "modified",
                versionsDirectory.resolve("1.21.11").resolve("1.21.11.json"), "Vanilla");
        var newest = new MinecraftLauncher.InstalledVersion("1.22.1", "release",
                versionsDirectory.resolve("1.22.1").resolve("1.22.1.json"), "Vanilla");

        assertEquals(preferred,
                MinecraftLauncher.preferredInstalledVersion(List.of(older, newest, preferred)));
    }

    @Test
    void preferredInstalledVersionChoosesNumericallyNewestStableRelease() {
        var older = new MinecraftLauncher.InstalledVersion("1.9.4", "release",
                versionsDirectory.resolve("1.9.4").resolve("1.9.4.json"), "Vanilla");
        var newer = new MinecraftLauncher.InstalledVersion("1.21.10", "release",
                versionsDirectory.resolve("1.21.10").resolve("1.21.10.json"), "Vanilla");
        var snapshot = new MinecraftLauncher.InstalledVersion("26w01a", "snapshot",
                versionsDirectory.resolve("26w01a").resolve("26w01a.json"), "Vanilla");

        assertEquals(newer, MinecraftLauncher.preferredInstalledVersion(List.of(older, snapshot, newer)));
    }

    @Test
    void minecraft1211RequiresJava25() {
        ObjectNode version = JSON.createObjectNode();
        version.putObject("javaVersion").put("majorVersion", 21);

        assertEquals(25, MinecraftLauncher.requiredJavaMajor(version, "1.21.1"));
        assertEquals(21, MinecraftLauncher.requiredJavaMajor(version, "1.21.4"));
    }

    @Test
    void minecraft1211LaunchRequestsJava25EvenWhenProfileMetadataSays21() throws Exception {
        Path versionDirectory = versionsDirectory.resolve("1.21.1");
        Files.createDirectories(versionDirectory);
        Files.copy(versionsDirectory.resolve(VANILLA_ID).resolve(VANILLA_ID + ".jar"),
                versionDirectory.resolve("1.21.1.jar"));
        ObjectNode version = (ObjectNode) JSON.readTree(
                versionsDirectory.resolve(VANILLA_ID).resolve(VANILLA_ID + ".json").toFile());
        version.put("id", "1.21.1");
        version.putObject("javaVersion").put("majorVersion", 21);
        writeJson(versionDirectory.resolve("1.21.1.json"), version);

        String executable = System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win")
                ? "java.exe" : "java";
        Path testJava = Path.of(System.getProperty("java.home"), "bin", executable);
        MinecraftLauncher java25Launcher = new MinecraftLauncher((javaMajor, onOutput, allowDownloads) -> {
            assertEquals(25, javaMajor);
            return testJava;
        });
        var selected = java25Launcher.findVersions(minecraftDirectory).stream()
                .filter(profile -> profile.id().equals("1.21.1"))
                .findFirst().orElseThrow();
        List<String> output = java.util.Collections.synchronizedList(new ArrayList<>());

        java25Launcher.launchSingleplayer(minecraftDirectory, selected, 2,
                PlayerAccount.offline("Player"), output::add);

        assertGameStarted(output);
    }

    @Test
    void joinSmpLaunchesSelectedProfileWithConfiguredServerAddress() throws Exception {
        var selected = launcher.findVersions(minecraftDirectory).stream()
                .filter(version -> version.id().equals(VANILLA_ID))
                .findFirst().orElseThrow();
        List<String> output = java.util.Collections.synchronizedList(new ArrayList<>());

        launcher.launchToServer(minecraftDirectory, selected, 2, PlayerAccount.offline("Player"),
                "neuron.zeroxhost.space", 19145, output::add);

        assertGameStarted(output);
        assertTrue(output.contains("ARG=--server"));
        assertTrue(output.contains("ARG=neuron.zeroxhost.space"));
        assertTrue(output.contains("ARG=--port"));
        assertTrue(output.contains("ARG=19145"));
    }

    @Test
    void playUsesVClientFilesAndAllowsMissingOfficialGameFilesToBeDownloaded() throws Exception {
        String executable = System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win")
                ? "java.exe" : "java";
        Path testJava = Path.of(System.getProperty("java.home"), "bin", executable);
        MinecraftLauncher downloadEnabledLauncher = new MinecraftLauncher((javaMajor, onOutput, allowDownloads) -> {
            assertTrue(allowDownloads, "Play must allow VYRN to download the required game runtime.");
            return testJava;
        });
        var selected = downloadEnabledLauncher.findVersions(minecraftDirectory).stream()
                .filter(version -> version.id().equals(VANILLA_ID))
                .findFirst().orElseThrow();
        List<String> output = java.util.Collections.synchronizedList(new ArrayList<>());

        downloadEnabledLauncher.launchSingleplayer(minecraftDirectory, selected, 2,
                PlayerAccount.offline("Player"), output::add);

        assertGameStarted(output);
        assertTrue(output.stream().anyMatch(line -> line.contains("VYRN's files")));
        assertTrue(output.stream().anyMatch(line -> line.contains("Java runtime version details:")));
        assertTrue(output.contains("JVM arguments (sensitive values redacted):"));
        assertTrue(output.contains("Minecraft arguments (sensitive values redacted):"));
        assertTrue(output.stream().anyMatch(line -> line.contains("Library preparation complete:")));
        assertTrue(output.stream().anyMatch(line -> line.contains("Asset index validated:")));
        assertTrue(Files.isRegularFile(minecraftDirectory.resolve("logs").resolve("vclient-launch.log")));
    }

    @Test
    void commandArgumentDiagnosticsRedactTokens() {
        List<String> output = new ArrayList<>();

        MinecraftLauncher.logCommandArguments(List.of("--username", "Player", "--accessToken",
                "secret-access-token", "--userProperties={\"token\":\"secret\"}"), output::add);

        assertTrue(output.contains("  [REDACTED]"));
        assertTrue(output.stream().anyMatch(line -> line.contains("--userProperties=[REDACTED]")));
        assertTrue(output.stream().noneMatch(line -> line.contains("secret")));
    }

    @Test
    void crashReportDiagnosticsReturnTheGeneratedReport() throws Exception {
        Path reports = minecraftDirectory.resolve("crash-reports");
        Files.createDirectories(reports);
        Path report = reports.resolve("crash-2026-10-14_13.00.00-client.txt");
        Files.writeString(report, "Unexpected exception: missing game class");
        List<String> output = new ArrayList<>();

        String details = MinecraftLauncher.latestCrashReport(minecraftDirectory, output::add);

        assertTrue(details.contains(report.toString()));
        assertTrue(details.contains("missing game class"));
        assertTrue(output.stream().anyMatch(line -> line.startsWith("CRASH:")));
    }

    @Test
    void playAndJoinUseExistingElyByAccountWithoutRefreshingOrDownloading() throws Exception {
        String executable = System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win")
                ? "java.exe" : "java";
        Path testJava = Path.of(System.getProperty("java.home"), "bin", executable);
        MinecraftLauncher localLauncher = new MinecraftLauncher((javaMajor, onOutput, allowDownloads) -> {
            assertTrue(!allowDownloads, "Local launch must not download a Java runtime.");
            return testJava;
        });
        var selected = localLauncher.findVersions(minecraftDirectory).stream()
                .filter(version -> version.id().equals(VANILLA_ID))
                .findFirst().orElseThrow();
        PlayerAccount elyByAccount = new PlayerAccount("elyby:player", PlayerAccount.Type.ELY_BY,
                "Player", "0123456789abcdef0123456789abcdef", "cached-access-token",
                "cached-refresh-token", "", "", "{}", "", "");
        List<String> singleplayerOutput = java.util.Collections.synchronizedList(new ArrayList<>());

        localLauncher.launchSingleplayerLocal(minecraftDirectory, selected, 2,
                elyByAccount, singleplayerOutput::add);

        assertGameStarted(singleplayerOutput);
        assertTrue(singleplayerOutput.stream().anyMatch(line -> line.contains("will not refresh accounts or download files")));

        List<String> multiplayerOutput = java.util.Collections.synchronizedList(new ArrayList<>());
        localLauncher.launchToServerLocally(minecraftDirectory, selected, 2, elyByAccount,
                "neuron.zeroxhost.space", 19145, multiplayerOutput::add);

        assertGameStarted(multiplayerOutput);
        assertTrue(multiplayerOutput.contains("ARG=--server"));
        assertTrue(multiplayerOutput.contains("ARG=neuron.zeroxhost.space"));
        assertTrue(multiplayerOutput.contains("ARG=--port"));
        assertTrue(multiplayerOutput.contains("ARG=19145"));
        assertTrue(multiplayerOutput.stream().anyMatch(line -> line.contains("Minecraft itself must connect to the server.")));
        assertTrue(multiplayerOutput.stream().anyMatch(line -> line.contains("authlib-injector is not cached")));
    }

    @Test
    void elyByAuthlibInjectorUsesAuthenticationServerApiUrl() {
        Path injector = minecraftDirectory.resolve("libraries")
                .resolve("vclient").resolve("authlib-injector")
                .resolve("authlib-injector-1.2.8.jar");

        assertEquals("-javaagent:" + injector.toAbsolutePath().normalize()
                        + "=https://authserver.ely.by/api/authlib-injector",
                MinecraftLauncher.authlibInjectorArgument(injector));
    }

    @Test
    void joinSmpRejectsInvalidServerAddressBeforeLaunching() throws Exception {
        var selected = launcher.findVersions(minecraftDirectory).stream()
                .filter(version -> version.id().equals(VANILLA_ID))
                .findFirst().orElseThrow();

        assertThrows(IOException.class, () -> launcher.launchToServer(minecraftDirectory, selected, 2,
                PlayerAccount.offline("Player"), "--invalid-host", 19145, ignored -> {}));
    }

    @Test
    void discoversAndLaunchesFabricProfileWithSpacesUsingOnlyLocalFiles() throws Exception {
        String id = "Fabric 1.21.11";
        Path profileDirectory = versionsDirectory.resolve(id);
        Files.createDirectories(profileDirectory);
        Files.copy(versionsDirectory.resolve(VANILLA_ID).resolve(VANILLA_ID + ".jar"),
                profileDirectory.resolve(id + ".jar"));
        ObjectNode profile = (ObjectNode) JSON.readTree(
                versionsDirectory.resolve(VANILLA_ID).resolve(VANILLA_ID + ".json").toFile());
        profile.put("id", id);
        profile.put("type", "modified");
        writeJson(profileDirectory.resolve(id + ".json"), profile);

        var selected = launcher.findVersions(minecraftDirectory).stream()
                .filter(version -> version.id().equals(id))
                .findFirst().orElseThrow();
        List<String> output = java.util.Collections.synchronizedList(new ArrayList<>());

        launcher.launchSingleplayerLocal(minecraftDirectory, selected, 2,
                PlayerAccount.offline("Player"), output::add);

        assertGameStarted(output);
        assertTrue(output.contains("ARG=" + id));
    }

    @Test
    void runSingleplayerUsesCachedLibraryWithLegacyLauncherRepositoryPath() throws Exception {
        String coordinate = "ru.legacylauncher:fabric-loader-l10n:1.0";
        Path libraryFile = libraryPath(coordinate);
        Files.createDirectories(libraryFile.getParent());
        Files.write(libraryFile, new byte[]{1, 2, 3});
        Path profileFile = versionsDirectory.resolve(VANILLA_ID).resolve(VANILLA_ID + ".json");
        ObjectNode metadata = (ObjectNode) JSON.readTree(profileFile.toFile());
        metadata.putArray("libraries").addObject()
                .put("name", coordinate)
                .put("url", "/libraries/");
        writeJson(profileFile, metadata);

        List<String> output = launchSingleplayer(VANILLA_ID);

        assertGameStarted(output);
        assertTrue(output.stream().anyMatch(line -> line.startsWith(
                "Starting Minecraft using only local files; VYRN will not refresh accounts or download files.")));
    }

    @Test
    void runSingleplayerDerivesMissingArtifactPathFromCachedMavenCoordinate() throws Exception {
        String coordinate = "org.example:cached-library:1.2.3";
        Path libraryFile = libraryPath(coordinate);
        Files.createDirectories(libraryFile.getParent());
        byte[] contents = {4, 5, 6};
        Files.write(libraryFile, contents);
        String sha1 = java.util.HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-1").digest(contents));

        Path profileFile = versionsDirectory.resolve(VANILLA_ID).resolve(VANILLA_ID + ".json");
        ObjectNode metadata = (ObjectNode) JSON.readTree(profileFile.toFile());
        ObjectNode artifact = metadata.putArray("libraries").addObject()
                .put("name", coordinate).putObject("downloads").putObject("artifact");
        artifact.put("url", "https://invalid.example/cached-library.jar");
        artifact.put("sha1", sha1);
        writeJson(profileFile, metadata);

        List<String> output = launchSingleplayer(VANILLA_ID);

        assertGameStarted(output);
        assertTrue(output.stream().anyMatch(line -> line.contains(libraryFile.toString())));
    }

    @Test
    void runSingleplayerReportsMissingPathlessArtifactAsMissingLocalFile() throws Exception {
        Path profileFile = versionsDirectory.resolve(VANILLA_ID).resolve(VANILLA_ID + ".json");
        ObjectNode metadata = (ObjectNode) JSON.readTree(profileFile.toFile());
        ObjectNode library = metadata.putArray("libraries").addObject();
        library.put("name", "org.example:uncached-library:1.2.3");
        library.put("url", "/libraries/");
        library.putObject("downloads").putObject("artifact").put("sha1", "invalid");
        writeJson(profileFile, metadata);
        var selected = launcher.findVersions(minecraftDirectory).stream()
                .filter(version -> version.id().equals(VANILLA_ID))
                .findFirst().orElseThrow();

        IOException error = assertThrows(IOException.class, () ->
                launcher.launchSingleplayerLocal(minecraftDirectory, selected, 2,
                        PlayerAccount.offline("Player"), ignored -> {}));

        assertTrue(error.getMessage().contains("local files only"));
        assertTrue(error.getMessage().contains("uncached-library-1.2.3.jar"));
    }

    @Test
    void missingLibraryWithRelativeRepositoryFailsClearlyInsteadOfNullSchemeCrash() throws Exception {
        Path profileFile = versionsDirectory.resolve(VANILLA_ID).resolve(VANILLA_ID + ".json");
        ObjectNode metadata = (ObjectNode) JSON.readTree(profileFile.toFile());
        metadata.putArray("libraries").addObject()
                .put("name", "ru.legacylauncher:fabric-loader-l10n:1.0")
                .put("url", "/libraries/");
        writeJson(profileFile, metadata);
        var selected = launcher.findVersions(minecraftDirectory).stream()
                .filter(version -> version.id().equals(VANILLA_ID))
                .findFirst().orElseThrow();

        IOException error = assertThrows(IOException.class, () ->
                launcher.launch(minecraftDirectory, selected, 2, PlayerAccount.offline("Player"),
                        ignored -> {}));

        assertTrue(error.getMessage().contains("invalid or insecure repository URL"));
        assertTrue(!error.getMessage().contains("equalsIgnoreCase"));
    }

    @Test
    void playStartsTheVanillaMinecraftJavaProcess() throws Exception {
        List<String> output = launch(VANILLA_ID);

        assertGameStarted(output);
        assertTrue(output.contains("ARG=--version"));
        assertTrue(output.contains("ARG=" + VANILLA_ID));
        assertTrue(output.contains("Minecraft assets ready (0 objects)."));
        assertTrue(output.contains("Minecraft closed."));
    }

    @Test
    void runSingleplayerLaunchesWithOnlyCachedFiles() throws Exception {
        List<String> output = launchSingleplayer(VANILLA_ID);

        assertGameStarted(output);
        assertTrue(output.stream().anyMatch(line -> line.startsWith(
                "Starting Minecraft using only local files; VYRN will not refresh accounts or download files.")));
        assertTrue(output.contains("Minecraft assets ready (0 objects)."));
    }

    @Test
    void runSingleplayerSkipsAnUncachedOptionalLoggingConfiguration() throws Exception {
        Path metadataFile = versionsDirectory.resolve(VANILLA_ID).resolve(VANILLA_ID + ".json");
        ObjectNode metadata = (ObjectNode) JSON.readTree(metadataFile.toFile());
        ObjectNode clientLogging = metadata.putObject("logging").putObject("client");
        ObjectNode loggingFile = clientLogging.putObject("file");
        loggingFile.put("id", "uncached-client.xml");
        loggingFile.put("url", "https://example.invalid/uncached-client.xml");
        loggingFile.put("sha1", "");
        clientLogging.put("argument", "-Dlogging.config=${path}");
        writeJson(metadataFile, metadata);

        List<String> output = launchSingleplayer(VANILLA_ID);

        assertGameStarted(output);
        assertTrue(output.stream().anyMatch(line -> line.contains("Optional logging configuration is not cached")));
        assertTrue(Files.notExists(minecraftDirectory.resolve("assets/log_configs/uncached-client.xml")));
    }

    @Test
    void runSingleplayerRejectsMissingFilesWithoutResolvingOrDownloadingAnything() throws Exception {
        Path clientJar = versionsDirectory.resolve(VANILLA_ID).resolve(VANILLA_ID + ".jar");
        Files.delete(clientJar);
        MinecraftLauncher localOnlyLauncher = new MinecraftLauncher((javaMajor, onOutput, allowDownloads) -> {
            throw new AssertionError("Runtime resolution must not be reached when the client JAR is missing.");
        });
        var selected = localOnlyLauncher.findVersions(minecraftDirectory).stream()
                .filter(version -> version.id().equals(VANILLA_ID))
                .findFirst().orElseThrow();

        IOException error = assertThrows(IOException.class, () ->
                localOnlyLauncher.launchSingleplayerLocal(minecraftDirectory, selected, 2,
                        PlayerAccount.offline("Player"), ignored -> {}));

        assertTrue(error.getMessage().contains("local files only"));
        assertTrue(error.getMessage().contains("1.20.4.jar"));
        assertTrue(Files.notExists(clientJar));
    }

    @Test
    void preparesRealIndexedAssetsAndLegacyVirtualResourceFiles() throws Exception {
        byte[] content = "minecraft test asset".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String hash = java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(content));
        Path object = minecraftDirectory.resolve("assets").resolve("objects")
                .resolve(hash.substring(0, 2)).resolve(hash);
        Files.createDirectories(object.getParent());
        Files.write(object, content);

        ObjectNode index = JSON.createObjectNode();
        index.put("virtual", true);
        index.put("map_to_resources", true);
        ObjectNode objects = index.putObject("objects");
        objects.putObject("lang/en_us.json").put("hash", hash).put("size", content.length);
        writeJson(minecraftDirectory.resolve("assets").resolve("indexes").resolve("test.json"), index);

        List<String> output = launch(VANILLA_ID);

        assertGameStarted(output);
        assertTrue(output.contains("Minecraft assets ready (1 objects)."));
        assertTrue(Files.mismatch(object,
                minecraftDirectory.resolve("assets/virtual/test/lang/en_us.json")) == -1);
        assertTrue(Files.mismatch(object,
                minecraftDirectory.resolve("assets/resources/lang/en_us.json")) == -1);
    }

    @Test
    void playStartsFabricUsingTheInheritedMinecraftVersion() throws Exception {
        String id = "fabric-loader-0.15.11-" + VANILLA_ID;
        List<String> output = launch(id);

        assertGameStarted(output);
        assertTrue(output.contains("ARG=" + id));
        assertTrue(output.stream().anyMatch(line -> line.startsWith("Started Minecraft process")));
        assertTrue(output.stream().anyMatch(line -> line.contains(libraryPath(
                "net.fabricmc:fabric-loader:0.15.11").toString())));
    }

    @Test
    void playStartsForgeUsingItsMavenLibraries() throws Exception {
        String id = VANILLA_ID + "-forge-49.0.31";
        List<String> output = launch(id);

        assertGameStarted(output);
        assertTrue(output.contains("ARG=" + id));
        assertTrue(output.stream().anyMatch(line -> line.startsWith("Started Minecraft process")));
        assertTrue(output.stream().anyMatch(line -> line.contains(libraryPath(
                "net.minecraftforge:forge:" + VANILLA_ID + "-49.0.31").toString())));
    }

    @Test
    void substitutesTheSelectedOfflineAccountIntoAuthenticatedLaunchArguments() throws Exception {
        Path profile = versionsDirectory.resolve(VANILLA_ID);
        Path json = profile.resolve(VANILLA_ID + ".json");
        ObjectNode metadata = (ObjectNode) JSON.readTree(json.toFile());
        ((ArrayNode) metadata.path("arguments").path("game")).add("${auth_access_token}");
        ((ArrayNode) metadata.path("arguments").path("game")).add("${auth_uuid}");
        ((ArrayNode) metadata.path("arguments").path("game")).add("${user_type}");
        writeJson(json, metadata);
        var selected = launcher.findVersions(minecraftDirectory).stream()
                .filter(version -> version.id().equals(VANILLA_ID))
                .findFirst().orElseThrow();
        PlayerAccount offline = PlayerAccount.offline("Player");
        List<String> output = java.util.Collections.synchronizedList(new ArrayList<>());

        launcher.launch(minecraftDirectory, selected, 2, offline, output::add);

        assertGameStarted(output);
        assertTrue(output.contains("ARG=0"));
        assertTrue(output.contains("ARG=" + offline.uuid()));
        assertTrue(output.contains("ARG=legacy"));
    }

    private List<String> launch(String id) throws Exception {
        var selected = launcher.findVersions(minecraftDirectory).stream()
                .filter(version -> version.id().equals(id))
                .findFirst().orElseThrow();
        List<String> output = java.util.Collections.synchronizedList(new ArrayList<>());

        launcher.launch(minecraftDirectory, selected, 2, PlayerAccount.offline("Player"), output::add);

        return List.copyOf(output);
    }

    private List<String> launchSingleplayer(String id) throws Exception {
        var selected = launcher.findVersions(minecraftDirectory).stream()
                .filter(version -> version.id().equals(id))
                .findFirst().orElseThrow();
        List<String> output = java.util.Collections.synchronizedList(new ArrayList<>());

        launcher.launchSingleplayerLocal(minecraftDirectory, selected, 2,
                PlayerAccount.offline("Player"), output::add);

        return List.copyOf(output);
    }

    private void createLoaderProfile(String id, String libraryCoordinate) throws IOException {
        Path profileDirectory = versionsDirectory.resolve(id);
        Files.createDirectories(profileDirectory);
        Path library = libraryPath(libraryCoordinate);
        Files.createDirectories(library.getParent());
        if (!Files.exists(library)) {
            try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(library))) {
                jar.putNextEntry(new JarEntry("META-INF/MANIFEST.MF"));
                jar.write("Manifest-Version: 1.0\r\n\r\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                jar.closeEntry();
            }
        }

        ObjectNode profile = JSON.createObjectNode();
        profile.put("id", id);
        profile.put("inheritsFrom", VANILLA_ID);
        profile.put("type", "release");
        profile.putArray("libraries").addObject().put("name", libraryCoordinate);
        writeJson(profileDirectory.resolve(id + ".json"), profile);
    }

    private Path libraryPath(String coordinate) {
        String[] parts = coordinate.split(":");
        return minecraftDirectory.resolve("libraries")
                .resolve(parts[0].replace('.', '/')).resolve(parts[1]).resolve(parts[2])
                .resolve(parts[1] + "-" + parts[2] + ".jar");
    }

    private void createGameJar(Path destination) throws IOException {
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(destination))) {
            String className = PLAYER_MAIN_CLASS.replace('.', '/') + ".class";
            jar.putNextEntry(new JarEntry(className));
            try (InputStream classBytes = LaunchProbe.class.getResourceAsStream("LaunchProbe.class")) {
                if (classBytes == null) throw new IOException("Missing test game entry point class.");
                classBytes.transferTo(jar);
            }
            jar.closeEntry();
        }
    }

    private void writeJson(Path destination, ObjectNode value) throws IOException {
        Files.createDirectories(destination.getParent());
        JSON.writerWithDefaultPrettyPrinter().writeValue(destination.toFile(), value);
    }

    private void assertGameStarted(List<String> output) {
        assertTrue(output.contains("MINECRAFT_MAIN_STARTED"), () -> String.join("\n", output));
        assertTrue(output.contains("ARG=--username"));
        assertTrue(output.contains("ARG=Player"));
        assertTrue(output.contains("Minecraft closed."));
    }
}
