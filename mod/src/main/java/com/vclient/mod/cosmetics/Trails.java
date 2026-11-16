package com.vclient.mod.cosmetics;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.vclient.mod.Module;
import com.vclient.mod.VClientMod;
import com.vclient.mod.VConfig;
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
 * Particle trails. The definition is
 * <pre>
 * "trail": { "particle": "minecraft:end_rod", "rate": 2, "count": 1, "spread": [0.3, 0.2, 0.3],
 *            "offset": [0, 0.1, 0], "velocity": [0, 0.01, 0], "color": "#00CFFF", "scale": 1,
 *            "onlyMoving": true }
 * </pre>
 * Particles are spawned client-side only with the game's normal particle limits and the player's particle
 * setting applied; nothing is sent to the server and no gameplay state changes.
 */
public final class Trails {
    private record Spec(ParticleEffect effect, int rate, int count, float[] spread, float[] offset, float[] velocity,
                        boolean onlyMoving) {}

    private static final Spec INVALID = new Spec(null, 1, 0, new float[3], new float[3], new float[3], false);
    private static final Map<String, Spec> SPECS = new ConcurrentHashMap<>();
    /** Hard ceiling on particles per tick, whatever a catalog item or the density setting asks for. */
    private static final int MAX_PER_TICK = 8;
    private static int ticks;

    private Trails() {}

    public static void tick(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        if (player == null || client.world == null || client.isPaused() || !CosmeticState.enabled()) return;
        Cosmetic cosmetic = CosmeticState.effective(CosmeticSlot.TRAIL);
        if (cosmetic == null || player.isSpectator() || player.isInvisible()) return;
        Spec spec = SPECS.computeIfAbsent(cosmetic.cacheKey(), ignored -> parse(cosmetic));
        if (spec == INVALID) return;
        ticks++;
        if (ticks % spec.rate != 0) return;
        if (spec.onlyMoving && player.getVelocity().horizontalLengthSquared() < 1.0E-4
                && Math.abs(player.getVelocity().y) < 0.02) return;
        double density = VConfig.get(Module.COSMETICS, "trailDensity") / 100.0;
        double amount = Math.min(MAX_PER_TICK, spec.count * density);
        int spawn = (int) amount + (ThreadLocalRandom.current().nextDouble() < amount - (int) amount ? 1 : 0);
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int i = 0; i < spawn; i++) {
            client.world.addParticleClient(spec.effect,
                    player.getX() + spec.offset[0] + (random.nextDouble() - 0.5) * 2 * spec.spread[0],
                    player.getY() + spec.offset[1] + random.nextDouble() * spec.spread[1],
                    player.getZ() + spec.offset[2] + (random.nextDouble() - 0.5) * 2 * spec.spread[2],
                    spec.velocity[0], spec.velocity[1], spec.velocity[2]);
        }
    }

    private static Spec parse(Cosmetic cosmetic) {
        try {
            JsonObject json = cosmetic.definition().getAsJsonObject("trail");
            Identifier id = Identifier.tryParse(Cosmetic.string(json, "particle", ""));
            ParticleType<?> type = id == null ? null : Registries.PARTICLE_TYPE.get(id);
            ParticleEffect effect;
            if (id != null && "minecraft:dust".equals(id.toString())) {
                effect = new DustParticleEffect(CosmeticTextures.parseColor(Cosmetic.string(json, "color", "#FFFFFF"),
                        0xFFFFFFFF) & 0xFFFFFF, (float) CosmeticModels.number(json, "scale", 1));
            } else if (type instanceof ParticleEffect simple) {
                effect = simple;
            } else {
                VClientMod.LOGGER.warn("[Cosmetics] Trail {} uses an unsupported particle: {}", cosmetic.id(), id);
                return INVALID;
            }
            return new Spec(effect, Math.clamp((int) CosmeticModels.number(json, "rate", 2), 1, 40),
                    Math.clamp((int) CosmeticModels.number(json, "count", 1), 1, MAX_PER_TICK),
                    array(json, "spread", 0.3f, 0.3f, 0.3f), array(json, "offset", 0, 0.1f, 0),
                    array(json, "velocity", 0, 0, 0),
                    json.has("onlyMoving") && json.get("onlyMoving").getAsBoolean());
        } catch (RuntimeException e) {
            VClientMod.LOGGER.warn("[Cosmetics] Trail {} is invalid: {}", cosmetic.id(), e.toString());
            return INVALID;
        }
    }

    private static float[] array(JsonObject json, String key, float x, float y, float z) {
        return json.get(key) instanceof JsonArray ? CosmeticModels.vector(json.get(key), x, y, z)
                : new float[]{x, y, z};
    }
}
