package com.vclient.mod;

import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ServerInfo;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Writes what you are doing (server or world, dimension) every few seconds so the launcher's Discord status can show it. */
public final class Presence {
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("vclient-presence.json");
    private static int ticks;
    private static String last = "";

    private Presence() {}

    public static void tick(MinecraftClient client) {
        if (++ticks % 100 != 0) return;
        JsonObject json = new JsonObject();
        String activity;
        if (client.world == null || client.player == null) {
            activity = "In the menus";
        } else if (client.isInSingleplayer()) {
            activity = "Singleplayer";
        } else {
            ServerInfo server = client.getCurrentServerEntry();
            json.addProperty("server", server == null ? "" : server.address);
            activity = "Multiplayer";
        }
        json.addProperty("activity", activity);
        if (client.world != null) json.addProperty("dimension", client.world.getRegistryKey().getValue().getPath());
        String text = json.toString();
        if (text.equals(last)) {
            // still write now and then so the launcher knows the game is alive
            if (ticks % 600 != 0) return;
        }
        last = text;
        try {
            Path temporary = FILE.resolveSibling("vclient-presence.json.tmp");
            Files.writeString(temporary, text, StandardCharsets.UTF_8);
            Files.move(temporary, FILE, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            // best effort only
        }
    }
}
