package com.vclient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guards the bundled cosmetics catalog: unique valid ids, sane event windows, and a grants list that matches it. */
final class BuiltinCosmeticsTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path CATALOG = Path.of("mod/src/main/resources/vclient/builtin-cosmetics.json");
    private static final Path GRANT_IDS = Path.of("website/cosmetic-ids.json");
    private static final Set<String> CAPE_ART = Set.of("goku_gi", "goku_ssj", "kamehameha", "ultra_instinct", "sakura_drift",
            "sakura_fall", "shonen_flame", "scout_cloak", "ramadan", "ramadan_animated", "aurora", "starfield",
            "solid", "gradient", "stripes", "checker", "shimmer", "sparkle");

    @Test
    void everyItemHasAUniqueValidIdAndAKnownLook() throws IOException {
        Set<String> seen = new HashSet<>();
        for (JsonNode item : JSON.readTree(CATALOG.toFile()).path("items")) {
            String id = item.path("id").asText();
            assertTrue(id.matches("[a-z0-9_.-]+"), "invalid id: " + id);
            assertTrue(seen.add(id), "duplicate id: " + id);
            assertTrue(!item.path("name").asText().isBlank(), id + " has no name");
            String generator = item.path("definition").path("texture").path("generator").path("type").asText("");
            if (!generator.isEmpty()) assertTrue(CAPE_ART.contains(generator), id + " uses an unknown generator " + generator);
        }
        assertTrue(seen.size() >= 90, "the catalog should keep all its items");
    }

    @Test
    void theAnimeEventRunsFromTodayToTheEndOfDecember() throws IOException {
        long start = 0;
        long end = 0;
        int cosmetics = 0;
        for (JsonNode item : JSON.readTree(CATALOG.toFile()).path("items")) {
            if (!item.path("id").asText().matches("goku_.*|kamehameha_cape|ultra_instinct_cape|sakura_.*|battle_flames_cape|scout_cloak_cape|checkered_haori_cape|golden_spikes_hat|power_up_emote|beam_pose_emote")) continue;
            cosmetics++;
            assertEquals("event", item.path("tier").asText(), item.path("id") + " should be an event item");
            long itemStart = item.path("startsAt").asLong();
            long itemEnd = item.path("endsAt").asLong();
            assertTrue(itemStart > 0 && itemEnd > itemStart, item.path("id") + " has a bad window");
            if (start == 0) {
                start = itemStart;
                end = itemEnd;
            }
            assertEquals(start, itemStart, "all event items start together");
            assertEquals(end, itemEnd, "all event items end together");
        }
        assertTrue(cosmetics >= 12, "expected the anime event items, found " + cosmetics);
        // 18 Nov 2026 00:00 and 1 Jan 2027 00:00 in UTC+4.
        assertEquals(java.time.OffsetDateTime.parse("2026-11-18T00:00:00+04:00").toEpochSecond(), start);
        assertEquals(java.time.OffsetDateTime.parse("2027-01-01T00:00:00+04:00").toEpochSecond(), end);
    }

    @Test
    void theGrantListKnowsEveryCatalogItem() throws IOException {
        Set<String> granted = new HashSet<>();
        for (JsonNode entry : JSON.readTree(GRANT_IDS.toFile())) granted.add(entry.path("id").asText());
        for (JsonNode item : JSON.readTree(CATALOG.toFile()).path("items")) {
            assertTrue(granted.contains(item.path("id").asText()), "website/cosmetic-ids.json is missing " + item.path("id").asText());
        }
    }
}
