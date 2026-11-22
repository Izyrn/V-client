package com.vclient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Local play statistics: playtime, launches per version, achievements and the daily-login streak.
 * Saved to {@code stats.json} next to settings.json. Nothing here is sent anywhere.
 */
final class PlayerStats {
    static final String FIRST_LAUNCH = "first_launch";
    static final String HUNDRED_HOURS = "hundred_hours";
    static final String FIRST_MOD = "first_mod";
    static final String FPS_BOOST = "fps_boost";
    static final long HUNDRED_HOURS_SECONDS = 100L * 3600;

    record Achievement(String id, String title, String description) {}

    static final List<Achievement> ACHIEVEMENTS = List.of(
            new Achievement(FIRST_LAUNCH, "First Launch", "Start the game from VYRN."),
            new Achievement(HUNDRED_HOURS, "100 Hours Played", "Play for 100 hours through VYRN."),
            new Achievement(FIRST_MOD, "Installed First Mod", "Install a mod from the Mods tab or a pack."),
            new Achievement(FPS_BOOST, "Used FPS Boost", "Turn on VSMP Boost."));

    /** What a claimed day pays out. Only days 1, 7 and 30 carry a cosmetic; other days just keep the streak. */
    enum Reward {
        NONE("Streak day"), COSMETIC("Cosmetic"), EXCLUSIVE("Exclusive Cosmetic"), LEGEND("Legend Cosmetic");

        private final String label;

        Reward(String label) {
            this.label = label;
        }

        String label() {
            return label;
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private final Path file;
    private long playtimeSeconds;
    private final Map<String, Integer> launches = new LinkedHashMap<>();
    private final Set<String> achievements = new LinkedHashSet<>();
    private LocalDate lastClaim;
    private LocalDate firstSeen;
    private int streak;
    /** Streak days whose reward was earned. The cosmetic grant itself needs the backend. */
    private final Set<Integer> earnedRewardDays = new LinkedHashSet<>();

    PlayerStats(Path file) {
        this.file = file;
    }

    static Path defaultFile() {
        return AppInfo.dataDir().resolve("stats.json");
    }

    static PlayerStats load(Path file) {
        PlayerStats stats = new PlayerStats(file);
        try {
            if (Files.isRegularFile(file)) {
                JsonNode root = JSON.readTree(file.toFile());
                stats.playtimeSeconds = Math.max(0, root.path("playtimeSeconds").asLong(0));
                root.path("launches").fields().forEachRemaining(e -> stats.launches.put(e.getKey(), e.getValue().asInt(0)));
                root.path("achievements").forEach(a -> stats.achievements.add(a.asText()));
                String claim = root.path("lastClaim").asText("");
                if (!claim.isBlank()) stats.lastClaim = LocalDate.parse(claim);
                String seen = root.path("firstSeen").asText("");
                if (!seen.isBlank()) stats.firstSeen = LocalDate.parse(seen);
                stats.streak = root.path("streak").asInt(0);
                root.path("earnedRewardDays").forEach(d -> stats.earnedRewardDays.add(d.asInt()));
            }
        } catch (IOException | RuntimeException e) {
            // A damaged stats file starts over rather than blocking the launcher.
        }
        return stats;
    }

    synchronized void save() {
        try {
            Files.createDirectories(file.getParent());
            ObjectNode root = JSON.createObjectNode();
            root.put("playtimeSeconds", playtimeSeconds);
            ObjectNode launchNode = root.putObject("launches");
            launches.forEach(launchNode::put);
            var list = root.putArray("achievements");
            achievements.forEach(list::add);
            root.put("lastClaim", lastClaim == null ? "" : lastClaim.toString());
            root.put("streak", streak);
            root.put("firstSeen", firstSeen == null ? "" : firstSeen.toString());
            var days = root.putArray("earnedRewardDays");
            earnedRewardDays.forEach(days::add);
            JSON.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), root);
        } catch (IOException e) {
            // Statistics are optional; losing one write is harmless.
        }
    }

    /** Remembers the first day this launcher ran (only the first call has an effect). */
    synchronized void markSeen(LocalDate today) {
        if (firstSeen == null) firstSeen = today;
    }

    synchronized LocalDate firstSeen() {
        return firstSeen;
    }

    synchronized long playtimeSeconds() {
        return playtimeSeconds;
    }

    synchronized void addPlaytime(long seconds) {
        if (seconds <= 0) return;
        playtimeSeconds += seconds;
        if (playtimeSeconds >= HUNDRED_HOURS_SECONDS) achievements.add(HUNDRED_HOURS);
    }

    synchronized void recordLaunch(String versionId) {
        if (versionId == null || versionId.isBlank()) return;
        launches.merge(versionId, 1, Integer::sum);
        achievements.add(FIRST_LAUNCH);
    }

    /** The version launched most often, or an empty string before the first launch. */
    synchronized String favoriteVersion() {
        return launches.entrySet().stream().max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey).orElse("");
    }

    synchronized int totalLaunches() {
        return launches.values().stream().mapToInt(Integer::intValue).sum();
    }

    /** Unlocks an achievement; returns true only the first time. */
    synchronized boolean unlock(String id) {
        return achievements.add(id);
    }

    synchronized boolean unlocked(String id) {
        return achievements.contains(id);
    }

    // ---- daily login rewards ----------------------------------------------------------------------

    static Reward rewardForDay(int day) {
        return switch (day) {
            case 1 -> Reward.COSMETIC;
            case 7 -> Reward.EXCLUSIVE;
            case 30 -> Reward.LEGEND;
            default -> Reward.NONE;
        };
    }

    synchronized boolean canClaim(LocalDate today) {
        return lastClaim == null || today.isAfter(lastClaim);
    }

    /** Streak day the next claim would land on: it continues on consecutive days and restarts otherwise. */
    synchronized int nextStreakDay(LocalDate today) {
        if (lastClaim == null) return 1;
        long gap = ChronoUnit.DAYS.between(lastClaim, today);
        int next = gap == 1 ? streak + 1 : gap <= 0 ? streak : 1;
        return next > 30 ? 1 : next;
    }

    /** Claims today's login; returns the reward, or null when today was already claimed. */
    synchronized Reward claim(LocalDate today) {
        if (!canClaim(today)) return null;
        streak = nextStreakDay(today);
        lastClaim = today;
        Reward reward = rewardForDay(streak);
        if (reward != Reward.NONE) earnedRewardDays.add(streak);
        return reward;
    }

    synchronized int streak() {
        return streak;
    }

    synchronized Set<Integer> earnedRewardDays() {
        return Set.copyOf(earnedRewardDays);
    }
}
