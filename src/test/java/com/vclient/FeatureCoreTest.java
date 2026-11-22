package com.vclient;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FeatureCoreTest {
    @TempDir
    Path temp;

    // ---- stats, achievements, daily rewards ------------------------------------------------------

    @Test
    void streakContinuesOnConsecutiveDaysAndPaysDayOneSevenAndThirty() {
        PlayerStats stats = new PlayerStats(temp.resolve("stats.json"));
        LocalDate day = LocalDate.of(2026, 1, 1);
        assertEquals(PlayerStats.Reward.COSMETIC, stats.claim(day));
        assertNull(stats.claim(day), "a second claim on the same day pays nothing");
        for (int n = 2; n <= 6; n++) assertEquals(PlayerStats.Reward.NONE, stats.claim(day.plusDays(n - 1)));
        assertEquals(PlayerStats.Reward.EXCLUSIVE, stats.claim(day.plusDays(6)));
        for (int n = 8; n <= 29; n++) stats.claim(day.plusDays(n - 1));
        assertEquals(PlayerStats.Reward.LEGEND, stats.claim(day.plusDays(29)));
        assertEquals(Set.of(1, 7, 30), stats.earnedRewardDays());
    }

    @Test
    void missingADayRestartsTheStreak() {
        PlayerStats stats = new PlayerStats(temp.resolve("stats.json"));
        LocalDate day = LocalDate.of(2026, 3, 1);
        stats.claim(day);
        stats.claim(day.plusDays(1));
        stats.claim(day.plusDays(3));
        assertEquals(1, stats.streak());
    }

    @Test
    void playtimeAchievementsAndFavoriteVersionPersist() {
        Path file = temp.resolve("stats.json");
        PlayerStats stats = new PlayerStats(file);
        stats.recordLaunch("1.21.11");
        stats.recordLaunch("1.21.11");
        stats.recordLaunch("1.8.9");
        stats.addPlaytime(PlayerStats.HUNDRED_HOURS_SECONDS - 1);
        assertFalse(stats.unlocked(PlayerStats.HUNDRED_HOURS));
        stats.addPlaytime(1);
        stats.unlock(PlayerStats.FPS_BOOST);
        stats.save();

        PlayerStats loaded = PlayerStats.load(file);
        assertEquals("1.21.11", loaded.favoriteVersion());
        assertEquals(3, loaded.totalLaunches());
        assertTrue(loaded.unlocked(PlayerStats.FIRST_LAUNCH));
        assertTrue(loaded.unlocked(PlayerStats.HUNDRED_HOURS));
        assertTrue(loaded.unlocked(PlayerStats.FPS_BOOST));
        assertFalse(loaded.unlocked(PlayerStats.FIRST_MOD));
    }

    // ---- MJPEG AVI and recordings ----------------------------------------------------------------

    @Test
    void aviRoundTripKeepsFramesSizeAndDuration() throws IOException {
        List<byte[]> frames = List.of(new byte[]{1, 2, 3}, new byte[]{4, 5, 6, 7}, new byte[]{8});
        Path file = temp.resolve("clip.avi");
        MjpegAvi.write(file, frames, 320, 180, 15);
        MjpegAvi.Info info = MjpegAvi.read(file);
        assertNotNull(info);
        assertEquals(3, info.frameCount());
        assertEquals(320, info.width());
        assertEquals(180, info.height());
        assertEquals(15, Math.round(info.framesPerSecond()));
        assertEquals(200, info.durationMillis());
        assertEquals(List.of((byte) 4, (byte) 5, (byte) 6, (byte) 7), toList(MjpegAvi.frame(file, info, 1)));
        assertEquals(200, RecordingLibrary.durationMillis(file));
    }

    @Test
    void mp4DurationComesFromTheMovieHeader() throws IOException {
        ByteArrayOutputStream mvhd = new ByteArrayOutputStream();
        ByteBuffer body = ByteBuffer.allocate(100);
        body.putInt(0); // version + flags
        body.putInt(0).putInt(0); // created, modified
        body.putInt(1000); // timescale
        body.putInt(90_000); // duration
        mvhd.write(box("mvhd", body.array()));
        ByteArrayOutputStream file = new ByteArrayOutputStream();
        file.write(box("ftyp", new byte[]{'i', 's', 'o', 'm', 0, 0, 0, 0}));
        file.write(box("moov", mvhd.toByteArray()));
        Path mp4 = temp.resolve("game.mp4");
        Files.write(mp4, file.toByteArray());
        assertEquals(90_000, RecordingLibrary.durationMillis(mp4));
    }

    @Test
    void importWaitsForStableFilesCopiesOnceAndSupportsFavoritesAndDelete() throws IOException {
        Path source = Files.createDirectories(temp.resolve("Videos"));
        RecordingLibrary library = new RecordingLibrary(temp.resolve("library"));
        Path video = source.resolve("obs.mp4");
        Files.write(video, new byte[]{1, 2, 3});
        Map<Path, Long> sizes = new HashMap<>();
        long since = System.currentTimeMillis() - 60_000;

        assertTrue(library.findImportable(List.of(source), since, sizes).isEmpty(), "first sighting is not stable yet");
        List<Path> ready = library.findImportable(List.of(source), since, sizes);
        assertEquals(List.of(video), ready);

        Path imported = library.importFile(video);
        assertTrue(Files.exists(video), "the recorder's own file is left alone");
        assertTrue(Files.exists(imported));
        assertTrue(library.findImportable(List.of(source), since, sizes).isEmpty(), "never imported twice");
        assertTrue(library.findImportable(List.of(source), since, sizes).isEmpty());

        assertEquals(1, library.list().size());
        library.setFavorite(imported, true);
        assertTrue(new RecordingLibrary(temp.resolve("library")).list().getFirst().favorite());
        assertTrue(library.delete(imported));
        assertTrue(library.list().isEmpty());
        assertFalse(library.delete(video), "files outside the library are never deleted");
        assertTrue(Files.exists(video));
    }

    @Test
    void oldVideosFromBeforeTheGameStartedAreNotImported() throws IOException {
        Path source = Files.createDirectories(temp.resolve("Videos"));
        Path old = source.resolve("old.mp4");
        Files.write(old, new byte[]{1});
        Files.setLastModifiedTime(old, java.nio.file.attribute.FileTime.fromMillis(1_000));
        RecordingLibrary library = new RecordingLibrary(temp.resolve("library"));
        Map<Path, Long> sizes = new HashMap<>();
        library.findImportable(List.of(source), System.currentTimeMillis() - 1000, sizes);
        assertTrue(library.findImportable(List.of(source), System.currentTimeMillis() - 1000, sizes).isEmpty());
    }

    // ---- FPS history ---------------------------------------------------------------------------------

    @Test
    void fpsHistorySplitsSessionsAndSummarises() throws IOException {
        Path file = temp.resolve("fps.csv");
        Files.writeString(file, "100,60\n101,120\n102,30\nbroken line\n1000,200\n1001,100\n");
        List<FpsHistory.Session> sessions = FpsHistory.sessions(FpsHistory.read(file));
        assertEquals(2, sessions.size());
        FpsHistory.Session first = sessions.get(0);
        assertEquals(70.0, first.average(), 0.001);
        assertEquals(30, first.lowest());
        assertEquals(120, first.highest());
        assertEquals(150.0, sessions.get(1).average(), 0.001);
        assertEquals(2, FpsHistory.downsample(List.of(10, 20, 30, 40), 2).size());
        assertEquals(15.0, FpsHistory.downsample(List.of(10, 20, 30, 40), 2).get(0), 0.001);
    }

    // ---- cleanup -----------------------------------------------------------------------------------

    @Test
    void cleanupDeletesLogsButKeepsWorldsAndProtectsInheritedVersions() throws IOException {
        Path game = Files.createDirectories(temp.resolve("minecraft"));
        Path launcher = Files.createDirectories(temp.resolve("launcher"));
        Files.createDirectories(game.resolve("logs"));
        Files.writeString(game.resolve("logs").resolve("old.log"), "x");
        Files.createDirectories(game.resolve("saves").resolve("World"));
        Files.writeString(game.resolve("saves").resolve("World").resolve("level.dat"), "w");
        Files.writeString(game.resolve("download.part"), "p");
        for (String id : List.of("1.8.9", "1.20.1", "fabric-loader-0.19-1.21.11", "1.21.11")) {
            Path directory = Files.createDirectories(game.resolve("versions").resolve(id));
            String parent = id.startsWith("fabric") ? "{\"inheritsFrom\":\"1.21.11\"}" : "{}";
            Files.writeString(directory.resolve(id + ".json"), parent);
        }
        Files.setLastModifiedTime(game.resolve("versions").resolve("fabric-loader-0.19-1.21.11"),
                java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()));
        Files.setLastModifiedTime(game.resolve("versions").resolve("1.21.11"),
                java.nio.file.attribute.FileTime.fromMillis(1_000));

        List<CleanupService.Item> items = CleanupService.scan(game, launcher,
                Set.of(CleanupService.Category.values()), Set.of());
        List<String> names = items.stream().map(item -> item.path().getFileName().toString()).toList();
        assertTrue(names.contains("old.log"));
        assertTrue(names.contains("download.part"));
        assertTrue(names.contains("1.8.9") && names.contains("1.20.1"));
        assertFalse(names.contains("1.21.11"), "the vanilla parent of a kept profile stays");
        assertFalse(names.contains("fabric-loader-0.19-1.21.11"), "the newest version stays");

        CleanupService.Result result = CleanupService.delete(items, game, launcher);
        assertEquals(0, result.failed());
        assertFalse(Files.exists(game.resolve("logs").resolve("old.log")));
        assertFalse(Files.exists(game.resolve("versions").resolve("1.8.9")));
        assertTrue(Files.exists(game.resolve("saves").resolve("World").resolve("level.dat")));
    }

    @Test
    void cleanupRefusesPathsOutsideItsFolders() throws IOException {
        Path game = Files.createDirectories(temp.resolve("minecraft"));
        Path outside = Files.writeString(temp.resolve("precious.txt"), "keep");
        CleanupService.Result result = CleanupService.delete(
                List.of(new CleanupService.Item(CleanupService.Category.LOGS, outside, 4)), game, game);
        assertEquals(1, result.failed());
        assertTrue(Files.exists(outside));
    }

    // ---- PC analysis ---------------------------------------------------------------------------------

    @Test
    void pcAdviceScalesWithHardware() {
        long gb = 1024L * 1024 * 1024;
        PcAnalysis.Advice low = PcAnalysis.advise(new PcAnalysis.Hardware(4 * gb, 4, "Intel(R) UHD Graphics"));
        PcAnalysis.Advice mid = PcAnalysis.advise(new PcAnalysis.Hardware(12 * gb, 6, "NVIDIA GeForce GTX 1650"));
        PcAnalysis.Advice high = PcAnalysis.advise(new PcAnalysis.Hardware(32 * gb, 16, "NVIDIA GeForce RTX 4080"));
        assertEquals(PcAnalysis.Tier.LOW, low.tier());
        assertEquals(PcAnalysis.Tier.MID, mid.tier());
        assertEquals(PcAnalysis.Tier.HIGH, high.tier());
        assertTrue(low.renderDistance() < mid.renderDistance() && mid.renderDistance() < high.renderDistance());
        assertTrue(low.memoryGb() <= 2, "a 4 GB PC never gives the game more than half its memory");
        assertEquals(BoostProfile.POTATO, low.profile());
        assertFalse(high.mods().isEmpty());
        assertTrue(PcAnalysis.integratedGraphics("AMD Radeon(TM) Graphics"));
        assertFalse(PcAnalysis.integratedGraphics("AMD Radeon RX 6700 XT"));
    }

    // ---- assistant -----------------------------------------------------------------------------------

    @Test
    void assistantRecognisesMemoryJavaAndDriverFailures() {
        long gb = 1024L * 1024 * 1024;
        var memory = LogAssistant.diagnose("java.lang.OutOfMemoryError: Java heap space", 3, 16 * gb);
        assertEquals(LogAssistant.Fix.RAISE_MEMORY, memory.getFirst().fix());
        var java = LogAssistant.diagnose("UnsupportedClassVersionError: class file version 69.0", 4, 16 * gb);
        assertEquals(LogAssistant.Fix.USE_JAVA_25, java.getFirst().fix());
        var reserve = LogAssistant.diagnose("Error occurred during initialization of VM\nCould not reserve enough space for 8388608KB object heap", 8, 8 * gb);
        assertEquals(LogAssistant.Fix.LOWER_MEMORY, reserve.getFirst().fix());
        var gpu = LogAssistant.diagnose("# EXCEPTION_ACCESS_VIOLATION (0xc0000005) nvoglv64.dll", 4, 16 * gb);
        assertEquals(LogAssistant.Fix.OPEN_FAQ_DRIVER, gpu.getFirst().fix());
        assertEquals("No problems found", LogAssistant.diagnose("[Render thread/INFO]: Setting user", 4, 16 * gb)
                .getFirst().title());
    }

    @Test
    void assistantDoesNotSuggestMoreMemoryThanThePcCanSpare() {
        long gb = 1024L * 1024 * 1024;
        var findings = LogAssistant.diagnose("OutOfMemoryError", 4, 8 * gb);
        assertEquals(LogAssistant.Fix.NONE, findings.getFirst().fix());
    }

    // ---- skins -------------------------------------------------------------------------------------

    @Test
    void randomSkinsAreValidAndPreviewHasTheRightSize() throws IOException {
        Random random = new Random(7);
        BufferedImage skin = SkinTools.randomSkin(random);
        assertNull(SkinTools.validate(skin));
        BufferedImage other = SkinTools.randomSkin(random);
        assertFalse(java.util.Arrays.equals(SkinTools.png(skin), SkinTools.png(other)));
        BufferedImage preview = SkinTools.frontPreview(skin, 8);
        assertEquals(16 * 8, preview.getWidth());
        assertEquals(32 * 8, preview.getHeight());
        assertTrue((preview.getRGB(5 * 8, 2 * 8) >>> 24) == 255, "the head is drawn");
        assertNotNull(SkinTools.validate(new BufferedImage(50, 50, BufferedImage.TYPE_INT_ARGB)));
        assertNull(SkinTools.validate(new BufferedImage(64, 32, BufferedImage.TYPE_INT_ARGB)));
    }

    @Test
    void presetsSaveWithSafeUniqueNames() throws IOException {
        BufferedImage skin = SkinTools.randomSkin(new Random(1));
        Path first = SkinTools.savePreset(temp, "../evil/name", skin);
        Path second = SkinTools.savePreset(temp, "../evil/name", skin);
        assertEquals(temp, first.getParent());
        assertEquals(2, SkinTools.presets(temp).size());
        assertFalse(first.equals(second));
        assertEquals("Skin", SkinTools.safeName("///"));
    }

    // ---- spotify and packs ---------------------------------------------------------------------------

    @Test
    void spotifyTitlesAreInterpreted() {
        assertEquals(SpotifyBridge.State.NOT_RUNNING, SpotifyBridge.fromSpotifyTitles(List.of()).state());
        assertEquals(SpotifyBridge.State.PAUSED, SpotifyBridge.fromSpotifyTitles(List.of("Spotify Free")).state());
        SpotifyBridge.Now playing = SpotifyBridge.fromSpotifyTitles(List.of("Daft Punk - Get Lucky"));
        assertEquals(SpotifyBridge.State.PLAYING, playing.state());
        assertEquals("Daft Punk", playing.artist());
        assertEquals("Get Lucky", playing.title());
    }

    @Test
    void packsRecogniseInstalledModsByFileName() throws IOException {
        Path mods = Files.createDirectories(temp.resolve("mods"));
        Files.writeString(mods.resolve("sodium-fabric-0.6.0+mc1.21.jar"), "x");
        Files.writeString(mods.resolve("ferritecore-7.0.0-fabric.jar"), "x");
        assertTrue(PackCatalog.alreadyInstalled(mods, "sodium"));
        assertTrue(PackCatalog.alreadyInstalled(mods, "ferrite-core"));
        assertFalse(PackCatalog.alreadyInstalled(mods, "lithium"));
        assertEquals(6, PackCatalog.PACKS.size());
    }

    // ---- helpers -------------------------------------------------------------------------------------

    private static byte[] box(String type, byte[] body) {
        ByteBuffer buffer = ByteBuffer.allocate(8 + body.length);
        buffer.putInt(8 + body.length).put(type.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1)).put(body);
        return buffer.array();
    }

    private static List<Byte> toList(byte[] bytes) {
        java.util.ArrayList<Byte> list = new java.util.ArrayList<>();
        for (byte b : bytes) list.add(b);
        return list;
    }
}
