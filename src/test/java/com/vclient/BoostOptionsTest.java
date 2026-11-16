package com.vclient;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class BoostOptionsTest {
    @TempDir
    Path directory;

    private void writeOptions(String... lines) throws Exception {
        Files.write(BoostOptions.optionsFile(directory), List.of(lines), StandardCharsets.UTF_8);
    }

    private List<String> readOptions() throws Exception {
        return Files.readAllLines(BoostOptions.optionsFile(directory), StandardCharsets.UTF_8);
    }

    @Test
    void changesOnlyTheNamedKeysAndAddsMissingOnes() throws Exception {
        writeOptions("renderDistance:16", "mouseSensitivity:0.5", "resourcePacks:[\"vanilla\"]");
        Map<String, String> values = new LinkedHashMap<>();
        values.put("renderDistance", "8");
        values.put("enableVsync", "false");

        int changed = BoostOptions.apply(directory, values, line -> {});

        assertEquals(2, changed);
        List<String> lines = readOptions();
        assertTrue(lines.contains("renderDistance:8"));
        assertTrue(lines.contains("enableVsync:false"));
        assertTrue(lines.contains("mouseSensitivity:0.5"));
        assertTrue(lines.contains("resourcePacks:[\"vanilla\"]"));
        assertTrue(BoostOptions.applied(directory));
    }

    @Test
    void applyingTwiceKeepsThePlayersOriginalValues() throws Exception {
        writeOptions("renderDistance:16");
        BoostOptions.apply(directory, Map.of("renderDistance", "8"), line -> {});
        int secondTime = BoostOptions.apply(directory, Map.of("renderDistance", "4"), line -> {});

        assertEquals(1, secondTime);
        BoostOptions.restore(directory, line -> {});
        assertTrue(readOptions().contains("renderDistance:16"));
    }

    @Test
    void restoreBringsBackOriginalsAndRemovesKeysThatDidNotExist() throws Exception {
        writeOptions("renderDistance:16", "ao:true");
        Map<String, String> values = new LinkedHashMap<>();
        values.put("renderDistance", "4");
        values.put("ao", "false");
        values.put("weatherRadius", "3");
        BoostOptions.apply(directory, values, line -> {});

        int restored = BoostOptions.restore(directory, line -> {});

        assertEquals(3, restored);
        List<String> lines = readOptions();
        assertTrue(lines.contains("renderDistance:16"));
        assertTrue(lines.contains("ao:true"));
        assertFalse(lines.stream().anyMatch(line -> line.startsWith("weatherRadius:")));
        assertFalse(BoostOptions.applied(directory));
    }

    @Test
    void worksWhenThereIsNoOptionsFileYet() throws Exception {
        BoostOptions.apply(directory, Map.of("enableVsync", "false"), line -> {});
        assertEquals(List.of("enableVsync:false"), readOptions());

        BoostOptions.restore(directory, line -> {});
        assertEquals(new ArrayList<String>(), readOptions());
    }

    @Test
    void everyProfileSetsVsyncOffAndStaysInTheRequestedRanges() {
        for (BoostProfile profile : BoostProfile.values()) {
            assertEquals("false", profile.options().get("enableVsync"));
        }
        Map<String, String> competitive = BoostProfile.COMPETITIVE.options();
        assertEquals("8", competitive.get("renderDistance"));
        assertEquals("4", competitive.get("simulationDistance"));
        assertEquals("\"off\"", competitive.get("renderClouds"));
        assertEquals("2", competitive.get("particles"));
        Map<String, String> potato = BoostProfile.POTATO.options();
        assertEquals("4", potato.get("renderDistance"));
        assertEquals("0", potato.get("mipmapLevels"));
        assertEquals("false", potato.get("ao"));
        assertEquals("false", potato.get("entityShadows"));
    }

    @Test
    void modSetsGrowWithTheProfileAndMemoryNeverExceedsHalfTheRam() {
        assertEquals(7, BoostProfile.ULTRA.mods().size());
        assertEquals(9, BoostProfile.BALANCED.mods().size());
        assertEquals(9, BoostProfile.COMPETITIVE.mods().size());
        assertEquals(9, BoostProfile.POTATO.mods().size());

        long eightGb = 8L * 1024 * 1024 * 1024;
        long thirtyTwoGb = 32L * 1024 * 1024 * 1024;
        assertEquals(4, BoostProfile.ULTRA.recommendedMemoryGb(eightGb));
        assertEquals(8, BoostProfile.ULTRA.recommendedMemoryGb(thirtyTwoGb));
        assertEquals(3, BoostProfile.POTATO.recommendedMemoryGb(thirtyTwoGb));
        assertEquals(2, BoostProfile.POTATO.recommendedMemoryGb(2L * 1024 * 1024 * 1024));
        assertEquals("5G", BoostProfile.COMPETITIVE.initialHeap(5));
        assertEquals("3G", BoostProfile.BALANCED.initialHeap(6));
        assertTrue(BoostProfile.POTATO.jvmFlags().contains("-XX:+UseG1GC"));
    }
}
