package com.vclient.mod;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.particle.ParticleType;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Client-side visual extras: Custom Sky (aurora bands or starlight drawn over the night sky), Snow Effects, Fireflies,
 * Ground Fog and Better Particles (extra sparks on every hit). Everything is particles or screen drawing only.
 */
public final class Visuals {
    private static final Map<String, ParticleEffect> PARTICLES = new ConcurrentHashMap<>();
    private static final double[][] STARS = new double[70][3];

    static {
        java.util.Random random = new java.util.Random(7);
        for (double[] star : STARS) {
            star[0] = random.nextDouble() * Math.PI * 2;          // azimuth
            star[1] = Math.toRadians(8 + random.nextDouble() * 80); // elevation
            star[2] = random.nextDouble();                          // twinkle phase / size
        }
    }

    private Visuals() {}

    /** A simple particle by id, or null if this game version does not have it. */
    static ParticleEffect particle(String id) {
        ParticleEffect cached = PARTICLES.computeIfAbsent(id, key -> {
            ParticleType<?> type = Registries.PARTICLE_TYPE.get(Identifier.of(key));
            return type instanceof ParticleEffect effect ? effect : null;
        });
        return cached;
    }

    /** 0 by day, 1 in the middle of the night, fading at dusk and dawn. */
    static float night(MinecraftClient client) {
        if (client.world == null) return 0;
        long time = Math.floorMod(client.world.getTimeOfDay(), 24000L);
        if (time < 12000 || time > 24000) return 0;
        if (time < 13500) return (time - 12000) / 1500f;
        if (time > 22500) return (24000 - time) / 1500f;
        return 1;
    }

    private static boolean overworld(MinecraftClient client) {
        return client.world != null && "overworld".equals(client.world.getRegistryKey().getValue().getPath());
    }

    public static void tick(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        if (player == null || client.world == null || client.isPaused()) return;
        ThreadLocalRandom random = ThreadLocalRandom.current();
        if (VConfig.enabled(Module.SNOW) && snowHere(client, player)) {
            ParticleEffect snow = particle("minecraft:snowflake");
            int count = (int) VConfig.get(Module.SNOW, "density");
            for (int i = 0; snow != null && i < count; i++) {
                double x = player.getX() + (random.nextDouble() - 0.5) * 24;
                double z = player.getZ() + (random.nextDouble() - 0.5) * 24;
                double y = player.getY() + 4 + random.nextDouble() * 10;
                if (!client.world.isSkyVisible(BlockPos.ofFloored(x, y, z))) continue;
                client.world.addParticleClient(snow, x, y, z, (random.nextDouble() - 0.5) * 0.02, -0.06, 0);
            }
        }
        float night = night(client);
        if (VConfig.enabled(Module.FIREFLIES) && night > 0.3f && overworld(client)) {
            ParticleEffect firefly = particle("minecraft:firefly");
            if (firefly == null) firefly = particle("minecraft:glow");
            int count = (int) VConfig.get(Module.FIREFLIES, "density");
            for (int i = 0; firefly != null && i < count; i++) {
                if (random.nextInt(4) != 0) continue;
                double x = player.getX() + (random.nextDouble() - 0.5) * 20;
                double z = player.getZ() + (random.nextDouble() - 0.5) * 20;
                double y = player.getY() + random.nextDouble() * 3;
                BlockPos pos = BlockPos.ofFloored(x, y, z);
                if (!client.world.getBlockState(pos).isAir() || client.world.getBlockState(below(pos)).isAir()) continue;
                client.world.addParticleClient(firefly, x, y + 0.3, z, 0, 0.005, 0);
            }
        }
        if (VConfig.enabled(Module.GROUND_FOG) && (night > 0.2f || dawn(client)) && overworld(client)) {
            ParticleEffect mist = particle("minecraft:cloud");
            int count = (int) VConfig.get(Module.GROUND_FOG, "density");
            for (int i = 0; mist != null && i < count; i++) {
                if (random.nextInt(3) != 0) continue;
                double x = player.getX() + (random.nextDouble() - 0.5) * 28;
                double z = player.getZ() + (random.nextDouble() - 0.5) * 28;
                BlockPos ground = BlockPos.ofFloored(x, player.getY() + 2, z);
                for (int down = 0; down < 6 && client.world.getBlockState(below(ground)).isAir(); down++) ground = below(ground);
                if (!client.world.getBlockState(ground).isAir()) continue;
                client.world.addParticleClient(mist, x, ground.getY() + 0.15, z, (random.nextDouble() - 0.5) * 0.01, 0.0, 0);
            }
        }
    }

    private static BlockPos below(BlockPos pos) {
        return new BlockPos(pos.getX(), pos.getY() - 1, pos.getZ());
    }

    private static boolean dawn(MinecraftClient client) {
        long time = Math.floorMod(client.world.getTimeOfDay(), 24000L);
        return time > 22500 || time < 1500;
    }

    private static boolean snowHere(MinecraftClient client, ClientPlayerEntity player) {
        if (VConfig.integer(Module.SNOW, "where") == 1) return overworld(client);
        return client.world.getBiome(player.getBlockPos()).comp_349().getTemperature() < 0.15f;
    }

    /** Better Particles: extra crit and magic sparks when a hit lands. */
    static void onHit(MinecraftClient client, LivingEntity target) {
        if (!VConfig.enabled(Module.BETTER_PARTICLES) || client.world == null) return;
        int amount = (int) VConfig.get(Module.BETTER_PARTICLES, "amount");
        ParticleEffect crit = particle("minecraft:crit");
        ParticleEffect magic = particle("minecraft:enchanted_hit");
        ThreadLocalRandom random = ThreadLocalRandom.current();
        double y = target.getY() + target.getHeight() * 0.6;
        for (int i = 0; i < amount * 6; i++) {
            ParticleEffect effect = i % 2 == 0 ? crit : magic;
            if (effect == null) continue;
            client.world.addParticleClient(effect, target.getX() + (random.nextDouble() - 0.5) * target.getWidth(),
                    y + (random.nextDouble() - 0.5) * target.getHeight() * 0.5,
                    target.getZ() + (random.nextDouble() - 0.5) * target.getWidth(),
                    (random.nextDouble() - 0.5) * 0.6, random.nextDouble() * 0.4, (random.nextDouble() - 0.5) * 0.6);
        }
    }

    // ---- Custom Sky -------------------------------------------------------------------------------------

    /** Draws aurora bands and/or extra stars over the night sky, following where you look. */
    static void renderSky(DrawContext context, MinecraftClient client) {
        if (!VConfig.enabled(Module.CUSTOM_SKY) || client.player == null || !overworld(client)) return;
        float night = night(client);
        if (night <= 0 || !client.world.isSkyVisible(client.player.getBlockPos().up())) return;
        var camera = client.gameRenderer.getCamera();
        double yaw = Math.toRadians(camera.getYaw());
        double pitchUp = -Math.toRadians(camera.getPitch());
        int width = client.getWindow().getScaledWidth();
        int height = client.getWindow().getScaledHeight();
        double focal = 1.0 / Math.tan(Math.toRadians(70) / 2);
        double strength = VConfig.get(Module.CUSTOM_SKY, "strength") / 100.0 * night;
        int style = VConfig.integer(Module.CUSTOM_SKY, "style");
        double seconds = (System.nanoTime() / 1.0E9);
        if (style == 0 || style == 2) aurora(context, width, height, yaw, pitchUp, focal, strength, seconds);
        if (style == 1 || style == 2) stars(context, width, height, yaw, pitchUp, focal, strength, seconds);
    }

    private static int screenY(int height, double elevation, double pitchUp, double focal) {
        double angle = elevation - pitchUp;
        if (angle <= -Math.PI / 2 + 0.05 || angle >= Math.PI / 2 - 0.05) return Integer.MIN_VALUE;
        return (int) Math.round(height / 2.0 - Math.tan(angle) * focal * height / 2.0);
    }

    private static void aurora(DrawContext context, int width, int height, double yaw, double pitchUp, double focal,
                               double strength, double seconds) {
        int horizon = screenY(height, 0, pitchUp, focal);
        double aspect = width / (double) height;
        int[] colors = {0x7CF29A, 0x55FFD0, 0x9A7CFF};
        for (int band = 0; band < 2; band++) {
            double elevation = Math.toRadians(28 + band * 9);
            int base = screenY(height, elevation, pitchUp, focal);
            if (base == Integer.MIN_VALUE) continue;
            for (int x = 0; x < width; x += 8) {
                // Angle of this screen column, so the aurora stays put in the sky as you turn.
                double azimuth = yaw + Math.atan((x - width / 2.0) / (width / 2.0) * aspect / focal);
                double wave = Math.sin(azimuth * 3 + seconds * 0.35 + band) * 0.5 + Math.sin(azimuth * 7.3 - seconds * 0.2) * 0.3;
                double glow = Math.max(0, Math.sin(azimuth * 2 + band * 1.7 + seconds * 0.1)) * 0.7 + 0.3;
                int top = base + (int) (wave * 14) - 22;
                int bottom = base + (int) (wave * 14) + 10;
                if (horizon != Integer.MIN_VALUE) bottom = Math.min(bottom, horizon);
                if (bottom <= top || bottom < 0 || top > height) continue;
                int alpha = (int) (70 * strength * glow);
                if (alpha < 3) continue;
                context.fillGradient(x, top, x + 8, bottom, (alpha / 4) << 24 | colors[band], alpha << 24 | colors[band]);
            }
        }
    }

    private static void stars(DrawContext context, int width, int height, double yaw, double pitchUp, double focal,
                              double strength, double seconds) {
        double aspect = width / (double) height;
        for (double[] star : STARS) {
            double relative = Math.atan2(Math.sin(star[0] - yaw), Math.cos(star[0] - yaw));
            if (Math.abs(relative) > 1.2) continue;
            int y = screenY(height, star[1], pitchUp, focal);
            if (y == Integer.MIN_VALUE || y < 0 || y > height) continue;
            int x = (int) Math.round(width / 2.0 + Math.tan(relative) * focal / aspect * width / 2.0);
            if (x < 0 || x >= width) continue;
            double twinkle = 0.55 + 0.45 * Math.sin(seconds * (1.5 + star[2] * 2) + star[2] * 40);
            int alpha = (int) (220 * strength * twinkle);
            int size = star[2] > 0.85 ? 2 : 1;
            context.fill(x, y, x + size, y + size, alpha << 24 | 0xDFF6FF);
        }
    }
}
