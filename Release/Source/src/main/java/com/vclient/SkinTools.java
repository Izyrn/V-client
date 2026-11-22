package com.vclient;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.stream.Stream;

/** Skin helpers: validation, a flat preview, saved presets, a random skin generator and Microsoft upload. */
final class SkinTools {
    private SkinTools() {}

    static Path presetsFolder() {
        return AppInfo.dataDir().resolve("skin-presets");
    }

    // ---- validation ------------------------------------------------------------------------------

    /** Null when the image is a usable Minecraft skin, otherwise the reason it is not. */
    static String validate(BufferedImage image) {
        if (image == null) return "That file is not a readable image.";
        int w = image.getWidth();
        int h = image.getHeight();
        boolean ok = (w == 64 && (h == 64 || h == 32)) || (w == 128 && (h == 128 || h == 64));
        return ok ? null : "Skins must be 64x64 or 64x32 pixels (this one is " + w + "x" + h + ").";
    }

    static BufferedImage read(Path file) throws IOException {
        if (Files.size(file) > 1_000_000) throw new IOException("That file is too large to be a skin.");
        return ImageIO.read(file.toFile());
    }

    static byte[] png(BufferedImage image) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    // ---- preview ---------------------------------------------------------------------------------

    /** A flat front view (head, body, arms, legs with their outer layers) scaled up with hard pixel edges. */
    static BufferedImage frontPreview(BufferedImage skin, int scale) {
        int unit = Math.max(1, skin.getWidth() / 64);
        boolean modern = skin.getHeight() >= 64 * unit;
        BufferedImage flat = new BufferedImage(16, 32, BufferedImage.TYPE_INT_ARGB);
        // base layer, then the overlay layer on top
        part(skin, unit, flat, 8, 8, 8, 8, 4, 0);          // head
        part(skin, unit, flat, 20, 20, 8, 12, 4, 8);       // body
        part(skin, unit, flat, 44, 20, 4, 12, 0, 8);       // right arm (viewer's left)
        part(skin, unit, flat, modern ? 36 : 44, modern ? 52 : 20, 4, 12, 12, 8, !modern); // left arm
        part(skin, unit, flat, 4, 20, 4, 12, 4, 20);       // right leg
        part(skin, unit, flat, modern ? 20 : 4, modern ? 52 : 20, 4, 12, 8, 20, !modern); // left leg
        part(skin, unit, flat, 40, 8, 8, 8, 4, 0);         // hat layer
        if (modern) {
            part(skin, unit, flat, 20, 36, 8, 12, 4, 8);   // jacket
            part(skin, unit, flat, 44, 36, 4, 12, 0, 8);   // right sleeve
            part(skin, unit, flat, 52, 52, 4, 12, 12, 8);  // left sleeve
            part(skin, unit, flat, 4, 36, 4, 12, 4, 20);   // right pants
            part(skin, unit, flat, 4, 52, 4, 12, 8, 20);   // left pants
        }
        BufferedImage scaled = new BufferedImage(16 * scale, 32 * scale, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < scaled.getHeight(); y++) {
            for (int x = 0; x < scaled.getWidth(); x++) scaled.setRGB(x, y, flat.getRGB(x / scale, y / scale));
        }
        return scaled;
    }

    private static void part(BufferedImage skin, int unit, BufferedImage target, int sx, int sy, int w, int h,
                             int dx, int dy) {
        part(skin, unit, target, sx, sy, w, h, dx, dy, false);
    }

    private static void part(BufferedImage skin, int unit, BufferedImage target, int sx, int sy, int w, int h,
                             int dx, int dy, boolean mirror) {
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int px = (mirror ? w - 1 - x : x);
                int ix = (sx + px) * unit;
                int iy = (sy + y) * unit;
                if (ix >= skin.getWidth() || iy >= skin.getHeight()) continue;
                int argb = skin.getRGB(ix, iy);
                if ((argb >>> 24) < 128) continue;
                target.setRGB(dx + x, dy + y, argb | 0xFF000000);
            }
        }
    }

    // ---- random generator --------------------------------------------------------------------------

    private static final int[] SKIN_TONES = {0xF1C27D, 0xE0AC69, 0xC68642, 0x8D5524, 0xFFDBAC, 0x6B4423};
    private static final int[] HAIR = {0x2B1B0E, 0x5A3825, 0xB5651D, 0xE6C068, 0x1B1B1B, 0xB22222, 0xEEEEEE, 0x3366CC};
    private static final int[] CLOTHES = {0x3A6EA5, 0xC0392B, 0x27AE60, 0x8E44AD, 0xE67E22, 0x16A085, 0x2C3E50,
            0xF1C40F, 0xD35400, 0x7F8C8D, 0xEC407A};
    private static final int[] PANTS = {0x2C3E50, 0x1F2A44, 0x4B3621, 0x333333, 0x5D6D7E, 0x6C3483};

    static BufferedImage randomSkin(Random random) {
        BufferedImage skin = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        int tone = pick(random, SKIN_TONES);
        int hair = pick(random, HAIR);
        int shirt = pick(random, CLOTHES);
        int sleeve = random.nextBoolean() ? shirt : tone;
        int pants = pick(random, PANTS);
        int shoes = darker(pants, 0.55);
        int eyes = pick(random, new int[]{0x2E6FBF, 0x3B7A3B, 0x5A3A1E, 0x222222});

        fill(skin, 0, 0, 32, 16, tone, random, 6);          // head sheet
        fill(skin, 8, 0, 16, 8, hair, random, 8);           // head top + bottom row start
        fill(skin, 0, 8, 32, 2, hair, random, 8);           // hair along the top of all faces
        fill(skin, 24, 8, 8, 8, hair, random, 8);           // back of the head
        fill(skin, 0, 8, 2, 4, hair, random, 8);            // sideburns / hair sides
        fill(skin, 16, 8, 2, 4, hair, random, 8);
        // face: eyes and mouth on the front (8,8)
        skin.setRGB(9, 12, 0xFFFFFFFF);
        skin.setRGB(10, 12, 0xFF000000 | eyes);
        skin.setRGB(13, 12, 0xFF000000 | eyes);
        skin.setRGB(14, 12, 0xFFFFFFFF);
        skin.setRGB(11, 14, 0xFF000000 | darker(tone, 0.7));
        skin.setRGB(12, 14, 0xFF000000 | darker(tone, 0.7));

        fill(skin, 16, 16, 24, 16, shirt, random, 5);       // torso sheet
        fill(skin, 40, 16, 16, 16, sleeve, random, 5);      // right arm sheet
        fill(skin, 40, 28, 16, 4, tone, random, 5);         // hands
        fill(skin, 0, 16, 16, 16, pants, random, 5);        // right leg sheet
        fill(skin, 0, 28, 16, 4, shoes, random, 4);
        fill(skin, 16, 48, 16, 16, pants, random, 5);       // left leg sheet
        fill(skin, 16, 60, 16, 4, shoes, random, 4);
        fill(skin, 32, 48, 16, 16, sleeve, random, 5);      // left arm sheet
        fill(skin, 32, 60, 16, 4, tone, random, 5);
        // a belt line makes the outfit read better
        fill(skin, 20, 31, 8, 1, darker(pants, 0.8), random, 0);
        return skin;
    }

    private static int pick(Random random, int[] options) {
        return options[random.nextInt(options.length)];
    }

    private static int darker(int rgb, double factor) {
        int r = (int) (((rgb >> 16) & 0xFF) * factor);
        int g = (int) (((rgb >> 8) & 0xFF) * factor);
        int b = (int) ((rgb & 0xFF) * factor);
        return (r << 16) | (g << 8) | b;
    }

    private static void fill(BufferedImage image, int x, int y, int w, int h, int rgb, Random random, int noise) {
        for (int j = y; j < y + h; j++) {
            for (int i = x; i < x + w; i++) {
                int delta = noise == 0 ? 0 : random.nextInt(noise * 2 + 1) - noise;
                int r = Math.clamp(((rgb >> 16) & 0xFF) + delta, 0, 255);
                int g = Math.clamp(((rgb >> 8) & 0xFF) + delta, 0, 255);
                int b = Math.clamp((rgb & 0xFF) + delta, 0, 255);
                image.setRGB(i, j, 0xFF000000 | (r << 16) | (g << 8) | b);
            }
        }
    }

    // ---- presets -----------------------------------------------------------------------------------

    static String safeName(String name) {
        String cleaned = name == null ? "" : name.replaceAll("[^A-Za-z0-9 _.-]", "").strip();
        if (cleaned.length() > 40) cleaned = cleaned.substring(0, 40).strip();
        return cleaned.isEmpty() ? "Skin" : cleaned;
    }

    static List<Path> presets(Path folder) {
        if (!Files.isDirectory(folder)) return List.of();
        try (Stream<Path> files = Files.list(folder)) {
            return files.filter(file -> file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".png"))
                    .sorted().toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    static Path savePreset(Path folder, String name, BufferedImage skin) throws IOException {
        String problem = validate(skin);
        if (problem != null) throw new IOException(problem);
        Files.createDirectories(folder);
        String base = safeName(name);
        Path target = folder.resolve(base + ".png");
        for (int n = 2; Files.exists(target); n++) target = folder.resolve(base + " (" + n + ").png");
        Files.write(target, png(skin));
        return target;
    }

    // ---- upload ----------------------------------------------------------------------------------

    /** Uploads a skin to the signed-in Microsoft account. Throws with Mojang's message when it refuses. */
    static void uploadMicrosoft(String accessToken, BufferedImage skin, boolean slim)
            throws IOException, InterruptedException {
        String problem = validate(skin);
        if (problem != null) throw new IOException(problem);
        if (accessToken == null || accessToken.isBlank()) throw new IOException("Sign in with Microsoft again first.");
        String boundary = "----vclient" + Long.toHexString(System.nanoTime());
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"variant\"\r\n\r\n"
                + (slim ? "slim" : "classic") + "\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"skin.png\"\r\n"
                + "Content-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(png(skin));
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        HttpClient client = PlatformTls.httpClientBuilder(Duration.ofSeconds(15)).build();
        HttpResponse<String> response = client.send(HttpRequest.newBuilder(
                        URI.create("https://api.minecraftservices.com/minecraft/profile/skins"))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + accessToken)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build(),
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IOException(response.statusCode() == 401
                    ? "Microsoft rejected the sign-in. Sign in again in Settings."
                    : "Minecraft services refused the skin (HTTP " + response.statusCode() + ").");
        }
    }

    static BufferedImage decode(byte[] bytes) throws IOException {
        return ImageIO.read(new ByteArrayInputStream(bytes));
    }

    static List<String> describe(BufferedImage skin) {
        List<String> lines = new ArrayList<>();
        lines.add(skin.getWidth() + "x" + skin.getHeight() + (skin.getHeight() == 32 ? " (old layout)" : ""));
        return lines;
    }
}
