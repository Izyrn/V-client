package com.vclient.mod;

import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/** Every feature shown in the Right Shift mod menu, with the options on its settings page. */
public enum Module {
    CLIENT("VYRN", "Menu key", true, Kind.ALWAYS_ON,
            Setting.key("menuKey", "Mod menu key", GLFW.GLFW_KEY_RIGHT_SHIFT)),
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
    WAYPOINTS("Waypoints", "On-screen markers; your deaths are marked automatically", true, Kind.FEATURE,
            Setting.key("addKey", "Add waypoint (Shift: remove nearest)", GLFW.GLFW_KEY_N),
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
