package com.vclient.mod.cosmetics;

import net.minecraft.client.texture.NativeImage;

import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Hand-drawn cape textures, painted in code so they need no download: the Ramadan capes (still and animated),
 * the Aurora and the Starfield. A cape texture is a 64x32 sheet whose visible back is the 10x16 area at (1,1);
 * the art is drawn at {@code scale = width / 64} times that size (256x128 gives a crisp 40x64 pixel cape), and the
 * rest of the sheet gets the matching background so an elytra made from the same texture looks right too.
 *
 * Every animated type is periodic in {@code frame / frames}, so the loop is seamless.
 */
final class CapeArt {
    private static final double GOLD_R = 0xE6, GOLD_G = 0xBD, GOLD_B = 0x59;

    /** A star or dust speck: position in cape pixels, size, animation phase and speed multiplier. */
    private record Spot(double x, double y, double size, double phase, int speed) {}

    /** Spot lists depend only on the seed, so they are built once instead of once per pixel. */
    private static final Map<String, Spot[]> SPOTS = new ConcurrentHashMap<>();

    private CapeArt() {}

    private static Spot[] ramadanStars(long seed) {
        return SPOTS.computeIfAbsent("ramadan-stars-" + seed, key -> {
            Random random = new Random(seed);
            Spot[] spots = new Spot[16];
            for (int i = 0; i < spots.length; i++) {
                double x = 0.8 + random.nextDouble() * 8.4;
                double y = 0.8 + random.nextDouble() * 8.6;
                double size = 0.18 + random.nextDouble() * 0.22;
                spots[i] = new Spot(x, y, size, random.nextDouble(), 1);
            }
            return spots;
        });
    }

    private static Spot[] dust(long seed) {
        return SPOTS.computeIfAbsent("dust-" + seed, key -> {
            Random random = new Random(seed ^ 0x5DEECE66DL);
            Spot[] spots = new Spot[12];
            for (int i = 0; i < spots.length; i++) {
                double x = 1 + random.nextDouble() * 8;
                spots[i] = new Spot(x, 0, 0.17, i, 1 + random.nextInt(2));
            }
            return spots;
        });
    }

    private static Spot[] sky(long seed, int count) {
        return SPOTS.computeIfAbsent("sky-" + seed + "-" + count, key -> {
            Random random = new Random(seed ^ 0x9E3779B97F4A7C15L);
            Spot[] spots = new Spot[count];
            for (int i = 0; i < count; i++) {
                double x = random.nextDouble() * 10;
                double y = random.nextDouble() * 16;
                double size = 0.10 + random.nextDouble() * random.nextDouble() * 0.32;
                double phase = random.nextDouble();
                spots[i] = new Spot(x, y, size, phase, 1 + random.nextInt(2));
            }
            return spots;
        });
    }

    static boolean handles(String type) {
        return switch (type) {
            case "ramadan", "ramadan_animated", "aurora", "starfield" -> true;
            case "goku_gi", "goku_ssj", "kamehameha", "ultra_instinct", "sakura_drift", "sakura_fall", "shonen_flame",
                 "scout_cloak" -> true;
            default -> false;
        };
    }

    static NativeImage paint(String type, String seedText, int width, int height, int frame, int frames) {
        double time = frames <= 1 ? 0 : frame / (double) frames;
        double scale = width / 64.0;
        long seed = seedText.hashCode() * 31L + type.hashCode();
        NativeImage image = new NativeImage(width, height, true);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                // Position on the visible cape face, in cape pixels: 0..10 across, 0..16 down.
                double u = x / scale - 1;
                double v = y / scale - 1;
                boolean onFace = u >= 0 && u < 10 && v >= 0 && v < 16;
                // Off the face the sheet repeats the face so elytra and the cape's edges stay on theme.
                if (!onFace) {
                    u = Math.floorMod((int) Math.floor(u * 4), 40) / 4.0;
                    v = Math.floorMod((int) Math.floor(v * 4), 64) / 4.0;
                }
                double[] rgb = AnimeCapeArt.handles(type) ? AnimeCapeArt.color(type, u, v, time, seed) : switch (type) {
                    case "ramadan" -> ramadan(u, v, 0, false, seed);
                    case "ramadan_animated" -> ramadan(u, v, time, true, seed);
                    case "aurora" -> aurora(u, v, time, seed);
                    default -> starfield(u, v, time, seed);
                };
                image.setColorArgb(x, y, argb(rgb));
            }
        }
        return image;
    }

    // ---- Ramadan -----------------------------------------------------------------------------------

    private static double[] ramadan(double u, double v, double time, boolean animated, long seed) {
        // Night blue at the top fading to deep emerald.
        double[] color = mix(new double[]{7, 24, 52}, new double[]{9, 74, 58}, smooth(v / 16.0));

        // Soft light from the crescent.
        double glowBreath = animated ? 0.85 + 0.15 * Math.sin(time * Math.PI * 2) : 1;
        double glow = Math.exp(-(sq(u - 4.6) + sq(v - 4.6)) / 14.0) * 0.42 * glowBreath;
        over(color, new double[]{255, 214, 120}, glow * 0.55);

        // Islamic geometric lattice: a square and the same square turned 45 degrees, repeated.
        double tile = 2.5;
        double tu = Math.floorMod((int) Math.floor((u + 1.25) * 64), (int) (tile * 64)) / 64.0 - tile / 2;
        double tv = Math.floorMod((int) Math.floor((v + 1.25) * 64), (int) (tile * 64)) / 64.0 - tile / 2;
        double lattice = Math.min(edge(Math.max(Math.abs(tu), Math.abs(tv)), 1.0, 0.07),
                edge((Math.abs(tu) + Math.abs(tv)) / 1.4142, 1.0, 0.07));
        over(color, new double[]{GOLD_R, GOLD_G, GOLD_B}, (1 - lattice) * 0.17);

        // Stars.
        double twinkleBase = animated ? time : 0;
        for (Spot spot : ramadanStars(seed)) {
            double d = Math.hypot(u - spot.x, v - spot.y);
            if (d >= spot.size * 2.6) continue;
            double twinkle = animated ? 0.55 + 0.45 * Math.sin((twinkleBase + spot.phase) * Math.PI * 2) : 0.9;
            over(color, new double[]{255, 245, 210}, Math.exp(-sq(d / spot.size)) * twinkle);
        }

        // Crescent moon: a gold disc with a smaller disc cut out, opening to the right.
        double outer = Math.hypot(u - 4.6, v - 4.7);
        double inner = Math.hypot(u - 5.75, v - 4.35);
        double moon = clamp((2.75 - outer) * 6) * clamp((inner - 2.3) * 6);
        if (moon > 0) {
            double shade = clamp((u - 2.0) / 5.0);
            over(color, mix(new double[]{255, 240, 180}, new double[]{214, 160, 60}, shade), moon);
        }
        // The star nestled in the crescent's opening.
        double star = starShape(u - 6.7, v - 4.55, 0.85, 0.36);
        if (star > 0) over(color, new double[]{255, 236, 160}, star * (animated ? 0.8 + 0.2 * Math.sin(time * Math.PI * 4) : 1));

        // Two lanterns hanging from golden cords.
        double flicker = animated ? 0.78 + 0.22 * Math.sin(time * Math.PI * 2 + 1.1) : 1;
        double flicker2 = animated ? 0.78 + 0.22 * Math.sin(time * Math.PI * 2 + 3.4) : 1;
        lantern(color, u, v, 2.6, 10.6, flicker);
        lantern(color, u, v, 7.4, 10.0, flicker2);

        // Gold frame with a thin inner line.
        double frameDistance = Math.min(Math.min(u, 10 - u), Math.min(v, 16 - v));
        double frame = Math.max(clamp((0.55 - frameDistance) * 5), clamp(1 - Math.abs(frameDistance - 0.9) * 9) * 0.7);
        double sweep = animated ? 0.65 + 0.35 * Math.sin((u * 0.35 + v * 0.35 - time * 2) * Math.PI * 2) : 0.85;
        over(color, mix(new double[]{255, 226, 140}, new double[]{GOLD_R * 0.8, GOLD_G * 0.7, GOLD_B * 0.5}, 1 - sweep), frame);

        // Drifting gold dust that rises through the cape.
        if (animated) {
            Spot[] specks = dust(seed);
            for (int i = 0; i < specks.length; i++) {
                double speed = specks[i].speed;
                double rise = (i / (double) specks.length + time * speed) % 1.0;
                double dy = 16 - rise * 16;
                double d = Math.hypot(u - specks[i].x - 0.25 * Math.sin((time * speed + i) * Math.PI * 2), v - dy);
                if (d < 0.5) over(color, new double[]{255, 224, 150}, Math.exp(-sq(d / 0.17)) * 0.75);
            }
        }
        return color;
    }

    /** A fanous lantern with a cord, cap, glowing body and a little glass-pane shading. */
    private static void lantern(double[] color, double u, double v, double cx, double top, double light) {
        // Cord.
        if (Math.abs(u - cx) < 0.07 && v > top - 2.4 && v < top) over(color, new double[]{GOLD_R, GOLD_G, GOLD_B}, 0.9);
        // Cap (a small triangle).
        double capHeight = 0.8;
        if (v >= top && v < top + capHeight) {
            double half = 0.25 + 0.65 * (v - top) / capHeight;
            if (Math.abs(u - cx) < half) over(color, new double[]{170, 112, 30}, 1);
        }
        // Body: tall hexagon-like shape.
        double bodyTop = top + capHeight;
        double bodyHeight = 2.9;
        if (v >= bodyTop && v < bodyTop + bodyHeight) {
            double t = (v - bodyTop) / bodyHeight;
            double half = 0.95 * (1 - 0.35 * Math.pow(Math.abs(t - 0.5) * 2, 2));
            double d = Math.abs(u - cx);
            if (d < half) {
                double pane = 0.5 + 0.5 * Math.cos(d / half * Math.PI * 2.4);
                double warm = (1 - d / half) * 0.65 + 0.35;
                double[] glass = mix(new double[]{150, 80, 12}, new double[]{255, 205, 90}, warm * light);
                over(color, mix(glass, new double[]{255, 238, 170}, pane * 0.18 * light), 0.96);
                // Glow outside the lantern.
            }
            if (Math.abs(d - half) < 0.07) over(color, new double[]{120, 78, 20}, 0.95);
        }
        // Base.
        if (v >= bodyTop + bodyHeight && v < bodyTop + bodyHeight + 0.35 && Math.abs(u - cx) < 0.7) {
            over(color, new double[]{170, 112, 30}, 1);
        }
        // Soft halo.
        double halo = Math.exp(-(sq(u - cx) + sq(v - (bodyTop + bodyHeight / 2)) * 0.6) / 1.6) * 0.30 * light;
        over(color, new double[]{255, 190, 80}, halo);
    }

    // ---- Aurora ------------------------------------------------------------------------------------

    private static double[] aurora(double u, double v, double time, long seed) {
        double nx = u / 10.0;
        double ny = v / 16.0;
        double[] color = mix(new double[]{3, 8, 24}, new double[]{8, 30, 58}, smooth(ny));
        // Faint stars in the dark sky.
        over(color, new double[]{210, 225, 255}, starDust(u, v, time, seed, 28));

        // Two overlapping curtains that sway on integer harmonics of the loop, so it repeats exactly.
        for (int curtain = 0; curtain < 2; curtain++) {
            double shift = curtain * 0.37;
            double center = 0.40 + 0.09 * curtain
                    + 0.10 * Math.sin((nx * 1.3 + shift) * Math.PI * 2 + time * Math.PI * 2)
                    + 0.05 * Math.sin((nx * 3.1 - shift) * Math.PI * 2 - time * Math.PI * 4);
            double thickness = 0.20 + 0.04 * Math.sin((nx * 2 + time) * Math.PI * 2);
            double band = Math.exp(-sq((ny - center) / thickness));
            // Vertical rays inside the curtain.
            double rays = 0.62 + 0.38 * Math.sin((nx * 11 + curtain * 2) * Math.PI * 2 + Math.sin((nx * 3 + time) * Math.PI * 2) * 2);
            double fade = 1 - smooth(clamp((ny - center) / 0.55));
            double strength = clamp(band * rays * (0.55 + 0.45 * fade) * (curtain == 0 ? 1.0 : 0.7));
            double hue = clamp((ny - (center - 0.25)) / 0.6);
            double[] ray = hue < 0.5
                    ? mix(new double[]{70, 255, 160}, new double[]{40, 220, 255}, hue * 2)
                    : mix(new double[]{40, 220, 255}, new double[]{165, 95, 255}, (hue - 0.5) * 2);
            over(color, ray, strength * 0.9);
        }
        return color;
    }

    // ---- Starfield ---------------------------------------------------------------------------------

    private static double[] starfield(double u, double v, double time, long seed) {
        double ny = v / 16.0;
        double[] color = mix(new double[]{3, 3, 14}, new double[]{12, 6, 30}, smooth(ny));
        // A slow nebula from layered value noise.
        double cloud = noise(u * 0.55 + 3, v * 0.55) * 0.6 + noise(u * 1.3, v * 1.3 + 7) * 0.4;
        double cloud2 = noise(u * 0.7 + 11, v * 0.7 + 5);
        over(color, new double[]{90, 40, 170}, clamp((cloud - 0.45) * 2.2) * 0.45);
        over(color, new double[]{30, 110, 190}, clamp((cloud2 - 0.5) * 2.4) * 0.32);

        over(color, new double[]{255, 255, 255}, starDust(u, v, time, seed, 70));

        // One shooting star crosses the cape each loop.
        double headT = time * 1.4 - 0.2;
        if (headT > 0 && headT < 1) {
            double hx = -1 + headT * 13;
            double hy = -2 + headT * 17;
            for (int i = 0; i < 14; i++) {
                double tx = hx - i * 0.32;
                double ty = hy - i * 0.42;
                double d = Math.hypot(u - tx, v - ty);
                if (d < 0.55) over(color, new double[]{235, 245, 255}, Math.exp(-sq(d / (0.16 - i * 0.008))) * (1 - i / 14.0));
            }
        }
        return color;
    }

    // ---- shared drawing helpers --------------------------------------------------------------------

    /** Brightness of a field of twinkling stars at this point, 0..1. */
    private static double starDust(double u, double v, double time, long seed, int count) {
        double best = 0;
        for (Spot spot : sky(seed, count)) {
            double d = Math.hypot(u - spot.x, v - spot.y);
            if (d >= spot.size * 3) continue;
            double twinkle = 0.45 + 0.55 * Math.sin((time * spot.speed + spot.phase) * Math.PI * 2);
            best = Math.max(best, Math.exp(-sq(d / spot.size)) * Math.max(0.15, twinkle));
        }
        return best;
    }

    /** A five-pointed star as coverage 0..1: {@code outer} and {@code inner} are the point and notch radii. */
    private static double starShape(double dx, double dy, double outer, double inner) {
        double angle = Math.atan2(dy, dx) + Math.PI / 2;
        double sector = Math.PI / 5;
        double a = Math.floorMod((int) Math.floor(angle / sector * 1000), 2000) / 1000.0;
        double t = a <= 1 ? a : 2 - a;
        double radius = inner + (outer - inner) * (1 - t);
        return clamp((radius - Math.hypot(dx, dy)) * 7);
    }

    /** Smooth value noise in 0..1. */
    private static double noise(double x, double y) {
        int ix = (int) Math.floor(x);
        int iy = (int) Math.floor(y);
        double fx = smooth(x - ix);
        double fy = smooth(y - iy);
        double top = lerp(hash(ix, iy), hash(ix + 1, iy), fx);
        double bottom = lerp(hash(ix, iy + 1), hash(ix + 1, iy + 1), fx);
        return lerp(top, bottom, fy);
    }

    private static double hash(int x, int y) {
        int h = x * 374761393 + y * 668265263;
        h = (h ^ (h >>> 13)) * 1274126177;
        return ((h ^ (h >>> 16)) & 0xFFFF) / 65535.0;
    }

    /** 1 well inside a line of the given half-width, 0 well outside (anti-aliased). Used for the lattice. */
    private static double edge(double value, double target, double width) {
        return clamp(Math.abs(value - target) / width);
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

    /** Blends {@code source} onto {@code destination} in place with the given opacity. */
    private static void over(double[] destination, double[] source, double alpha) {
        double a = clamp(alpha);
        destination[0] = destination[0] * (1 - a) + source[0] * a;
        destination[1] = destination[1] * (1 - a) + source[1] * a;
        destination[2] = destination[2] * (1 - a) + source[2] * a;
    }

    private static int argb(double[] rgb) {
        int r = (int) Math.max(0, Math.min(255, Math.round(rgb[0])));
        int g = (int) Math.max(0, Math.min(255, Math.round(rgb[1])));
        int b = (int) Math.max(0, Math.min(255, Math.round(rgb[2])));
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }
}
