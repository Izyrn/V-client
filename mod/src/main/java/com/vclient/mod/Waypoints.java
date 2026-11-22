package com.vclient.mod;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.render.Camera;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Waypoints and death waypoints, kept per server (or singleplayer world) and dimension, drawn as on-screen markers
 * with their distance. Press the waypoint key to drop one where you stand, Shift + the key to remove the nearest one.
 */
public final class Waypoints {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("vclient-waypoints.json");
    private static final int[] COLORS = {0xFF00CFFF, 0xFF7CF29A, 0xFFFFD166, 0xFFC792EA, 0xFFFF9F43, 0xFF55FFFF};
    private static final int DEATH_COLOR = 0xFFFF6B6B;
    private static final int MAX_DEATHS = 3;

    record Waypoint(String name, int x, int y, int z, int color, boolean death) {}

    /** "server|dimension" (or "world:Name|dimension") to the waypoints there. */
    private static final Map<String, List<Waypoint>> ALL = new LinkedHashMap<>();
    private static boolean loaded;
    private static boolean keyWasDown;
    private static boolean wasDead;
    /** The world field of view after zoom, recorded by the renderer so markers line up while zooming. */
    private static float fov = 70f;

    private Waypoints() {}

    public static void recordFov(float value) {
        if (value > 1 && value < 179) fov = value;
    }

    public static void tick(MinecraftClient client) {
        if (!loaded) load();
        if (client.player == null || client.world == null || !VConfig.enabled(Module.WAYPOINTS)) {
            keyWasDown = false;
            wasDead = client.player != null && client.player.isDead();
            return;
        }
        boolean dead = client.player.isDead();
        if (dead && !wasDead && VConfig.bool(Module.WAYPOINTS, "death")) addDeath(client);
        wasDead = dead;

        int key = VConfig.integer(Module.WAYPOINTS, "addKey");
        boolean down = client.currentScreen == null && key != Setting.NO_KEY
                && org.lwjgl.glfw.GLFW.glfwGetKey(client.getWindow().getHandle(), key) == org.lwjgl.glfw.GLFW.GLFW_PRESS;
        if (down && !keyWasDown) {
            long window = client.getWindow().getHandle();
            boolean shift = org.lwjgl.glfw.GLFW.glfwGetKey(window, org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_SHIFT) == org.lwjgl.glfw.GLFW.GLFW_PRESS
                    || org.lwjgl.glfw.GLFW.glfwGetKey(window, org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_SHIFT) == org.lwjgl.glfw.GLFW.GLFW_PRESS;
            if (shift) addHere(client);
            else client.setScreen(new WaypointsScreen());
        }
        keyWasDown = down;
    }

    private static String key(MinecraftClient client) {
        String place;
        if (client.isInSingleplayer() && client.getServer() != null) {
            place = "world:" + client.getServer().getSaveProperties().getLevelName();
        } else {
            ServerInfo server = client.getCurrentServerEntry();
            place = server == null ? "unknown" : server.address.toLowerCase(Locale.ROOT);
        }
        return place + "|" + client.world.getRegistryKey().getValue();
    }

    /** Waypoints in the current world and dimension (for the minimap); empty when none. */
    static List<Waypoint> current(MinecraftClient client) {
        if (!loaded) load();
        if (client.world == null) return List.of();
        List<Waypoint> list = ALL.get(key(client));
        return list == null ? List.of() : List.copyOf(list);
    }

    private static List<Waypoint> here(MinecraftClient client) {
        return ALL.computeIfAbsent(key(client), ignored -> new ArrayList<>());
    }

    static void addHere(MinecraftClient client) {
        List<Waypoint> list = here(client);
        var pos = client.player.getBlockPos();
        String menuKey = KeyNames.name(VConfig.integer(Module.WAYPOINTS, "addKey"));
        for (Waypoint existing : list) {
            if (!existing.death() && Math.abs(existing.x() - pos.getX()) <= 2 && Math.abs(existing.y() - pos.getY()) <= 2
                    && Math.abs(existing.z() - pos.getZ()) <= 2) {
                chat(client, "§b[VYRN] §f" + existing.name() + " is already here. Walk away to see its marker; "
                        + menuKey + " opens the list.");
                return;
            }
        }
        int number = 1;
        while (true) {
            String candidate = "Waypoint " + number;
            if (list.stream().noneMatch(point -> point.name().equals(candidate))) break;
            number++;
        }
        Waypoint point = new Waypoint("Waypoint " + number, pos.getX(), pos.getY(), pos.getZ(),
                COLORS[(number - 1) % COLORS.length], false);
        list.add(point);
        save();
        chat(client, "§b[VYRN] §f" + point.name() + " set at " + pos.getX() + " " + pos.getY() + " " + pos.getZ()
                + ". Walk away to see its marker; " + menuKey + " opens the list.");
    }

    /** Client-side chat line (only you see it); servers often overwrite the action bar, so this is where feedback goes. */
    private static void chat(MinecraftClient client, String text) {
        client.inGameHud.getChatHud().addMessage(Text.literal(text));
    }

    private static void addDeath(MinecraftClient client) {
        List<Waypoint> list = here(client);
        var pos = client.player.getBlockPos();
        String time = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT));
        list.add(new Waypoint("Death " + time, pos.getX(), pos.getY(), pos.getZ(), DEATH_COLOR, true));
        List<Waypoint> deaths = list.stream().filter(Waypoint::death).toList();
        for (int i = 0; i < deaths.size() - MAX_DEATHS; i++) list.remove(deaths.get(i));
        save();
    }

    static void remove(MinecraftClient client, Waypoint point) {
        here(client).remove(point);
        save();
    }

    private static void removeNearest(MinecraftClient client) {
        List<Waypoint> list = here(client);
        Waypoint nearest = null;
        double best = Double.MAX_VALUE;
        for (Waypoint point : list) {
            double distance = client.player.squaredDistanceTo(point.x() + 0.5, point.y(), point.z() + 0.5);
            if (distance < best) {
                best = distance;
                nearest = point;
            }
        }
        if (nearest == null) {
            chat(client, "§b[VYRN] §fNo waypoints here to remove");
            return;
        }
        list.remove(nearest);
        save();
        chat(client, "§b[VYRN] §fRemoved " + nearest.name());
    }

    /** Draws a marker for every waypoint in this dimension that is in front of the camera and within range. */
    public static void render(DrawContext context, MinecraftClient client) {
        if (!VConfig.enabled(Module.WAYPOINTS) || client.player == null || client.world == null) return;
        if (!loaded) load();
        List<Waypoint> list = ALL.get(key(client));
        if (list == null || list.isEmpty()) return;
        Camera camera = client.gameRenderer.getCamera();
        Vec3d eye = camera.getCameraPos();
        double yaw = Math.toRadians(camera.getYaw());
        double pitch = Math.toRadians(camera.getPitch());
        // Camera basis in Minecraft's axes: yaw 0 looks south (+Z), positive pitch looks down.
        Vec3d forward = new Vec3d(-Math.sin(yaw) * Math.cos(pitch), -Math.sin(pitch), Math.cos(yaw) * Math.cos(pitch));
        Vec3d right = new Vec3d(-Math.cos(yaw), 0, -Math.sin(yaw));
        Vec3d up = right.crossProduct(forward);
        int width = client.getWindow().getScaledWidth();
        int height = client.getWindow().getScaledHeight();
        double focal = 1.0 / Math.tan(Math.toRadians(fov) / 2);
        double aspect = width / (double) height;
        int maxDistance = (int) VConfig.get(Module.WAYPOINTS, "maxDistance");
        boolean showDistance = VConfig.bool(Module.WAYPOINTS, "distance");
        TextRenderer font = client.textRenderer;

        for (Waypoint point : list) {
            Vec3d relative = new Vec3d(point.x() + 0.5, point.y() + 1.2, point.z() + 0.5).subtract(eye);
            double distance = relative.length();
            if (maxDistance > 0 && distance > maxDistance) continue;
            double depth = relative.dotProduct(forward);
            if (depth < 0.1) continue;
            double sx = width / 2.0 + relative.dotProduct(right) / depth * focal / aspect * width / 2.0;
            double sy = height / 2.0 - relative.dotProduct(up) / depth * focal * height / 2.0;
            if (sx < -40 || sx > width + 40 || sy < -20 || sy > height + 20) continue;
            int x = (int) Math.round(sx);
            int y = (int) Math.round(sy);
            String label = point.name() + (showDistance ? "  " + Math.round(distance) + "m" : "");
            int labelWidth = font.getWidth(label);
            diamond(context, x, y, point.color());
            context.fill(x - labelWidth / 2 - 3, y - 17, x + labelWidth / 2 + 3, y - 6, 0x900C1F33);
            context.fill(x - labelWidth / 2 - 3, y - 17, x + labelWidth / 2 + 3, y - 16, point.color());
            context.drawText(font, label, x - labelWidth / 2, y - 15, 0xFFFFFFFF, true);
        }
    }

    private static void diamond(DrawContext context, int x, int y, int color) {
        for (int row = -3; row <= 3; row++) {
            int half = 3 - Math.abs(row);
            context.fill(x - half - 1, y + row, x + half + 2, y + row + 1, 0xC0000000);
            context.fill(x - half, y + row, x + half + 1, y + row + 1, color);
        }
    }

    private static void load() {
        loaded = true;
        if (!Files.isRegularFile(FILE)) return;
        try {
            JsonObject root = GSON.fromJson(Files.readString(FILE, StandardCharsets.UTF_8), JsonObject.class);
            if (root == null) return;
            for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
                List<Waypoint> list = new ArrayList<>();
                for (JsonElement element : entry.getValue().getAsJsonArray()) {
                    JsonObject point = element.getAsJsonObject();
                    list.add(new Waypoint(point.get("name").getAsString(), point.get("x").getAsInt(),
                            point.get("y").getAsInt(), point.get("z").getAsInt(), point.get("color").getAsInt(),
                            point.has("death") && point.get("death").getAsBoolean()));
                }
                ALL.put(entry.getKey(), list);
            }
        } catch (IOException | RuntimeException e) {
            VClientMod.LOGGER.warn("Could not read waypoints from {}: {}", FILE, e.toString());
        }
    }

    private static void save() {
        JsonObject root = new JsonObject();
        ALL.forEach((place, list) -> {
            if (list.isEmpty()) return;
            JsonArray array = new JsonArray();
            for (Waypoint point : list) {
                JsonObject object = new JsonObject();
                object.addProperty("name", point.name());
                object.addProperty("x", point.x());
                object.addProperty("y", point.y());
                object.addProperty("z", point.z());
                object.addProperty("color", point.color());
                object.addProperty("death", point.death());
                array.add(object);
            }
            root.add(place, array);
        });
        try {
            Files.createDirectories(FILE.getParent());
            Path temporary = FILE.resolveSibling(FILE.getFileName() + ".tmp");
            Files.writeString(temporary, GSON.toJson(root), StandardCharsets.UTF_8);
            Files.move(temporary, FILE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            VClientMod.LOGGER.warn("Could not save waypoints: {}", e.toString());
        }
    }
}
