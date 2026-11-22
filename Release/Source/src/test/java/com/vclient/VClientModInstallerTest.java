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

class VClientModInstallerTest {
    @TempDir
    Path minecraft;

    @Test
    void skipsInstallWhenMinecraft12111IsMissing() throws Exception {
        assertFalse(VClientModInstaller.ensureInstalled(minecraft, line -> {}));
        assertFalse(Files.exists(minecraft.resolve("versions").resolve(VClientModInstaller.PROFILE_ID)));
    }

    @Test
    void installsBundledFabricProfileOfflineAndOnlyOnce() throws Exception {
        Path parent = minecraft.resolve("versions/1.21.11/1.21.11.json");
        Files.createDirectories(parent.getParent());
        Files.writeString(parent, "{\"id\":\"1.21.11\",\"type\":\"release\",\"mainClass\":\"net.minecraft.client.main.Main\",\"libraries\":[]}");

        List<String> output = new ArrayList<>();
        assertTrue(VClientModInstaller.ensureInstalled(minecraft, output::add));
        Path profile = minecraft.resolve("versions/VYRN 1.21.11/VYRN 1.21.11.json");
        assertTrue(Files.isRegularFile(profile));
        assertTrue(Files.isRegularFile(minecraft.resolve(
                "libraries/net/fabricmc/fabric-loader/0.19.5/fabric-loader-0.19.5.jar")));
        assertTrue(Files.isRegularFile(minecraft.resolve(
                "libraries/com/vclient/vclient-mod/1.0.0/vclient-mod-1.0.0.jar")));
        assertEquals(1, output.size());

        List<MinecraftLauncher.InstalledVersion> versions =
                new MinecraftLauncher((major, out, downloads) -> Path.of("java")).findVersions(minecraft);
        MinecraftLauncher.InstalledVersion preferred = MinecraftLauncher.preferredInstalledVersion(versions);
        assertEquals(VClientModInstaller.PROFILE_ID, preferred.id());
        assertEquals("Fabric", preferred.loader());

        output.clear();
        assertTrue(VClientModInstaller.ensureInstalled(minecraft, output::add));
        assertTrue(output.isEmpty(), "an up-to-date install copies nothing");
    }

    @Test
    void replacesTheOldVClientProfileWithTheVyrnOne() throws Exception {
        Path parent = minecraft.resolve("versions/1.21.11/1.21.11.json");
        Files.createDirectories(parent.getParent());
        Files.writeString(parent, "{\"id\":\"1.21.11\",\"type\":\"release\",\"mainClass\":\"net.minecraft.client.main.Main\",\"libraries\":[]}");
        Path old = minecraft.resolve("versions/V-Client 1.21.11/V-Client 1.21.11.json");
        Files.createDirectories(old.getParent());
        Files.writeString(old, "{}");
        Path world = minecraft.resolve("saves/World/level.dat");
        Files.createDirectories(world.getParent());
        Files.writeString(world, "keep");

        assertTrue(VClientModInstaller.ensureInstalled(minecraft, line -> {}));

        assertFalse(Files.exists(old.getParent()));
        assertTrue(Files.isRegularFile(minecraft.resolve("versions/VYRN 1.21.11/VYRN 1.21.11.json")));
        assertEquals("keep", Files.readString(world));
    }
}
