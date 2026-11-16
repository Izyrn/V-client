package com.vclient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The four VSMP Boost profiles. Each one is a bundle of: Minecraft video options written to options.txt, a
 * set of optimisation mods from Modrinth, a memory size and JVM flags. Quality drops and FPS rises from Ultra
 * Quality to Potato. Low-End PC maps to Potato and Extreme FPS to Competitive.
 */
enum BoostProfile {
    ULTRA("Crystal Mode", "Highest quality, still smoother than vanilla",
            "+20% to +40%", 2,
            "Keeps the pretty settings and adds the mods that cost nothing visually. High FPS without giving up how the game looks."),
    BALANCED("Aurora Mode", "Balanced: a good mix of FPS and visuals",
            "+60% to +100%", 3,
            "Fast lighting, shorter distances, culling and memory fixes. Looks almost the same."),
    COMPETITIVE("Blizzard Mode", "Competitive PvP: low graphics, maximum visibility",
            "+100% to +180%", 3,
            "Everything that hides enemies or costs FPS is turned off. Built for PvP."),
    POTATO("Glacier Mode", "Maximum FPS, the default for low-end PCs",
            "+150% to +250%", 3,
            "Minimum distances and effects, the leanest memory use. Maximum FPS on low-end PCs.");

    /** A Modrinth project to install: the slug the API uses and the name shown to the player. */
    record BoostMod(String slug, String name, String effect) {}

    private static final BoostMod SODIUM = new BoostMod("sodium", "Sodium", "Rewrites the renderer: the biggest FPS gain");
    private static final BoostMod LITHIUM = new BoostMod("lithium", "Lithium", "Faster game logic and physics");
    private static final BoostMod FERRITE = new BoostMod("ferrite-core", "FerriteCore", "Much lower memory use");
    private static final BoostMod IMMEDIATELY_FAST = new BoostMod("immediatelyfast", "ImmediatelyFast",
            "Faster HUD, text and entity drawing");
    private static final BoostMod ENTITY_CULLING = new BoostMod("entityculling", "EntityCulling",
            "Skips entities you cannot see");
    private static final BoostMod MORE_CULLING = new BoostMod("moreculling", "MoreCulling",
            "Skips block faces you cannot see");
    private static final BoostMod MODERN_FIX = new BoostMod("modernfix", "ModernFix",
            "Faster start-up, less memory, fewer stutters");
    private static final BoostMod NOISIUM = new BoostMod("noisium", "Noisium", "Faster chunk generation");
    private static final BoostMod MEMORY_LEAK_FIX = new BoostMod("memoryleakfix", "MemoryLeakFix",
            "Stops memory leaks that cause lag spikes");

    private final String label;
    private final String tagline;
    private final String estimate;
    private final int modTier;
    private final String summary;

    BoostProfile(String label, String tagline, String estimate, int modTier, String summary) {
        this.label = label;
        this.tagline = tagline;
        this.estimate = estimate;
        this.modTier = modTier;
        this.summary = summary;
    }

    String label() {
        return label;
    }

    String tagline() {
        return tagline;
    }

    /** A typical range, not a measurement: real gains depend on the PC, the world and the server. */
    String estimatedFpsGain() {
        return estimate;
    }

    String summary() {
        return summary;
    }

    /** The optimisation mods this profile installs: seven for Quality, all nine for the others. */
    List<BoostMod> mods() {
        List<BoostMod> mods = new ArrayList<>(List.of(SODIUM, LITHIUM, FERRITE, IMMEDIATELY_FAST));
        if (modTier >= 2) mods.addAll(List.of(ENTITY_CULLING, MODERN_FIX, MEMORY_LEAK_FIX));
        if (modTier >= 3) mods.addAll(List.of(MORE_CULLING, NOISIUM));
        return List.copyOf(mods);
    }

    /** The options.txt values this profile sets. Everything not listed is left exactly as the player has it. */
    Map<String, String> options() {
        Map<String, String> options = new LinkedHashMap<>();
        options.put("enableVsync", "false");
        options.put("maxFps", "260");
        switch (this) {
            case ULTRA -> {
                options.put("graphicsPreset", "\"fancy\"");
                options.put("ao", "true");
                options.put("entityShadows", "true");
                options.put("renderClouds", "\"fancy\"");
                options.put("particles", "0");
                options.put("mipmapLevels", "4");
                options.put("renderDistance", "14");
                options.put("simulationDistance", "8");
                options.put("biomeBlendRadius", "3");
                options.put("entityDistanceScaling", "1.0");
            }
            case BALANCED -> {
                options.put("graphicsPreset", "\"fast\"");
                options.put("ao", "false");
                options.put("entityShadows", "false");
                options.put("renderClouds", "\"fast\"");
                options.put("particles", "1");
                options.put("mipmapLevels", "2");
                options.put("renderDistance", "10");
                options.put("simulationDistance", "6");
                options.put("biomeBlendRadius", "1");
                options.put("entityDistanceScaling", "0.75");
            }
            case COMPETITIVE -> {
                options.put("graphicsPreset", "\"fast\"");
                options.put("ao", "false");
                options.put("entityShadows", "false");
                options.put("renderClouds", "\"off\"");
                options.put("particles", "2");
                options.put("mipmapLevels", "1");
                options.put("renderDistance", "8");
                options.put("simulationDistance", "4");
                options.put("biomeBlendRadius", "0");
                options.put("entityDistanceScaling", "0.75");
                options.put("cutoutLeaves", "false");
            }
            case POTATO -> {
                options.put("graphicsPreset", "\"fast\"");
                options.put("ao", "false");
                options.put("entityShadows", "false");
                options.put("renderClouds", "\"off\"");
                options.put("particles", "2");
                options.put("mipmapLevels", "0");
                options.put("renderDistance", "4");
                options.put("simulationDistance", "4");
                options.put("biomeBlendRadius", "0");
                options.put("entityDistanceScaling", "0.5");
                options.put("cutoutLeaves", "false");
                options.put("weatherRadius", "3");
                options.put("chunkSectionFadeInTime", "0.0");
            }
        }
        return options;
    }

    /**
     * The options for a PC of this class. Low-end PCs are held to short distances whatever profile they picked;
     * high-end PCs on Quality or Balanced get fancy graphics and longer distances, because they can afford them.
     * Mid-range PCs get the profile exactly as written.
     */
    Map<String, String> options(PcAnalysis.Tier tier) {
        Map<String, String> options = options();
        switch (tier) {
            case LOW -> {
                capInt(options, "renderDistance", this == ULTRA || this == BALANCED ? 8 : 6);
                capInt(options, "simulationDistance", 6);
                capInt(options, "mipmapLevels", 2);
                if (this == ULTRA || this == BALANCED) {
                    options.put("graphicsPreset", "\"fast\"");
                    options.put("ao", "false");
                    options.put("entityShadows", "false");
                    options.put("entityDistanceScaling", "0.75");
                }
            }
            case HIGH -> {
                if (this == ULTRA || this == BALANCED) {
                    options.put("graphicsPreset", "\"fancy\"");
                    options.put("ao", "true");
                    options.put("entityDistanceScaling", "1.0");
                    options.put("renderDistance", this == ULTRA ? "18" : "14");
                    options.put("simulationDistance", this == ULTRA ? "12" : "8");
                    options.put("biomeBlendRadius", "3");
                    options.put("mipmapLevels", "4");
                }
            }
            case MID -> { }
        }
        return options;
    }

    private static void capInt(Map<String, String> options, String key, int ceiling) {
        try {
            if (Integer.parseInt(options.get(key)) > ceiling) options.put(key, String.valueOf(ceiling));
        } catch (NumberFormatException e) {
            // Not a number: leave it as the profile wrote it.
        }
    }

    /**
     * How much memory to give the game, in GB. More memory than the game needs does not add FPS and makes
     * garbage-collection pauses longer, so the leaner profiles ask for less. Never more than half the PC's RAM.
     */
    int recommendedMemoryGb(long physicalRamBytes) {
        int target = switch (this) {
            case ULTRA -> 8;
            case BALANCED -> 6;
            case COMPETITIVE -> 5;
            case POTATO -> 3;
        };
        long ramGb = Math.max(2, physicalRamBytes / (1024L * 1024 * 1024));
        int ceiling = (int) Math.max(2, ramGb / 2);
        return Math.clamp(Math.min(target, ceiling), 2, 16);
    }

    /** The -Xms value: equal to -Xmx on the leaner profiles so the heap never has to grow (a source of stutter). */
    String initialHeap(int memoryGb) {
        return this == COMPETITIVE || this == POTATO ? memoryGb + "G" : Math.max(1, memoryGb / 2) + "G";
    }

    /** Garbage-collector flags aimed at short, even pauses (frame consistency) rather than raw throughput. */
    List<String> jvmFlags() {
        List<String> flags = new ArrayList<>(List.of(
                "-XX:+UseG1GC",
                "-XX:+ParallelRefProcEnabled",
                "-XX:+DisableExplicitGC",
                "-XX:+UnlockExperimentalVMOptions",
                "-XX:+PerfDisableSharedMem"));
        switch (this) {
            case ULTRA -> flags.add("-XX:MaxGCPauseMillis=100");
            case BALANCED -> flags.addAll(List.of("-XX:MaxGCPauseMillis=60", "-XX:G1NewSizePercent=30",
                    "-XX:G1MaxNewSizePercent=40", "-XX:G1HeapRegionSize=8M"));
            case COMPETITIVE, POTATO -> flags.addAll(List.of("-XX:MaxGCPauseMillis=40", "-XX:G1NewSizePercent=30",
                    "-XX:G1MaxNewSizePercent=40", "-XX:G1HeapRegionSize=8M", "-XX:G1ReservePercent=20",
                    "-XX:G1HeapWastePercent=5", "-XX:G1MixedGCCountTarget=4",
                    "-XX:InitiatingHeapOccupancyPercent=15", "-XX:G1RSetUpdatingPauseTimePercent=5",
                    "-XX:SurvivorRatio=32", "-XX:MaxTenuringThreshold=1"));
        }
        return List.copyOf(flags);
    }

    /** Everything the launcher adds to the game command for this profile. */
    record Tuning(BoostProfile profile, int memoryGb, String initialHeap, List<String> jvmFlags,
                  Map<String, String> options) {}

    Tuning tuning(long physicalRamBytes) {
        int memory = recommendedMemoryGb(physicalRamBytes);
        return new Tuning(this, memory, initialHeap(memory), jvmFlags(), options());
    }

    /** The tuning for this exact PC: memory from its RAM, video settings from its class. */
    Tuning tuning(PcAnalysis.Hardware hardware) {
        int memory = recommendedMemoryGb(hardware.ramBytes());
        return new Tuning(this, memory, initialHeap(memory), jvmFlags(), options(PcAnalysis.tier(hardware)));
    }

    /** Total RAM of this PC in bytes, or 8 GB when the JVM cannot tell. */
    static long physicalRamBytes() {
        try {
            if (java.lang.management.ManagementFactory.getOperatingSystemMXBean()
                    instanceof com.sun.management.OperatingSystemMXBean os) {
                return os.getTotalMemorySize();
            }
        } catch (RuntimeException | LinkageError e) {
            // Fall through to the default.
        }
        return 8L * 1024 * 1024 * 1024;
    }

    static BoostProfile parse(String name, BoostProfile fallback) {
        if (name != null) {
            for (BoostProfile profile : values()) if (profile.name().equalsIgnoreCase(name)) return profile;
        }
        return fallback;
    }
}
