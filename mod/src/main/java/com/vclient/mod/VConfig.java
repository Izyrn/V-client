package com.vclient.mod;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

/** Module toggles, module settings, and HUD positions, saved to config/vclient.json. */
public final class VConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("vclient.json");

    private static final Map<Module, Boolean> ENABLED = new EnumMap<>(Module.class);
    private static final Map<Module, int[]> POSITIONS = new EnumMap<>(Module.class);
    private static final Map<Module, Map<String, Double>> VALUES = new EnumMap<>(Module.class);

    private VConfig() {}

    public static boolean enabled(Module module) {
        if (!module.toggleable()) return true;
        return ENABLED.getOrDefault(module, module.enabledByDefault());
    }

    public static void setEnabled(Module module, boolean value) {
        if (!module.toggleable()) return;
        ENABLED.put(module, value);
        save();
    }

    public static double get(Module module, Setting setting) {
        Map<String, Double> values = VALUES.get(module);
        Double value = values == null ? null : values.get(setting.id());
        return value == null ? setting.defaultValue() : setting.clamp(value);
    }

    public static double get(Module module, String settingId) {
        return get(module, module.setting(settingId));
    }

    public static boolean bool(Module module, String settingId) {
        return get(module, settingId) >= 0.5;
    }

    public static int integer(Module module, String settingId) {
        return (int) Math.round(get(module, settingId));
    }

    public static void set(Module module, Setting setting, double value) {
        VALUES.computeIfAbsent(module, ignored -> new HashMap<>()).put(setting.id(), setting.clamp(value));
        save();
    }

    public static void resetSettings(Module module) {
        VALUES.remove(module);
        save();
    }

    /** Saved HUD position, or null when the element still uses its default spot. */
    public static int[] position(Module module) {
        return POSITIONS.get(module);
    }

    public static void setPosition(Module module, int x, int y) {
        POSITIONS.put(module, new int[]{x, y});
    }

    public static void resetPositions() {
        POSITIONS.clear();
        save();
    }

    public static void load() {
        if (!Files.isRegularFile(FILE)) return;
        try {
            JsonObject root = GSON.fromJson(Files.readString(FILE, StandardCharsets.UTF_8), JsonObject.class);
            if (root == null) return;
            JsonObject modules = root.getAsJsonObject("modules");
            JsonObject positions = root.getAsJsonObject("positions");
            JsonObject settings = root.getAsJsonObject("settings");
            for (Module module : Module.values()) {
                if (modules != null && modules.has(module.name())) {
                    ENABLED.put(module, modules.get(module.name()).getAsBoolean());
                }
                if (positions != null && positions.has(module.name())) {
                    JsonObject position = positions.getAsJsonObject(module.name());
                    POSITIONS.put(module, new int[]{position.get("x").getAsInt(), position.get("y").getAsInt()});
                }
                if (settings != null && settings.has(module.name())) {
                    Map<String, Double> values = new HashMap<>();
                    for (Map.Entry<String, JsonElement> entry
                            : settings.getAsJsonObject(module.name()).entrySet()) {
                        values.put(entry.getKey(), entry.getValue().getAsDouble());
                    }
                    VALUES.put(module, values);
                }
            }
        } catch (IOException | RuntimeException e) {
            VClientMod.LOGGER.warn("Could not read V-Client settings; using defaults.", e);
        }
    }

    public static void save() {
        JsonObject root = new JsonObject();
        JsonObject modules = new JsonObject();
        JsonObject positions = new JsonObject();
        JsonObject settings = new JsonObject();
        for (Module module : Module.values()) {
            if (module.toggleable()) modules.addProperty(module.name(), enabled(module));
            int[] position = POSITIONS.get(module);
            if (position != null) {
                JsonObject saved = new JsonObject();
                saved.addProperty("x", position[0]);
                saved.addProperty("y", position[1]);
                positions.add(module.name(), saved);
            }
            Map<String, Double> values = VALUES.get(module);
            if (values != null && !values.isEmpty()) {
                JsonObject saved = new JsonObject();
                values.forEach(saved::addProperty);
                settings.add(module.name(), saved);
            }
        }
        root.add("modules", modules);
        root.add("positions", positions);
        root.add("settings", settings);
        try {
            Files.createDirectories(FILE.getParent());
            Files.writeString(FILE, GSON.toJson(root), StandardCharsets.UTF_8);
        } catch (IOException e) {
            VClientMod.LOGGER.warn("Could not save V-Client settings.", e);
        }
    }
}
