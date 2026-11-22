package com.vclient.mod;

import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumMap;
import java.util.Map;

public final class VClientMod implements ClientModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("VYRN");

    private static final Map<Module, Boolean> TOGGLE_KEYS_DOWN = new EnumMap<>(Module.class);
    private static boolean menuKeyWasDown;
    private static boolean zooming;
    private static boolean smoothCameraBeforeZoom;
    private static boolean zoomChangedSmoothCamera;
    private static float zoomProgress;
    private static double zoomLevel;
    private static boolean togglesApplied;
    private static double lastX = Double.NaN;
    private static double lastZ;
    private static double speed;

    @Override
    public void onInitializeClient() {
        VConfig.load();
        com.vclient.mod.cosmetics.Cosmetics.init();
        LOGGER.info("VYRN mod loaded. Press {} in game to open the mod menu.",
                KeyNames.name(VConfig.integer(Module.CLIENT, "menuKey")));
    }

    /** Called at the end of every client tick. */
    public static void tick(MinecraftClient client) {
        if (!togglesApplied && client.options != null) {
            if (VConfig.enabled(Module.TOGGLE_SPRINT)) client.options.getSprintToggled().setValue(true);
            if (VConfig.enabled(Module.TOGGLE_SNEAK)) client.options.getSneakToggled().setValue(true);
            togglesApplied = true;
        }
        boolean inGame = client.player != null && client.currentScreen == null;

        boolean menuKeyDown = keyDown(client, VConfig.integer(Module.CLIENT, "menuKey"));
        if (menuKeyDown && !menuKeyWasDown && inGame) client.setScreen(new ModMenuScreen());
        menuKeyWasDown = menuKeyDown;

        for (Module module : Module.values()) {
            if (!module.toggleable()) continue;
            boolean down = inGame && keyDown(client, VConfig.integer(module, CommonSettings.TOGGLE_KEY.id()));
            if (down && !TOGGLE_KEYS_DOWN.getOrDefault(module, false)) {
                boolean enabled = !VConfig.enabled(module);
                VConfig.setEnabled(module, enabled);
                if (module == Module.TOGGLE_SPRINT || module == Module.TOGGLE_SNEAK) applyMovementToggles(client);
                client.inGameHud.setOverlayMessage(Text.literal(module.title() + (enabled ? " enabled" : " disabled")),
                        false);
            }
            TOGGLE_KEYS_DOWN.put(module, down);
        }

        boolean zoomKeyDown = inGame && VConfig.enabled(Module.ZOOM)
                && keyDown(client, VConfig.integer(Module.ZOOM, "key"));
        if (zoomKeyDown != zooming) {
            if (zoomKeyDown) {
                zoomLevel = VConfig.get(Module.ZOOM, "level");
                zoomChangedSmoothCamera = VConfig.bool(Module.ZOOM, "cinematic");
                if (zoomChangedSmoothCamera) {
                    smoothCameraBeforeZoom = client.options.smoothCameraEnabled;
                    client.options.smoothCameraEnabled = true;
                }
            } else if (zoomChangedSmoothCamera) {
                client.options.smoothCameraEnabled = smoothCameraBeforeZoom;
            }
            zooming = zoomKeyDown;
        }

        com.vclient.mod.cosmetics.Cosmetics.tick(client);
        trackDeath(client);
        Safe.run("Combat HUD", () -> Combat.tick(client));
        Safe.run("Waypoints", () -> Waypoints.tick(client));
        FpsRecorder.tick(client);
        PerfProbe.tick(client);
        SkinSync.tick(client);
        FrostTitleScreen.replaceTitle(client);


        if (client.player != null) {
            double x = client.player.getX();
            double z = client.player.getZ();
            if (!Double.isNaN(lastX)) {
                double perSecond = Math.hypot(x - lastX, z - lastZ) * 20;
                speed += (perSecond - speed) * 0.5;
            }
            lastX = x;
            lastZ = z;
        } else {
            lastX = Double.NaN;
            speed = 0;
        }
    }

    static String itemId(net.minecraft.item.ItemStack stack) {
        return net.minecraft.registry.Registries.ITEM.getId(stack.getItem()).getPath();
    }

    // ---- HUD data for the Item Counter and Last Death

    private static String lastDeath = "no deaths yet";
    private static boolean deathRecorded;

    public static String lastDeath() {
        return lastDeath;
    }

    /** How many of an item (by registry path, e.g. "arrow") the player carries, off hand included. */
    public static int countItem(MinecraftClient client, String path) {
        if (client.player == null) return 0;
        int total = 0;
        var inventory = client.player.getInventory();
        for (int i = 0; i < inventory.size(); i++) {
            var stack = inventory.getStack(i);
            if (path.equals(itemId(stack))) total += stack.getCount();
        }
        var offhand = client.player.getOffHandStack();
        if (path.equals(itemId(offhand)) && !contains(inventory, offhand)) total += offhand.getCount();
        return total;
    }

    private static boolean contains(net.minecraft.entity.player.PlayerInventory inventory, net.minecraft.item.ItemStack stack) {
        for (int i = 0; i < inventory.size(); i++) if (inventory.getStack(i) == stack) return true;
        return false;
    }

    private static void trackDeath(MinecraftClient client) {
        if (client.player == null || client.world == null) return;
        if (client.player.isDead()) {
            if (!deathRecorded) {
                deathRecorded = true;
                lastDeath = String.format(java.util.Locale.ROOT, "%d %d %d (%s)", (int) client.player.getX(),
                        (int) client.player.getY(), (int) client.player.getZ(),
                        client.world.getRegistryKey().getValue().getPath());
            }
        } else {
            deathRecorded = false;
        }
    }

    private static boolean keyDown(MinecraftClient client, int key) {
        return key != Setting.NO_KEY && GLFW.glfwGetKey(client.getWindow().getHandle(), key) == GLFW.GLFW_PRESS;
    }

    /** Field of view after the zoom module. */
    public static float modifyFov(float fov) {
        float target = zooming ? 1.0f : 0.0f;
        if (VConfig.bool(Module.ZOOM, "smooth")) {
            zoomProgress += (target - zoomProgress) * 0.35f;
            if (Math.abs(target - zoomProgress) < 0.002f) zoomProgress = target;
        } else {
            zoomProgress = target;
        }
        if (zoomProgress <= 0) return fov;
        float divisor = 1.0f + (float) (zoomLevel - 1.0) * zoomProgress;
        return fov / divisor;
    }

    /** Scroll while zooming changes the zoom level; returns true when the scroll was used. */
    public static boolean onScroll(double amount) {
        if (!zooming || amount == 0 || !VConfig.bool(Module.ZOOM, "scroll")) return false;
        zoomLevel = Math.clamp(zoomLevel + Math.signum(amount), 1.5, 50);
        return true;
    }

    public static boolean fullbright() {
        return VConfig.enabled(Module.FULLBRIGHT);
    }

    public static double fullbrightGamma() {
        return VConfig.get(Module.FULLBRIGHT, "brightness");
    }

    public static boolean noHurtCam() {
        return VConfig.enabled(Module.NO_HURT_CAM);
    }

    public static double speed() {
        return speed;
    }

    /** Toggle Sprint/Sneak use Minecraft's own toggle options, so they behave exactly like vanilla. */
    public static void applyMovementToggles(MinecraftClient client) {
        client.options.getSprintToggled().setValue(VConfig.enabled(Module.TOGGLE_SPRINT));
        client.options.getSneakToggled().setValue(VConfig.enabled(Module.TOGGLE_SNEAK));
    }
}
