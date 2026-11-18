package com.vclient.mod.cosmetics;

import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Anime-inspired cape art, painted in code like the other hand-drawn capes (nothing to download). The designs are
 * original pixel art in the style of a few well-known looks: an orange martial-arts gi with a blue sash, a golden
 * flaring aura, a blue energy beam, a silver aura, cherry-blossom petals, red battle flames and a green winged cloak.
 *
 * <p>A cape's visible back is 10x16 pixels; {@code u} runs 0..10 across and {@code v} 0..16 down. {@code time} is
 * the position in the loop, 0..1, and every animated type repeats exactly when it wraps, so the loop is seamless.
 * This class has no Minecraft dependencies, so the art can be previewed on its own.
 */
final class AnimeCapeArt {
    private static final Map<String, double[][]> PETALS = new ConcurrentHashMap<>();
    private static final int NOISE_PERIOD = 8;

    private AnimeCapeArt() {}

    static boolean handles(String type) {
        return switch (type) {
            case "goku_gi", "goku_ssj", "kamehameha", "ultra_instinct", "sakura_drift", "sakura_fall",
                 "shonen_flame", "scout_cloak" -> true;
            default -> false;
        };
    }

    /** The colour (0..255 per channel) of this cape pixel. */
    static double[] color(String type, double u, double v, double time, long seed) {
        return switch (type) {
            case "goku_gi" -> gi(u, v);
            case "goku_ssj" -> flames(u, v, time, new double[][]{{150, 40, 0}, {255, 140, 10}, {255, 215, 40}, {255, 255, 210}},
                    new double[]{255, 190, 30}, true);
            case "kamehameha" -> beam(u, v, time, seed);
            case "ultra_instinct" -> silver(u, v, time);
            case "sakura_drift" -> sakura(u, v, 0, false, seed);
            case "sakura_fall" -> sakura(u, v, time, true, seed);
            case "shonen_flame" -> flames(u, v, time, new double[][]{{40, 0, 8}, {170, 8, 20}, {255, 90, 20}, {255, 215, 120}},
                    new double[]{255, 70, 20}, false);
            default -> cloak(u, v);
        };
    }

    // ---- orange gi ---------------------------------------------------------------------------------

    private static double[] gi(double u, double v) {
        double[] color = mix(new double[]{255, 156, 28}, new double[]{222, 86, 0}, v / 16.0);
        // Cloth folds: soft darker vertical creases that bend a little.
        double fold = 0.5 + 0.5 * Math.sin(u * 2.3 + noise(u * 0.7, v * 0.45) * 2.6);
        scale(color, 0.86 + 0.14 * fold);
        scale(color, 0.97 + 0.06 * noise(u * 5.0, v * 5.0));

        // Blue undershirt collar at the top.
        double collar = clamp((1.9 - v) * 2.4);
        over(color, new double[]{26, 58, 168}, collar);
        over(color, new double[]{70, 110, 220}, collar * clamp(1 - Math.abs(v - 0.5) * 2) * 0.35);

        // Blue sash with a highlight along its top edge and a knot on the left hip.
        double sash = clamp((v - 9.7) * 7) * clamp((11.5 - v) * 7);
        over(color, new double[]{28, 54, 158}, sash);
        over(color, new double[]{80, 120, 230}, sash * clamp(1 - Math.abs(v - 10.0) * 3.2) * 0.55);
        double knot = Math.exp(-(sq(u - 2.0) / 1.2 + sq(v - 11.4) / 1.6));
        over(color, new double[]{20, 44, 140}, knot * 0.9);

        // A white circular crest between the shoulders with a simple brush-stroke mark inside it.
        double dist = Math.hypot(u - 5, v - 5.6);
        double ring = clamp(1 - Math.abs(dist - 2.25) / 0.34);
        over(color, new double[]{252, 248, 238}, ring);
        double inside = clamp((2.0 - dist) * 6);
        over(color, new double[]{255, 190, 90}, inside * 0.35);
        double stroke = Math.max(bar(u, v, 5, 5.6, 0.22, 3.0), bar(u, v, 5, 5.0, 2.7, 0.22));
        double flick = Math.max(bar(u, v, 4.0, 6.7, 0.2, 1.1), bar(u, v, 6.0, 4.0, 0.2, 1.1));
        over(color, new double[]{252, 248, 238}, Math.max(stroke, flick) * inside);
        return color;
    }

    // ---- rising flames (golden aura and red battle flames) ----------------------------------------------

    private static double[] flames(double u, double v, double time, double[][] ramp, double[] spark, boolean glowCentre) {
        double[] color = mix(new double[]{ramp[0][0] * 0.45, ramp[0][1] * 0.45, ramp[0][2] * 0.45}, ramp[0], v / 16.0 * 0.9 + 0.1);
        // Flame field: periodic noise that scrolls upward by exactly one lattice period per loop.
        double n = periodicNoise(u * 1.15, v * 0.42 + time * NOISE_PERIOD) * 0.62
                + periodicNoise(u * 2.3 + 5, v * 0.85 + time * NOISE_PERIOD * 2) * 0.38;
        double rise = Math.pow(clamp((v - 0.5) / 15.5), 0.85);
        double spikes = 0.62 + 0.38 * Math.sin(u * 2.1 + n * 3.0);
        double heat = clamp((n * 1.3 - 0.18) * rise * 1.6 * spikes + rise * 0.28);
        if (heat < 0.5) {
            over(color, ramp[1], heat / 0.5);
        } else if (heat < 0.8) {
            color = mix(ramp[1], ramp[2], (heat - 0.5) / 0.3);
        } else {
            color = mix(ramp[2], ramp[3], (heat - 0.8) / 0.2);
        }
        if (glowCentre) {
            double core = Math.exp(-(sq(u - 5) / 8.0 + sq(v - 15.5) / 26.0));
            over(color, ramp[3], core * 0.55);
        }
        // Sparks drifting up the cape; each makes one trip per loop.
        Random random = new Random(7);
        for (int i = 0; i < 12; i++) {
            double x = 0.8 + random.nextDouble() * 8.4;
            double start = random.nextDouble() * 16;
            int trips = 1 + random.nextInt(2);
            double y = Math.floorMod((int) Math.round((start - time * 16 * trips) * 1000), 16000) / 1000.0;
            double sway = Math.sin((time * trips + i * 0.37) * Math.PI * 2) * 0.5;
            double d = Math.hypot(u - (x + sway), v - y);
            if (d < 0.5) over(color, spark, Math.exp(-sq(d / 0.2)) * (0.45 + 0.55 * clamp(y / 16.0)));
        }
        return color;
    }

    // ---- blue energy beam --------------------------------------------------------------------------

    private static double[] beam(double u, double v, double time, long seed) {
        double[] color = mix(new double[]{4, 12, 44}, new double[]{8, 52, 128}, v / 16.0);
        double t = time * Math.PI * 2;
        double width = 1.15 + 0.5 * Math.sin(v / 8.0 * Math.PI * 2 - t);
        double d = Math.abs(u - 5);
        double glow = Math.exp(-sq(d / (width * 2.4)));
        over(color, new double[]{40, 140, 255}, glow * 0.65);
        double core = Math.exp(-sq(d / width));
        over(color, new double[]{190, 235, 255}, core * 0.85);
        over(color, new double[]{255, 255, 255}, Math.exp(-sq(d / (width * 0.45))) * 0.9);
        // Rings travelling down the beam.
        double ring = 0.5 + 0.5 * Math.sin(v / 2.0 * Math.PI * 2 - 2 * t);
        over(color, new double[]{150, 210, 255}, Math.pow(ring, 6) * glow * 0.55);
        // Crackling arcs around it.
        double arc = periodicNoise(u * 1.6 + 3, v * 0.7 + time * NOISE_PERIOD);
        over(color, new double[]{200, 235, 255}, clamp(1 - Math.abs(arc - 0.5) * 22) * clamp(1 - d / 4.8) * 0.7);
        return color;
    }

    // ---- silver aura -------------------------------------------------------------------------------

    private static double[] silver(double u, double v, double time) {
        double t = time * Math.PI * 2;
        double[] color = mix(new double[]{206, 220, 240}, new double[]{104, 126, 168}, v / 16.0);
        double streak = periodicNoise(u * 1.5, v * 0.38 + time * NOISE_PERIOD);
        over(color, new double[]{255, 255, 255}, clamp((streak - 0.5) * 2.4) * 0.65);
        over(color, new double[]{60, 80, 140}, clamp((0.45 - streak) * 2.2) * 0.35);
        double edge = Math.exp(-sq(Math.min(u, 10 - u) / 1.6));
        over(color, new double[]{120, 100, 255}, edge * (0.5 + 0.2 * Math.sin(t)));
        double sheen = Math.exp(-sq((u - 5) / 1.4)) * (0.5 + 0.5 * Math.sin(v / 4.0 * Math.PI * 2 - t));
        over(color, new double[]{235, 240, 255}, sheen * 0.3);
        return color;
    }

    // ---- cherry blossom ----------------------------------------------------------------------------

    private static double[] sakura(double u, double v, double time, boolean falling, long seed) {
        double[] color = mix(new double[]{255, 205, 224}, new double[]{255, 238, 245}, v / 16.0);
        over(color, new double[]{255, 170, 205}, clamp((noise(u * 0.5, v * 0.5) - 0.5) * 2.2) * 0.4);
        if (!falling) {
            // A dark branch across the top with a few blossoms on it.
            double branchV = 1.5 + u * 0.22 + (noise(u * 0.9, 3) - 0.5) * 0.7;
            over(color, new double[]{84, 46, 46}, clamp(1 - Math.abs(v - branchV) / 0.36));
            double twig = Math.abs(v - (branchV + 1.4 + (u - 6) * 0.5)) < 0.18 && u > 6 ? 1 : 0;
            over(color, new double[]{84, 46, 46}, twig);
        }
        double[][] petals = PETALS.computeIfAbsent("petals-" + seed + falling, key -> {
            Random random = new Random(seed ^ 0x51CAD);
            double[][] list = new double[falling ? 26 : 30][];
            for (int i = 0; i < list.length; i++) {
                list[i] = new double[]{random.nextDouble() * 10, random.nextDouble() * 16, 0.52 + random.nextDouble() * 0.3,
                        random.nextDouble() * Math.PI, 1 + random.nextInt(2), random.nextDouble()};
            }
            return list;
        });
        for (double[] petal : petals) {
            double x = petal[0];
            double y = petal[1];
            double angle = petal[3];
            if (falling) {
                y = Math.floorMod((int) Math.round((petal[1] + time * 16 * petal[4]) * 1000), 16000) / 1000.0;
                x += Math.sin((time * petal[4] + petal[5]) * Math.PI * 2) * 0.9;
                angle += time * Math.PI * 2 * petal[4];
            }
            double dx = u - x;
            double dy = v - y;
            double rx = dx * Math.cos(angle) + dy * Math.sin(angle);
            double ry = -dx * Math.sin(angle) + dy * Math.cos(angle);
            double shape = sq(rx / petal[2]) + sq(ry / (petal[2] * 0.62));
            if (shape < 1.4) {
                double edge = clamp((1.0 - shape) * 2.2 + 0.25);
                over(color, mix(new double[]{255, 150, 190}, new double[]{255, 225, 236}, clamp(0.5 + rx / petal[2] * 0.5)), edge);
            }
        }
        return color;
    }

    // ---- winged cloak ------------------------------------------------------------------------------

    private static double[] cloak(double u, double v) {
        double[] color = mix(new double[]{92, 110, 66}, new double[]{44, 56, 34}, v / 16.0);
        double fold = 0.5 + 0.5 * Math.sin(u * 2.0 + noise(u * 0.6, v * 0.4) * 2.8);
        scale(color, 0.84 + 0.16 * fold);
        scale(color, 0.97 + 0.06 * noise(u * 5.0, v * 5.0));
        // Hood shadow and a stitched hem.
        over(color, new double[]{20, 28, 16}, clamp((2.2 - v) * 0.5) * 0.6);
        over(color, new double[]{26, 34, 20}, clamp((v - 14.6) * 2.5) * 0.7);
        // Two crossed feathered wings: a white one on the left, a blue one on the right.
        for (int side = -1; side <= 1; side += 2) {
            double dx = (u - 5) * side;
            if (dx < 0.35 || dx > 4.3) continue;
            double top = 7.8 - (dx - 0.3) * 1.13;
            double bottom = 8.4 - (dx - 0.3) * 0.72 + 0.4 * Math.abs(Math.sin(dx * 3.4));
            double inside = clamp((v - top) * 6) * clamp((bottom - v) * 6);
            if (inside <= 0) continue;
            double[] feather = side < 0 ? new double[]{244, 244, 248} : new double[]{44, 96, 190};
            double shade = 0.86 + 0.14 * Math.sin(dx * 6.0);
            over(color, new double[]{feather[0] * shade, feather[1] * shade, feather[2] * shade}, inside);
        }
        return color;
    }

    // ---- small helpers -----------------------------------------------------------------------------

    /** A filled rectangle centred at (cx, cy), half-sizes hw and hh, soft at its edge. */
    private static double bar(double u, double v, double cx, double cy, double hw, double hh) {
        return clamp((hw - Math.abs(u - cx)) * 7) * clamp((hh - Math.abs(v - cy)) * 7);
    }

    /** Value noise whose y direction repeats every {@link #NOISE_PERIOD} lattice cells. */
    private static double periodicNoise(double x, double y) {
        int ix = (int) Math.floor(x);
        int iy = (int) Math.floor(y);
        double fx = smooth(x - ix);
        double fy = smooth(y - iy);
        double top = lerp(hash(ix, Math.floorMod(iy, NOISE_PERIOD)), hash(ix + 1, Math.floorMod(iy, NOISE_PERIOD)), fx);
        double bottom = lerp(hash(ix, Math.floorMod(iy + 1, NOISE_PERIOD)), hash(ix + 1, Math.floorMod(iy + 1, NOISE_PERIOD)), fx);
        return lerp(top, bottom, fy);
    }

    private static double noise(double x, double y) {
        int ix = (int) Math.floor(x);
        int iy = (int) Math.floor(y);
        double fx = smooth(x - ix);
        double fy = smooth(y - iy);
        return lerp(lerp(hash(ix, iy), hash(ix + 1, iy), fx), lerp(hash(ix, iy + 1), hash(ix + 1, iy + 1), fx), fy);
    }

    private static double hash(int x, int y) {
        int h = x * 374761393 + y * 668265263;
        h = (h ^ (h >>> 13)) * 1274126177;
        return ((h ^ (h >>> 16)) & 0xFFFF) / 65535.0;
    }

    private static double smooth(double t) {
        double c = clamp(t);
        return c * c * (3 - 2 * c);
    }

    private static double clamp(double value) {
        return Math.max(0, Math.min(1, value));
    }

    private static double sq(double value) {
        return value * value;
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    private static double[] mix(double[] a, double[] b, double t) {
        double c = clamp(t);
        return new double[]{lerp(a[0], b[0], c), lerp(a[1], b[1], c), lerp(a[2], b[2], c)};
    }

    private static void scale(double[] color, double factor) {
        color[0] *= factor;
        color[1] *= factor;
        color[2] *= factor;
    }

    private static void over(double[] destination, double[] source, double alpha) {
        double a = clamp(alpha);
        destination[0] = destination[0] * (1 - a) + source[0] * a;
        destination[1] = destination[1] * (1 - a) + source[1] * a;
        destination[2] = destination[2] * (1 - a) + source[2] * a;
    }
}
