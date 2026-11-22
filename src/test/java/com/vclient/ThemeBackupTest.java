package com.vclient;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThemeBackupTest {
    @TempDir
    Path temp;

    @AfterEach
    void resetTheme() {
        Theme.apply("frost", "#00cfff");
    }

    @Test
    void presetsAndCustomAccentChangeTheColours() {
        Theme.apply("aurora", "#000000");
        assertEquals("#5cffc8", Theme.accent());
        assertEquals("#5cffc8", ModsUi.ACCENT);
        Theme.apply(Theme.CUSTOM, "#33CC99");
        assertEquals("#33cc99", Theme.accent());
        assertTrue(Theme.validHex(Theme.background()));
        assertEquals("rgba(51, 204, 153, 0.5)", Theme.accentRgba(0.5));
        Theme.apply(Theme.CUSTOM, "not a colour");
        assertEquals("#00cfff", Theme.accent(), "an invalid custom colour falls back to the default theme");
        assertEquals(5, Theme.PRESETS.size());
    }

    @Test
    void themeIsSavedInSettings() {
        LauncherSettings settings = LauncherSettings.defaults(temp);
        settings.setTheme("purple", "#123456");
        assertEquals("purple", settings.themeId());
        assertEquals("#123456", settings.customAccent());
        settings.setTheme("blue", "garbage");
        assertEquals("#123456", settings.customAccent());
    }

    @Test
    void badgesFollowFirstRunDateAndAdminAccount() {
        PlayerStats stats = new PlayerStats(temp.resolve("stats.json"));
        LauncherSettings settings = LauncherSettings.defaults(temp);
        stats.markSeen(Badges.EARLY_USER_CUTOFF.minusDays(1));
        stats.markSeen(LocalDate.of(2030, 1, 1));
        assertTrue(Badges.earned(Badges.ALL.get(0), settings, null, stats));
        assertFalse(Badges.earned(Badges.ALL.get(1), settings, null, stats), "OG User is only awarded by the team");
        assertFalse(Badges.earned(Badges.ALL.get(4), settings, null, stats));
        PlayerStats late = new PlayerStats(temp.resolve("late.json"));
        late.markSeen(Badges.EARLY_USER_CUTOFF);
        assertFalse(Badges.earned(Badges.ALL.get(0), settings, null, late));
    }

    @Test
    void activityLogKeepsNewestFirstAndPersists() {
        Path file = temp.resolve("activity.json");
        ActivityLog log = new ActivityLog(file);
        log.add("one");
        log.add("two");
        assertEquals("two", new ActivityLog(file).recent(5).getFirst().text());
        assertEquals(2, new ActivityLog(file).recent(5).size());
    }

    @Test
    void backupRoundTripKeepsLocalSettingsAndSkipsTokens() throws IOException {
        Path game = Files.createDirectories(temp.resolve("game"));
        Path launcher = Files.createDirectories(temp.resolve("launcher"));
        Files.createDirectories(game.resolve("mods"));
        Files.writeString(game.resolve("mods").resolve("sodium.jar"), "jar");
        Files.writeString(game.resolve("options.txt"), "renderDistance:12");
        Files.createDirectories(game.resolve("config"));
        Files.writeString(game.resolve("config").resolve("vclient-cosmetics.json"), "{\"equipped\":{}}");
        Files.writeString(game.resolve("config").resolve("other-mod.toml"), "x");
        Files.createDirectories(game.resolve("versions").resolve("VYRN 1.21.11"));
        Files.writeString(game.resolve("versions").resolve("VYRN 1.21.11").resolve("VYRN 1.21.11.json"), "{}");
        Files.writeString(game.resolve("versions").resolve("VYRN 1.21.11").resolve("big.jar"), "jar");
        Files.writeString(launcher.resolve("settings.json"),
                "{\"memoryGb\":6,\"themeId\":\"red\",\"minecraftDirectory\":\"D:/old\",\"backendTokens\":{\"a\":\"SECRET\"},\"installId\":\"old\"}");

        Path zip = BackupService.create(temp.resolve("backups"), game, launcher, EnumSet.allOf(BackupService.Category.class));
        assertEquals(1, BackupService.list(temp.resolve("backups")).size());
        assertEquals(EnumSet.allOf(BackupService.Category.class), BackupService.contents(zip));
        assertFalse(new String(Files.readAllBytes(zip), java.nio.charset.StandardCharsets.ISO_8859_1).contains("SECRET"));

        // Restore onto a "new PC" that has its own folder, sign-in and install id.
        Path game2 = Files.createDirectories(temp.resolve("game2"));
        Path launcher2 = Files.createDirectories(temp.resolve("launcher2"));
        Files.writeString(launcher2.resolve("settings.json"),
                "{\"memoryGb\":2,\"minecraftDirectory\":\"E:/new\",\"backendTokens\":{\"b\":\"MINE\"},\"installId\":\"new\"}");
        BackupService.Result result = BackupService.restore(zip, game2, launcher2, EnumSet.allOf(BackupService.Category.class));
        assertTrue(result.files() >= 5);
        assertEquals("jar", Files.readString(game2.resolve("mods").resolve("sodium.jar")));
        assertEquals("renderDistance:12", Files.readString(game2.resolve("options.txt")));
        assertTrue(Files.exists(game2.resolve("config").resolve("vclient-cosmetics.json")));
        assertFalse(Files.exists(game2.resolve("config").resolve("other-mod.toml")));
        assertTrue(Files.exists(game2.resolve("versions").resolve("VYRN 1.21.11").resolve("VYRN 1.21.11.json")));
        assertFalse(Files.exists(game2.resolve("versions").resolve("VYRN 1.21.11").resolve("big.jar")));
        String settings = Files.readString(launcher2.resolve("settings.json"));
        assertTrue(settings.contains("\"memoryGb\" : 6") && settings.contains("red"));
        assertTrue(settings.contains("E:/new") && settings.contains("MINE") && settings.contains("\"new\""));
        assertFalse(settings.contains("SECRET"));
    }

    @Test
    void restoreOnlyTouchesTheTickedCategoriesAndRejectsEscapingPaths() throws IOException {
        Path game = Files.createDirectories(temp.resolve("game"));
        Path launcher = Files.createDirectories(temp.resolve("launcher"));
        Path evil = temp.resolve("evil.zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(evil))) {
            for (String name : new String[]{"mods/../../escaped.txt", "mods/ok.jar", "settings/options.txt"}) {
                zip.putNextEntry(new ZipEntry(name));
                zip.write("x".getBytes());
                zip.closeEntry();
            }
        }
        assertNull(BackupService.destinationOf("mods/../../escaped.txt", game, launcher));
        BackupService.Result result = BackupService.restore(evil, game, launcher, Set.of(BackupService.Category.MODS));
        assertEquals(1, result.files());
        assertTrue(Files.exists(game.resolve("mods").resolve("ok.jar")));
        assertFalse(Files.exists(game.resolve("options.txt")));
        assertFalse(Files.exists(temp.resolve("escaped.txt")));
    }
}
