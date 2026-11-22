package com.vclient.mod.cosmetics;

import com.vclient.mod.Module;
import com.vclient.mod.Setting;
import com.vclient.mod.VConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import org.lwjgl.glfw.GLFW;

/**
 * Entry point of the cosmetics system: start-up, the per-tick work (trails, emote and menu keys) and the
 * hooks the mixins call. Cosmetics only change how the local player looks on this client.
 */
public final class Cosmetics {
    private static boolean menuKeyWasDown;
    private static boolean emoteKeyWasDown;
    private static int preloadedKey = -1;
    private static boolean diagnosedOnJoin;

    private Cosmetics() {}

    public static void init() {
        CosmeticState.load();
        CosmeticCatalog.init();
        SkinFix.init();
    }

    /** Called at the end of every client tick. */
    public static void tick(MinecraftClient client) {
        if (client.player == null) {
            Emotes.stop();
            return;
        }
        // Load what is equipped before it is first drawn, so there is no pop-in and a failure shows up at once.
        int key = CosmeticState.version() * 31 + CosmeticCatalog.revision();
        if (key != preloadedKey) {
            preloadedKey = key;
            for (CosmeticSlot slot : CosmeticSlot.values()) {
                Cosmetic equipped = CosmeticState.effective(slot);
                if (equipped != null && equipped.definition().has("texture")) CosmeticTextures.current(equipped);
            }
        }
        if (!diagnosedOnJoin && CosmeticNet.debug()) {
            diagnosedOnJoin = true;
            CosmeticDiagnostics.run();
        }
        boolean inGame = client.currentScreen == null;
        boolean menuDown = inGame && keyDown(client, VConfig.integer(Module.COSMETICS, "menuKey"));
        if (menuDown && !menuKeyWasDown) openMenu(client, null);
        menuKeyWasDown = menuDown;

        boolean emoteDown = inGame && keyDown(client, VConfig.integer(Module.COSMETICS, "emoteKey"));
        if (emoteDown && !emoteKeyWasDown && CosmeticState.enabled()) {
            if (Emotes.active()) Emotes.stop();
            else Emotes.play(CosmeticState.effective(CosmeticSlot.EMOTE));
        }
        emoteKeyWasDown = emoteDown;

        Emotes.tick(client);
        Trails.tick(client);
        com.vclient.mod.Safe.run("Auras and effects", () -> Effects.tick(client));
    }

    public static void openMenu(MinecraftClient client, Screen parent) {
        client.setScreen(new CosmeticsScreen(parent));
    }

    private static boolean keyDown(MinecraftClient client, int key) {
        return key != Setting.NO_KEY && GLFW.glfwGetKey(client.getWindow().getHandle(), key) == GLFW.GLFW_PRESS;
    }
}
