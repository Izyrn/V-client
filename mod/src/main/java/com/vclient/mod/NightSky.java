package com.vclient.mod;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;
import java.util.Random;

/**
 * VYRN's frozen menu backdrop: a polar sky with an aurora, falling snow and glacier ridges.
 *
 * <p>The static layers (sky, aurora, glow, glaciers) are painted once per window size into two half-resolution
 * textures; only the falling snow and a faint aurora shimmer are drawn per frame. Minecraft's GUI renderer compares
 * every element with every other, so thousands of small fills per frame would stall the menu.
 */
public final class NightSky {
    private static final int SNOW_COUNT = 110;
    private static final double[] SNOW_X = new double[SNOW_COUNT];
    private static final double[] SNOW_Y = new double[SNOW_COUNT];
    private static final int[] SNOW_SIZE = new int[SNOW_COUNT];
    private static final double[] SNOW_PHASE = new double[SNOW_COUNT];
    private static final double[] SNOW_SPEED = new double[SNOW_COUNT];
    private static final Identifier BASE_ID = Identifier.of("vclient", "frost_sky_base");
    private static final Identifier HILLS_ID = Identifier.of("vclient", "frost_sky_glaciers");
    private static final int DOWNSCALE = 2;

    private static int bakedWidth = -1;
    private static int bakedHeight = -1;
    private static int textureWidth;
    private static int textureHeight;

    static {
        Random random = new Random(731921);
        for (int index = 0; index < SNOW_COUNT; index++) {
            SNOW_X[index] = random.nextDouble();
            SNOW_Y[index] = random.nextDouble();
            SNOW_SIZE[index] = 1 + random.nextInt(3);
            SNOW_PHASE[index] = random.nextDouble() * Math.PI * 2;
            SNOW_SPEED[index] = 0.012 + SNOW_SIZE[index] * 0.012 + random.nextDouble() * 0.01;
        }
    }

    private NightSky() {}

    public static void render(DrawContext context) {
        MinecraftClient client = MinecraftClient.getInstance();
        int scale = Math.max(1, client.getWindow().getScaleFactor());
        int width = client.getWindow().getScaledWidth() * scale;
        int height = client.getWindow().getScaledHeight() * scale;
        if (width <= 0 || height <= 0) return;
        bake(client, width, height);
        double time = System.nanoTime() / 1_000_000_000.0;
        int pixel = Math.max(1, scale / 2);

        context.getMatrices().pushMatrix();
        context.getMatrices().scale(1.0f / scale, 1.0f / scale);
        try {
            texture(context, BASE_ID, width, height);
            if (VConfig.bool(Module.MENU_BACKGROUND, "comet")) shimmer(context, width, height, time);
            if (VConfig.bool(Module.MENU_BACKGROUND, "twinkle")) snow(context, width, height, time, pixel);
            texture(context, HILLS_ID, width, height);
        } finally {
            context.getMatrices().popMatrix();
        }
    }

    private static void texture(DrawContext context, Identifier id, int width, int height) {
        context.drawTexture(RenderPipelines.GUI_TEXTURED, id, 0, 0, 0, 0, width, height,
                textureWidth, textureHeight, textureWidth, textureHeight);
    }

    /** A slow pulse of green and cyan light across the upper sky: two wide fills, so it costs almost nothing. */
    private static void shimmer(DrawContext context, int width, int height, double time) {
        double pulse = 0.5 + 0.5 * Math.sin(time * 0.45);
        double sway = Math.sin(time * 0.2);
        int bandTop = (int) (height * (0.10 + 0.03 * sway));
        context.fill(0, bandTop, width, bandTop + (int) (height * 0.16), argb(0.018 + 0.03 * pulse, 92, 255, 200));
        context.fill(0, bandTop + (int) (height * 0.05), width, bandTop + (int) (height * 0.22),
                argb(0.014 + 0.026 * (1 - pulse), 0, 207, 255));
    }

    private static void snow(DrawContext context, int width, int height, double time, int pixel) {
        for (int index = 0; index < SNOW_COUNT; index++) {
            double fall = (SNOW_Y[index] + time * SNOW_SPEED[index]) % 1.0;
            double drift = Math.sin(time * 0.6 + SNOW_PHASE[index]) * 0.012 + time * 0.0035;
            int x = (int) ((((SNOW_X[index] + drift) % 1.0) + 1.0) % 1.0 * width);
            int y = (int) (fall * height);
            int size = SNOW_SIZE[index] * pixel;
            context.fill(x, y, x + size, y + size, argb(0.45 + 0.1 * SNOW_SIZE[index], 226, 247, 255));
        }
    }

    /** Paints the static layers into textures whenever the window size changes. */
    private static void bake(MinecraftClient client, int width, int height) {
        if (width == bakedWidth && height == bakedHeight) return;
        bakedWidth = width;
        bakedHeight = height;
        textureWidth = Math.max(1, width / DOWNSCALE);
        textureHeight = Math.max(1, height / DOWNSCALE);

        Canvas base = new Canvas(textureWidth, textureHeight);
        paintSky(base);
        Canvas hills = new Canvas(textureWidth, textureHeight);
        paintGlaciers(hills);
        upload(client, BASE_ID, base);
        upload(client, HILLS_ID, hills);
    }

    private static void upload(MinecraftClient client, Identifier id, Canvas canvas) {
        NativeImage image = new NativeImage(canvas.width, canvas.height, false);
        for (int y = 0; y < canvas.height; y++) {
            for (int x = 0; x < canvas.width; x++) {
                image.setColorArgb(x, y, canvas.pixels[y * canvas.width + x]);
            }
        }
        NativeImageBackedTexture texture = new NativeImageBackedTexture(() -> "VSMP frost sky", image);
        client.getTextureManager().registerTexture(id, texture);
    }

    private static void paintSky(Canvas canvas) {
        int width = canvas.width;
        int height = canvas.height;
        canvas.fill(0, 0, width, height, rgb(7, 19, 33));
        // Deeper blue toward the top, a paler cold haze at the horizon.
        for (int y = 0; y < height; y++) {
            double t = (double) y / height;
            canvas.fill(0, y, width, y + 1, argb(0.30 * (1 - t), 3, 10, 24));
            canvas.fill(0, y, width, y + 1, argb(0.16 * t * t, 60, 150, 190));
        }
        // Aurora ribbons.
        int[][] colours = {{0, 207, 255}, {92, 255, 200}, {124, 235, 255}};
        for (int ribbon = 0; ribbon < colours.length; ribbon++) {
            int[] c = colours[ribbon];
            double base = height * (0.12 + ribbon * 0.085);
            for (int x = 0; x < width; x += 3) {
                double wave = Math.sin(x * 0.0085 + ribbon * 1.7) * height * 0.05
                        + Math.sin(x * 0.022 + ribbon) * height * 0.018;
                double strength = 0.5 + 0.5 * Math.sin(x * 0.0042 + ribbon * 2.3);
                int top = (int) (base + wave);
                int step = (int) Math.max(2, height * 0.03);
                for (int layer = 0; layer < 8; layer++) {
                    double alpha = (0.085 - layer * 0.0098) * (0.45 + strength);
                    if (alpha <= 0) break;
                    int y = top + layer * step;
                    canvas.fill(x, y, x + 3, y + step + 1, argb(Math.min(0.22, alpha), c[0], c[1], c[2]));
                }
            }
        }
        // Cold glow rising from the glaciers.
        for (int layer = 10; layer >= 1; layer--) {
            canvas.circle(width * 0.5, height * 1.04, width * (0.18 + layer * 0.045),
                    argb(0.006 + (10 - layer) * 0.0016, 0, 207, 255));
        }
    }

    private static void paintGlaciers(Canvas canvas) {
        ridge(canvas, argb(0.82, 14, 40, 66),
                new double[]{0, 0.10, 0.19, 0.30, 0.43, 0.56, 0.69, 0.82, 0.92, 1.0},
                new double[]{0.80, 0.70, 0.79, 0.66, 0.80, 0.72, 0.83, 0.68, 0.78, 0.74});
        ridge(canvas, argb(0.90, 8, 26, 46),
                new double[]{0, 0.14, 0.27, 0.41, 0.54, 0.68, 0.80, 0.93, 1.0},
                new double[]{0.91, 0.86, 0.93, 0.84, 0.92, 0.87, 0.93, 0.90, 0.90});
    }

    /** Fills the area under a polyline ridge (x and y as fractions of the canvas). */
    private static void ridge(Canvas canvas, int color, double[] xs, double[] ys) {
        for (int x = 0; x < canvas.width; x++) {
            double fraction = (x + 0.5) / canvas.width;
            int segment = 0;
            while (segment < xs.length - 2 && fraction > xs[segment + 1]) segment++;
            double span = xs[segment + 1] - xs[segment];
            double t = span <= 0 ? 0 : Math.clamp((fraction - xs[segment]) / span, 0, 1);
            int top = (int) (canvas.height * (ys[segment] + (ys[segment + 1] - ys[segment]) * t));
            canvas.fill(x, top, x + 1, canvas.height, color);
            canvas.fill(x, top, x + 1, top + 1, argb(0.35, 190, 240, 255)); // snow-lit edge
        }
    }

    /** A small ARGB software canvas with source-over blending. */
    private static final class Canvas {
        final int width;
        final int height;
        final int[] pixels;

        Canvas(int width, int height) {
            this.width = width;
            this.height = height;
            this.pixels = new int[width * height];
        }

        void fill(int x1, int y1, int x2, int y2, int color) {
            int left = Math.max(0, x1);
            int top = Math.max(0, y1);
            int right = Math.min(width, x2);
            int bottom = Math.min(height, y2);
            for (int y = top; y < bottom; y++) {
                for (int x = left; x < right; x++) blend(y * width + x, color);
            }
        }

        void circle(double centerX, double centerY, double radius, int color) {
            int top = (int) Math.floor(centerY - radius);
            int bottom = (int) Math.ceil(centerY + radius);
            for (int y = top; y < bottom; y++) {
                double middle = y + 0.5 - centerY;
                double half = Math.sqrt(Math.max(0, radius * radius - middle * middle));
                if (half <= 0) continue;
                fill((int) Math.round(centerX - half), y, (int) Math.round(centerX + half), y + 1, color);
            }
        }

        private void blend(int index, int source) {
            double sourceAlpha = (source >>> 24) / 255.0;
            if (sourceAlpha <= 0) return;
            int destination = pixels[index];
            double destinationAlpha = (destination >>> 24) / 255.0;
            double outAlpha = sourceAlpha + destinationAlpha * (1 - sourceAlpha);
            if (outAlpha <= 0) {
                pixels[index] = 0;
                return;
            }
            int red = channel(source >> 16, destination >> 16, sourceAlpha, destinationAlpha, outAlpha);
            int green = channel(source >> 8, destination >> 8, sourceAlpha, destinationAlpha, outAlpha);
            int blue = channel(source, destination, sourceAlpha, destinationAlpha, outAlpha);
            pixels[index] = ((int) Math.round(outAlpha * 255) << 24) | (red << 16) | (green << 8) | blue;
        }

        private static int channel(int source, int destination, double sourceAlpha, double destinationAlpha,
                                   double outAlpha) {
            double value = ((source & 0xFF) * sourceAlpha
                    + (destination & 0xFF) * destinationAlpha * (1 - sourceAlpha)) / outAlpha;
            return (int) Math.round(Math.clamp(value, 0, 255));
        }
    }

    private static int rgb(int red, int green, int blue) {
        return 0xFF000000 | (red << 16) | (green << 8) | blue;
    }

    private static int argb(double opacity, int red, int green, int blue) {
        int alpha = (int) Math.round(Math.clamp(opacity, 0, 1) * 255);
        return (alpha << 24) | (red << 16) | (green << 8) | blue;
    }
}
