package com.vclient;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BundledResourcePackTest {
    @TempDir
    Path minecraft;

    @Test
    void copyOnlyPutsThePackInTheFolderWithoutEnablingIt() throws Exception {
        assertTrue(BundledResourcePack.ensureInstalled(minecraft, line -> {}));
        assertTrue(Files.size(minecraft.resolve("resourcepacks").resolve(BundledResourcePack.FILE_NAME)) > 1000);
        assertTrue(Files.size(minecraft.resolve("resourcepacks").resolve(BundledResourcePack.UI_FILE_NAME)) > 500);
        assertFalse(Files.exists(minecraft.resolve("options.txt")));
        assertFalse(BundledResourcePack.ensureInstalled(minecraft, line -> {}), "up to date: nothing copied");
    }

    @Test
    void installsAndEnablesOnFirstRunOnly() throws Exception {
        List<String> output = new ArrayList<>();
        assertTrue(BundledResourcePack.ensureInstalledAndEnabled(minecraft, output::add));
        assertEquals("resourcePacks:[\"vanilla\",\"file/Ultra Smooth PvP.zip\",\"file/VYRN UI.zip\"]",
                Files.readAllLines(minecraft.resolve("options.txt")).getFirst());

        // the player turns it off in Options: the launcher must not turn it back on
        Files.writeString(minecraft.resolve("options.txt"), "resourcePacks:[\"vanilla\"]\n");
        output.clear();
        assertFalse(BundledResourcePack.ensureInstalledAndEnabled(minecraft, output::add));
        assertEquals("resourcePacks:[\"vanilla\"]", Files.readAllLines(minecraft.resolve("options.txt")).getFirst());
    }

    @Test
    void keepsTheirOtherPacksAndSettings() throws Exception {
        Files.writeString(minecraft.resolve("options.txt"),
                "renderDistance:12\nresourcePacks:[\"vanilla\",\"file/Mine.zip\"]\nfov:0.5\n");
        BundledResourcePack.ensureInstalledAndEnabled(minecraft, line -> {});
        assertEquals(List.of("renderDistance:12",
                "resourcePacks:[\"vanilla\",\"file/Mine.zip\",\"file/Ultra Smooth PvP.zip\",\"file/VYRN UI.zip\"]", "fov:0.5"),
                Files.readAllLines(minecraft.resolve("options.txt")));
    }

    @Test
    void onlySupportsTheVersionsThePackWasBuiltFor() {
        assertTrue(BundledResourcePack.supports("1.21.4"));
        assertTrue(BundledResourcePack.supports("1.21.11"));
        assertFalse(BundledResourcePack.supports("1.21.1"));
        assertFalse(BundledResourcePack.supports("1.20.4"));
        assertFalse(BundledResourcePack.supports("1.21.12"));
        assertFalse(BundledResourcePack.supports(null));
    }
}
