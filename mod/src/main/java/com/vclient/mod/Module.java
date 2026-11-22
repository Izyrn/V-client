package com.vclient.mod;

import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/** Every feature shown in the Right Shift mod menu, with the options on its settings page. */
public enum Module {
    CLIENT("VYRN", "Menu key and HUD style", true, Kind.ALWAYS_ON,
            Setting.key("menuKey", "Mod menu key", GLFW.GLFW_KEY_RIGHT_SHIFT),
            Setting.choice("hudStyle", "HUD style", 1, "Frost", "Rounded glass")),
    MENU_BACKGROUND("Frost Menus", "Aurora, snowfall and glaciers behind menus", true, Kind.FEATURE,
            Setting.toggle("comet", "Aurora shimmer", true),
            Setting.toggle("twinkle", "Falling snow", true)),
    FPS("FPS", "Frames per second", true, Kind.HUD),
    CPS("CPS", "Clicks per second", true, Kind.HUD,
            Setting.toggle("rightClicks", "Show right clicks", true)),
    PING("Ping", "Server latency", false, Kind.HUD),
    COORDINATES("Coordinates", "Position and facing", true, Kind.HUD,
            Setting.toggle("facing", "Show facing", true)),
    SPEED("Speed", "Movement speed", false, Kind.HUD,
            Setting.choice("unit", "Unit", 0, "blocks/s", "km/h")),
    CLOCK("Clock", "Real-world time", false, Kind.HUD,
            Setting.toggle("twentyFour", "24-hour time", false),
            Setting.toggle("seconds", "Show seconds", false)),
    MEMORY("Memory", "Game RAM usage", false, Kind.HUD),
    DAY_COUNTER("Day Counter", "In-game day number", false, Kind.HUD),
    SERVER_ADDRESS("Server Address", "Server IP", false, Kind.HUD),
    POTION_EFFECTS("Potion Effects", "Effects and timers", true, Kind.HUD,
            Setting.toggle("level", "Show level", true)),
    KEYSTROKES("Keystrokes", "WASD, mouse, jump", true, Kind.HUD,
            Setting.toggle("mouse", "Show mouse buttons", true),
            Setting.toggle("space", "Show space bar", true)),
    ARMOR_STATUS("Armor Status", "Armor durability", true, Kind.HUD,
            Setting.toggle("held", "Show held item", true),
            Setting.toggle("numbers", "Show durability", true)),
    ITEM_COUNT("Item Counter", "Count a chosen item", false, Kind.HUD,
            Setting.choice("item", "Item", 0, "Totems", "Arrows", "Ender Pearls", "Golden Apples", "XP Bottles", "Fireworks")),
    LAST_DEATH("Last Death", "Where you last died", false, Kind.HUD),
    DURABILITY("Durability", "Durability of the item in hand", false, Kind.HUD),
    LIGHT_LEVEL("Light Level", "Light where you stand (mobs spawn at 0)", false, Kind.HUD),
    BIOME("Biome", "The biome you are in", false, Kind.HUD),
    TARGET_HUD("Target HUD", "Who you are fighting: health, armor, distance", true, Kind.HUD),
    COMBO("Combo Counter", "Hits in a row without getting hit", true, Kind.HUD),
    REACH("Reach Display", "How far away your last hit landed", false, Kind.HUD),
    ATTACK_COOLDOWN("Attack Cooldown", "Weapon charge bar while it recharges", false, Kind.HUD),
    DAMAGE_FEED("Damage Feed", "Damage you deal and kills, as they land", false, Kind.HUD),
    SESSION_STATS("Session Stats", "Playtime, kills, deaths, hits and best combo", false, Kind.HUD),
    MINIMAP("Minimap", "Map of the area around you with players and waypoints", true, Kind.HUD,
            Setting.number("size", "Map size", 96, 64, 160, 16, " px"),
            Setting.number("zoom", "Blocks per pixel", 1, 1, 4, 1, "x"),
            Setting.toggle("players", "Show players", true),
            Setting.toggle("waypoints", "Show waypoints", true)),
    HEAL_HELPER("Heal Helper", "Health, food and healing items, with a low-health warning", true, Kind.HUD,
            Setting.toggle("warning", "Low-health warning", true),
            Setting.number("warnAt", "Warn at (hearts)", 5, 1, 10, 1, "")),
    CUSTOM_SKY("Custom Sky", "Aurora bands and starlight in the night sky", true, Kind.FEATURE,
            Setting.choice("style", "Style", 0, "Aurora", "Starlight", "Aurora + stars"),
            Setting.number("strength", "Strength", 70, 20, 100, 10, "%")),
    SNOW("Snow Effects", "Snow falling around you", false, Kind.FEATURE,
            Setting.choice("where", "Where", 0, "Cold biomes", "Everywhere"),
            Setting.number("density", "Density", 4, 1, 12, 1, "")),
    FIREFLIES("Fireflies", "Fireflies glow near the ground at night", true, Kind.FEATURE,
            Setting.number("density", "Density", 3, 1, 10, 1, "")),
    GROUND_FOG("Ground Fog", "Low mist at night and dawn", false, Kind.FEATURE,
            Setting.number("density", "Density", 3, 1, 10, 1, "")),
    BETTER_PARTICLES("Better Particles", "Extra crit sparks on every hit", true, Kind.FEATURE,
            Setting.number("amount", "Amount", 2, 1, 5, 1, "x")),
    BETTER_HAND("Better Hand", "Move and resize the item in your hand", false, Kind.FEATURE,
            Setting.number("x", "Left / right", 0, -50, 50, 5, ""),
            Setting.number("y", "Down / up", 0, -50, 50, 5, ""),
            Setting.number("z", "Back / forward", 0, -50, 50, 5, ""),
            Setting.number("size", "Size", 100, 50, 150, 5, "%")),
    CUSTOM_SCOREBOARD("Custom Scoreboard", "Frosted scoreboard, red numbers optional", true, Kind.FEATURE,
            Setting.toggle("numbers", "Show numbers", false),
            Setting.toggle("background", "Background", true),
            Setting.number("scale", "Size", 100, 50, 150, 10, "%")),
    CUSTOM_TAB("Custom Tab List", "Ping shown in milliseconds, coloured", true, Kind.FEATURE),
    WAYPOINTS("Waypoints", "On-screen markers; your deaths are marked automatically", true, Kind.FEATURE,
            Setting.key("addKey", "Waypoints menu (Shift: add here)", GLFW.GLFW_KEY_N),
            Setting.toggle("death", "Death waypoints", true),
            Setting.toggle("distance", "Show distance", true),
            Setting.number("maxDistance", "Hide beyond (0 = never)", 0, 0, 5000, 250, " m")),
    ZOOM("Zoom", "Hold key to zoom", true, Kind.FEATURE,
            Setting.key("key", "Zoom key", GLFW.GLFW_KEY_C),
            Setting.number("level", "Zoom level", 4, 2, 10, 1, "x"),
            Setting.toggle("smooth", "Smooth zoom", true),
            Setting.toggle("cinematic", "Cinematic camera", true),
            Setting.toggle("scroll", "Scroll to adjust", true)),
    FULLBRIGHT("Fullbright", "See in the dark", false, Kind.FEATURE,
            Setting.number("brightness", "Brightness", 15, 2, 15, 1, "x")),
    COSMETICS("Cosmetics", "Capes, wings, hats, trails", true, Kind.FEATURE,
            Setting.key("menuKey", "Cosmetics menu key", GLFW.GLFW_KEY_K),
            Setting.key("emoteKey", "Play emote key", GLFW.GLFW_KEY_B),
            Setting.number("trailDensity", "Trail density", 100, 25, 200, 25, "%"),
            Setting.toggle("debug", "Debug mode (requests + stack traces)", false)),
    NO_HURT_CAM("No Hurt Cam", "No shake when hit", false, Kind.FEATURE),
    TOGGLE_SPRINT("Toggle Sprint", "Tap to keep sprinting", false, Kind.HUD),
    TOGGLE_SNEAK("Toggle Sneak", "Tap to keep sneaking", false, Kind.FEATURE);

    private enum Kind { ALWAYS_ON, FEATURE, HUD }


    private final String title;
    private final String description;
    private final boolean enabledByDefault;
    private final Kind kind;
    private final List<Setting> settings;

    Module(String title, String description, boolean enabledByDefault, Kind kind, Setting... own) {
        this.title = title;
        this.description = description;
        this.enabledByDefault = enabledByDefault;
        this.kind = kind;
        List<Setting> all = new ArrayList<>(List.of(own));
        if (kind == Kind.HUD) {
            all.add(CommonSettings.SCALE);
            all.add(CommonSettings.BACKGROUND);
            all.add(CommonSettings.LABEL_COLOR);
        }
        if (kind != Kind.ALWAYS_ON) all.add(CommonSettings.TOGGLE_KEY);
        this.settings = List.copyOf(all);
    }


    /** The modules shown in the menu: everything except cheats the player is not allowed to use. */
    public static Module[] available() {
        return java.util.Arrays.stream(values())
                .toArray(Module[]::new);
    }

    public String title() {
        return title;
    }

    public String description() {
        return description;
    }

    public boolean enabledByDefault() {
        return enabledByDefault;
    }

    /** Whether the module draws a movable element on the in-game HUD. */
    public boolean hudElement() {
        return kind == Kind.HUD;
    }

    /** Whether the module can be switched off (VYRN's own controls cannot). */
    public boolean toggleable() {
        return kind != Kind.ALWAYS_ON;
    }

    public List<Setting> settings() {
        return settings;
    }

    public Setting setting(String id) {
        for (Setting setting : settings) if (setting.id().equals(id)) return setting;
        throw new IllegalArgumentException(name() + " has no setting " + id);
    }
}
