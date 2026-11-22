package com.vclient.mod;

import net.minecraft.client.MinecraftClient;

import java.util.Locale;
import java.util.Set;

/**
 * Makes players who do not use VYRN see your Ely.by skin on servers that run the SkinsRestorer plugin: shortly
 * after you join, it sends {@code /skin url <your Ely.by skin>} once. Only for the servers listed in
 * {@code -Dvclient.skin.syncHosts} (default {@code *}: every server), only for launches that came with an Ely.by skin, and
 * never more than once per join. Without SkinsRestorer the server simply says the command is unknown.
 * Turn it off with {@code -Dvclient.skin.sync=false}.
 */
final class SkinSync {
    private static final boolean ENABLED = !"false".equalsIgnoreCase(System.getProperty("vclient.skin.sync", "true"));
    private static final Set<String> HOSTS = Set.of(System.getProperty("vclient.skin.syncHosts", "*")
            .toLowerCase(Locale.ROOT).split("\\s*,\\s*"));
    private static final int DELAY_TICKS = 100; // let the join finish and the server's own skin handling run first

    private static int ticksInWorld;
    private static boolean sent;

    private SkinSync() {}

    static void tick(MinecraftClient client) {
        if (!ENABLED) return;
        if (client.world == null || client.player == null || client.getCurrentServerEntry() == null) {
            ticksInWorld = 0;
            sent = false;
            return;
        }
        if (sent || ++ticksInWorld < DELAY_TICKS) return;
        sent = true;
        String address = client.getCurrentServerEntry().address.toLowerCase(Locale.ROOT).replaceFirst(":\\d+$", "");
        if (!HOSTS.contains("*") && !HOSTS.contains(address)) return;
        String url = com.vclient.mod.cosmetics.SkinFix.launcherSkinUrl();
        if (url.isEmpty() || !url.startsWith("https://") || url.contains(" ")) return;
        String model = "slim".equalsIgnoreCase(com.vclient.mod.cosmetics.SkinFix.launcherSkinModel()) ? "slim" : "classic";
        if (client.getNetworkHandler() == null) return;
        VClientMod.LOGGER.info("[Skin] Asking {} (SkinsRestorer) to show your Ely.by skin to other players.", address);
        client.getNetworkHandler().sendChatCommand("skin url " + url + " " + model);
    }
}
