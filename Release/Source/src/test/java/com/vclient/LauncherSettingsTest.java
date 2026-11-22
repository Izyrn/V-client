package com.vclient;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class LauncherSettingsTest {
    @TempDir
    Path temporaryDirectory;

    private static PlayerAccount account(PlayerAccount.Type type, String name, String uuid) {
        return new PlayerAccount(type.name().toLowerCase() + ":" + uuid, type, name, uuid, "token", "", "client", "", "[]", "", "");
    }

    @Test
    void onlyTheIzyrnElyByAccountIsAdmin() {
        LauncherSettings settings = LauncherSettings.defaults(temporaryDirectory);
        String izyrn = LauncherSettings.ADMIN_ELY_UUID;

        assertTrue(settings.isAdmin(account(PlayerAccount.Type.ELY_BY, "Izyrn", izyrn)));
        // The same account after a rename, and the UUID with dashes, still count: the UUID is what matters.
        assertTrue(settings.isAdmin(account(PlayerAccount.Type.ELY_BY, "NewName",
                izyrn.replaceFirst("(.{8})(.{4})(.{4})(.{4})(.{12})", "$1-$2-$3-$4-$5"))));
        // Someone who merely has the name Izyrn on another account, or any other type of account, does not.
        assertFalse(settings.isAdmin(account(PlayerAccount.Type.ELY_BY, "Izyrn", "0123456789abcdef0123456789abcdef")));
        assertFalse(settings.isAdmin(account(PlayerAccount.Type.OFFLINE, "Izyrn", izyrn)));
        assertFalse(settings.isAdmin(account(PlayerAccount.Type.MICROSOFT, "Izyrn", izyrn)));
        assertFalse(settings.isAdmin(null));
    }

    @Test
    void correctsSelectedVersionSubfolderToMinecraftGameRoot() throws IOException {
        Path gameDirectory = temporaryDirectory.resolve("Minecraft").resolve("game");
        Path profileDirectory = gameDirectory.resolve("versions").resolve("Fabric 1.21.11");
        Files.createDirectories(profileDirectory);
        Files.createFile(profileDirectory.resolve("Fabric 1.21.11.json"));

        assertEquals(gameDirectory, LauncherSettings.normalizeMinecraftDirectory(profileDirectory));
    }

    @Test
    void leavesGameRootAndUnrecognizedFoldersUnchanged() throws IOException {
        Path gameDirectory = temporaryDirectory.resolve("Minecraft").resolve("game");
        Path versionDirectory = gameDirectory.resolve("versions");
        Files.createDirectories(versionDirectory);
        Path nonProfile = versionDirectory.resolve("Fabric 1.21.11");
        Files.createDirectories(nonProfile);

        assertEquals(gameDirectory, LauncherSettings.normalizeMinecraftDirectory(gameDirectory));
        assertEquals(nonProfile, LauncherSettings.normalizeMinecraftDirectory(nonProfile));
    }

    @Test
    void privateMinecraftDirectoryLivesUnderTheDataFolder() {
        Path dataDir = temporaryDirectory.resolve("AppData").resolve("Roaming").resolve("VYRN");
        assertEquals(dataDir.resolve("minecraft"), LauncherSettings.privateMinecraftDirectory(dataDir));
    }

    @Test
    void migratesOldLauncherAndDefaultMinecraftDirectoriesToPrivateFolder() {
        Path appData = temporaryDirectory.resolve("AppData").resolve("Roaming");
        Path dataDir = appData.resolve("VYRN");
        Path privateDirectory = LauncherSettings.privateMinecraftDirectory(dataDir);

        assertEquals(privateDirectory,
                LauncherSettings.migrateLegacyDirectory(appData.resolve("V-Client"), appData, dataDir));
        assertEquals(privateDirectory, LauncherSettings.migrateLegacyDirectory(dataDir, appData, dataDir));
        assertEquals(privateDirectory, LauncherSettings.migrateLegacyDirectory(appData.resolve(".minecraft"), appData, dataDir));
    }

    @Test
    void savedGameFolderInsideTheOldVClientFolderFollowsTheRename() {
        Path appData = temporaryDirectory.resolve("AppData").resolve("Roaming");
        Path dataDir = appData.resolve("VYRN");
        assertEquals(dataDir.resolve("minecraft").toAbsolutePath().normalize(), LauncherSettings.migrateLegacyDirectory(
                appData.resolve("V-Client").resolve("minecraft"), appData, dataDir));
        assertEquals(dataDir.resolve("instances").resolve("pvp").toAbsolutePath().normalize(),
                LauncherSettings.migrateLegacyDirectory(appData.resolve("V-Client").resolve("instances").resolve("pvp"),
                        appData, dataDir));
    }

    @Test
    void keepsTheOldFolderWhenTheMoveFailedAndItIsStillInUse() throws IOException {
        Path appData = temporaryDirectory.resolve("AppData").resolve("Roaming");
        Path legacy = appData.resolve("V-Client");
        Files.createDirectories(legacy.resolve("minecraft"));
        // The move failed, so the old folder is the data folder: the saved path must not change.
        assertEquals(legacy.resolve("minecraft"),
                LauncherSettings.migrateLegacyDirectory(legacy.resolve("minecraft"), appData, legacy));
    }

    @Test
    void keepsAnExplicitCustomMinecraftDirectory() throws IOException {
        Path appData = temporaryDirectory.resolve("AppData").resolve("Roaming");
        Path customDirectory = temporaryDirectory.resolve("CustomMinecraft");
        Path profileDirectory = customDirectory.resolve("versions").resolve("1.21.11");
        Files.createDirectories(profileDirectory);
        Files.createFile(profileDirectory.resolve("1.21.11.json"));

        assertEquals(customDirectory, LauncherSettings.migrateLegacyDirectory(customDirectory, appData, appData.resolve("VYRN")));
    }

    @Test
    void selectingVersionSubfolderNormalizesBeforeApplyingFolderMigration() throws IOException {
        Path appData = temporaryDirectory.resolve("AppData").resolve("Roaming");
        Path dataDir = appData.resolve("VYRN");
        Path versionDirectory = dataDir.resolve("versions").resolve("1.21.11");
        Files.createDirectories(versionDirectory);
        Files.createFile(versionDirectory.resolve("1.21.11.json"));

        Path normalized = LauncherSettings.normalizeMinecraftDirectory(versionDirectory);
        assertEquals(LauncherSettings.privateMinecraftDirectory(dataDir),
                LauncherSettings.migrateLegacyDirectory(normalized, appData, dataDir));
    }

    @Test
    void v_client_sign_in_belongs_to_one_game_account() {
        LauncherSettings settings = LauncherSettings.defaults(java.nio.file.Path.of("minecraft"));
        settings.setLastAccountId("ely:izyrn");
        settings.setBackendToken("admin-token");
        assertEquals("admin-token", settings.backendToken());

        settings.setLastAccountId("offline:Nyrox");
        assertEquals("", settings.backendToken(), "another game account must not inherit the sign-in");
        settings.setBackendToken("nyrox-token");

        settings.setLastAccountId("ely:izyrn");
        assertEquals("admin-token", settings.backendToken());
        settings.setBackendToken("");
        assertEquals("", settings.backendToken());
        settings.setLastAccountId("offline:Nyrox");
        assertEquals("nyrox-token", settings.backendToken());
    }
}
