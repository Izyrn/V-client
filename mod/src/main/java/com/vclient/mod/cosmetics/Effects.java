package com.vclient.mod.cosmetics;

import com.google.gson.JsonObject;
import com.vclient.mod.VClientMod;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.particle.ParticleType;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Auras (a ring of particles circling you) and one-off effects for kills, your deaths and wins. All client-side
 * particles with the game's normal particle limits; nothing is sent to the server. Definitions:
 * <pre>
 * "aura":   { "particle": "minecraft:snowflake", "count": 2, "radius": 0.9, "height": 1.0, "speed": 1, "rings": 1 }
 * "effect": { "particle": "minecraft:firework", "count": 40, "pattern": "burst|ring|spiral|column", "size": 1.2,
 *             "speed": 0.15, "color": "#00CFFF", "scale": 1 }
 * </pre>
 */
public final class Effects {
    private static final Map<String, ParticleEffect> PARTICLES = new ConcurrentHashMap<>();
    private static final ParticleEffect NONE = new DustParticleEffect(0xFFFFFF, 1f);
    private static final int MAX_BURST = 80;
    private static int ticks;
    private static int victoryTicks;

    private Effects() {}

    /** Particle from a definition's "particle" (and "color"/"scale" for dust); null when unknown. */
    public static ParticleEffect particle(JsonObject json, String fallback) {
        String idText = Cosmetic.string(json, "particle", fallback);
        String color = Cosmetic.string(json, "color", "#FFFFFF");
        String key = idText + "|" + color + "|" + Cosmetic.string(json, "scale", "1");
        ParticleEffect cached = PARTICLES.computeIfAbsent(key, ignored -> {
            Identifier id = Identifier.tryParse(idText);
            if (id == null) return NONE;
            if ("minecraft:dust".equals(id.toString())) {
                return new DustParticleEffect(CosmeticTextures.parseColor(color, 0xFFFFFFFF) & 0xFFFFFF,
                        (float) CosmeticModels.number(json, "scale", 1));
            }
            ParticleType<?> type = Registries.PARTICLE_TYPE.get(id);
            if (type instanceof ParticleEffect simple) return simple;
            VClientMod.LOGGER.warn("[Cosmetics] Unsupported particle {}", id);
            return NONE;
        });
        return cached == NONE ? null : cached;
    }

    public static void tick(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        if (player == null || client.world == null || client.isPaused() || !CosmeticState.enabled()) return;
        ticks++;
        if (!player.isSpectator() && !player.isInvisible()) aura(client, player);
        if (victoryTicks > 0) {
            if (victoryTicks % 10 == 0) play(client, CosmeticSlot.VICTORY_EFFECT, player.getX(), player.getY() + 1, player.getZ());
            victoryTicks--;
        }
    }

    private static void aura(MinecraftClient client, ClientPlayerEntity player) {
        Cosmetic cosmetic = CosmeticState.effective(CosmeticSlot.AURA);
        if (cosmetic == null || !(cosmetic.definition().get("aura") instanceof JsonObject json)) return;
        ParticleEffect effect = particle(json, "minecraft:end_rod");
        if (effect == null) return;
        int count = Math.clamp((int) CosmeticModels.number(json, "count", 2), 1, 6);
        int rings = Math.clamp((int) CosmeticModels.number(json, "rings", 1), 1, 3);
        double radius = CosmeticModels.number(json, "radius", 0.9);
        double height = CosmeticModels.number(json, "height", 1.0);
        double speed = CosmeticModels.number(json, "speed", 1);
        for (int ring = 0; ring < rings; ring++) {
            for (int i = 0; i < count; i++) {
                double angle = ticks * 0.15 * speed + Math.PI * 2 * i / count + ring * Math.PI / rings;
                double y = player.getY() + height + (rings > 1 ? (ring - (rings - 1) / 2.0) * 0.6 : Math.sin(ticks * 0.05) * 0.15);
                client.world.addParticleClient(effect, player.getX() + Math.cos(angle) * radius, y,
                        player.getZ() + Math.sin(angle) * radius, 0, 0, 0);
            }
        }
    }

    public static void onKill(MinecraftClient client, double x, double y, double z) {
        play(client, CosmeticSlot.KILL_EFFECT, x, y, z);
    }

    public static void onDeath(MinecraftClient client) {
        if (client.player != null) play(client, CosmeticSlot.DEATH_EFFECT, client.player.getX(), client.player.getY() + 0.5, client.player.getZ());
    }

    /** A title such as "VICTORY!" or "You won" appeared: celebrate for about 3 seconds. */
    public static void onTitle(String title) {
        String text = title == null ? "" : title.toLowerCase(java.util.Locale.ROOT);
        if (text.matches(".*(victory|winner|you win|you won|champion|#1\\b|1st place).*")) victoryTicks = 60;
    }

    private static void play(MinecraftClient client, CosmeticSlot slot, double x, double y, double z) {
        if (client.world == null || !CosmeticState.enabled()) return;
        Cosmetic cosmetic = CosmeticState.effective(slot);
        if (cosmetic == null || !(cosmetic.definition().get("effect") instanceof JsonObject json)) return;
        ParticleEffect effect = particle(json, "minecraft:firework");
        if (effect == null) return;
        int count = Math.clamp((int) CosmeticModels.number(json, "count", 30), 1, MAX_BURST);
        double size = CosmeticModels.number(json, "size", 1.0);
        double speed = CosmeticModels.number(json, "speed", 0.12);
        String pattern = Cosmetic.string(json, "pattern", "burst");
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int i = 0; i < count; i++) {
            double t = i / (double) count;
            double angle = Math.PI * 2 * t;
            double px = x, py = y, pz = z, vx, vy, vz;
            switch (pattern) {
                case "ring" -> {
                    px += Math.cos(angle) * size;
                    pz += Math.sin(angle) * size;
                    vx = Math.cos(angle) * speed * 0.3;
                    vy = 0.02;
                    vz = Math.sin(angle) * speed * 0.3;
                }
                case "spiral" -> {
                    double turn = angle * 3;
                    px += Math.cos(turn) * size * (1 - t * 0.5);
                    pz += Math.sin(turn) * size * (1 - t * 0.5);
                    py += t * 2.2 - 0.5;
                    vx = vz = 0;
                    vy = speed * 0.4;
                }
                case "column" -> {
                    px += (random.nextDouble() - 0.5) * size * 0.6;
                    pz += (random.nextDouble() - 0.5) * size * 0.6;
                    py += t * 2.5 - 0.5;
                    vx = vz = 0;
                    vy = speed;
                }
                default -> {
                    double theta = random.nextDouble() * Math.PI * 2;
                    double phi = Math.acos(2 * random.nextDouble() - 1);
                    vx = Math.sin(phi) * Math.cos(theta) * speed;
                    vy = Math.cos(phi) * speed + 0.02;
                    vz = Math.sin(phi) * Math.sin(theta) * speed;
                }
            }
            client.world.addParticleClient(effect, px, py, pz, vx, vy, vz);
        }
    }
}
