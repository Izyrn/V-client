package com.vclient;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The V-Client to VYRN rename must carry every player's accounts, settings and game files over untouched. */
class DataFolderMigrationTest {
    @TempDir
    Path appData;

    @Test
    void movesTheOldVClientFolderToVyrnOnFirstStart() throws IOException {
        Path legacy = appData.resolve("V-Client");
        Files.createDirectories(legacy.resolve("minecraft").resolve("saves").resolve("World"));
        Files.writeString(legacy.resolve("accounts.dat"), "secret");
        Files.writeString(legacy.resolve("settings.json"), "{}");

        Path dataDir = AppInfo.resolveDataDir(appData);

        assertEquals(appData.resolve("VYRN"), dataDir);
        assertFalse(Files.exists(legacy));
        assertEquals("secret", Files.readString(dataDir.resolve("accounts.dat")));
        assertTrue(Files.isDirectory(dataDir.resolve("minecraft").resolve("saves").resolve("World")));
    }

    @Test
    void anExistingVyrnFolderWinsAndTheOldOneIsLeftAlone() throws IOException {
        Files.createDirectories(appData.resolve("VYRN"));
        Files.createDirectories(appData.resolve("V-Client"));
        Files.writeString(appData.resolve("V-Client").resolve("settings.json"), "{}");

        assertEquals(appData.resolve("VYRN"), AppInfo.resolveDataDir(appData));
        assertTrue(Files.exists(appData.resolve("V-Client").resolve("settings.json")));
    }

    @Test
    void aFreshInstallUsesVyrn() {
        assertEquals(appData.resolve("VYRN"), AppInfo.resolveDataDir(appData));
    }

    @Test
    void rebasesOnlyPathsInsideTheOldFolder() {
        Path dataDir = appData.resolve("VYRN");
        assertEquals(dataDir.resolve("minecraft").toAbsolutePath().normalize(),
                AppInfo.rebaseLegacyPath(appData.resolve("V-Client").resolve("minecraft"), appData, dataDir));
        Path elsewhere = appData.resolve("Games").resolve("mc");
        assertEquals(elsewhere, AppInfo.rebaseLegacyPath(elsewhere, appData, dataDir));
        // A folder merely starting with the same letters is not inside it.
        Path lookalike = appData.resolve("V-Client2").resolve("mc");
        assertEquals(lookalike, AppInfo.rebaseLegacyPath(lookalike, appData, dataDir));
    }
}
