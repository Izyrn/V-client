package com.vclient.mod;

import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;

/**
 * Minimap: a north-up top-down map of the blocks around you (map colours, shaded by height), with other players,
 * your waypoints and an arrow for you. In the Nether it maps the layer you stand on instead of the roof.
 *
 * Built to cost almost nothing: a few rows are refreshed per tick from the game's own heightmap (the whole map every
 * 16 ticks), and the finished map is one small texture drawn as a single quad.
 */
public final class Minimap {
    static final int SAMPLES = 64;
    private static final int ROWS_PER_TICK = 4;
    private static final Identifier TEXTURE_ID = Identifier.of("vclient", "minimap");

    private static final int[] WORK = new int[SAMPLES * SAMPLES];
    private static final int[] HEIGHTS = new int[SAMPLES * SAMPLES];
    private static NativeImageBackedTexture texture;
    private static Heightmap.Type surfaceType;
    private static boolean ready;
    private static int nextRow;
    private static int centerX;
    private static int centerZ;

    private Minimap() {}

    static int size() {
        return (int) VConfig.get(Module.MINIMAP, "size");
    }

    public static void tick(MinecraftClient client) {
        if (!VConfig.enabled(Module.MINIMAP) || client.player == null || client.world == null) return;
        ClientWorld world = client.world;
        if (surfaceType == null) {
            for (Heightmap.Type type : Heightmap.Type.values()) {
                if ("WORLD_SURFACE".equals(type.asString())) surfaceType = type;
            }
            if (surfaceType == null) return;
        }
        if (nextRow == 0) {
            centerX = client.player.getBlockX();
            centerZ = client.player.getBlockZ();
        }
        int zoom = (int) VConfig.get(Module.MINIMAP, "zoom");
        boolean ceiling = "the_nether".equals(world.getRegistryKey().getValue().getPath());
        int playerY = client.player.getBlockY();
        BlockPos.Mutable pos = new BlockPos.Mutable();
        int last = Math.min(SAMPLES, nextRow + ROWS_PER_TICK);
        for (int sz = nextRow; sz < last; sz++) {
            for (int sx = 0; sx < SAMPLES; sx++) {
                int x = centerX + (sx - SAMPLES / 2) * zoom;
                int z = centerZ + (sz - SAMPLES / 2) * zoom;
                int y;
                if (ceiling) {
                    pos.set(x, playerY + 1, z);
                    for (int i = 0; i < 16 && world.getBlockState(pos).isAir(); i++) pos.move(0, -1, 0);
                    y = pos.getY();
                } else {
                    y = world.getTopY(surfaceType, x, z) - 1;
                    pos.set(x, y, z);
                }
                BlockState state = world.getBlockState(pos);
                int color = state.isAir() ? 0x0A0F18 : state.getMapColor(world, pos).color;
                WORK[sz * SAMPLES + sx] = color == 0 ? 0x0A0F18 : color;
                HEIGHTS[sz * SAMPLES + sx] = y;
            }
        }
        nextRow = last;
        if (nextRow < SAMPLES) return;
        nextRow = 0;
        // Shade by the height step to the north, like paper maps: slopes read as hills.
        for (int i = SAMPLES * SAMPLES - 1; i >= SAMPLES; i--) {
            int step = HEIGHTS[i] - HEIGHTS[i - SAMPLES];
            if (step != 0) WORK[i] = scale(WORK[i], step > 0 ? 1.12f : 0.82f);
        }
        upload(client);
    }

    /** Copies the finished map into one small GPU texture (ticks run on the render thread, so this is safe). */
    private static void upload(MinecraftClient client) {
        if (texture == null) {
            texture = new NativeImageBackedTexture(() -> "vyrn minimap", SAMPLES, SAMPLES, true);
            client.getTextureManager().registerTexture(TEXTURE_ID, texture);
        }
        NativeImage image = texture.getImage();
        if (image == null) return;
        for (int z = 0; z < SAMPLES; z++) {
            for (int x = 0; x < SAMPLES; x++) image.setColorArgb(x, z, 0xFF000000 | WORK[z * SAMPLES + x]);
        }
        texture.upload();
        ready = true;
    }

    private static int scale(int rgb, float factor) {
        int r = Math.min(255, (int) (((rgb >> 16) & 0xFF) * factor));
        int g = Math.min(255, (int) (((rgb >> 8) & 0xFF) * factor));
        int b = Math.min(255, (int) ((rgb & 0xFF) * factor));
        return r << 16 | g << 8 | b;
    }

    static void draw(DrawContext context, MinecraftClient client, boolean background, boolean editing) {
        int size = size();
        if (background) Hud.frostPanel(context, Module.MINIMAP, size, size + 12);
        if (!ready || texture == null) {
            context.fill(0, 0, size, size, 0xFF2E5A3A);
            if (!editing) context.drawText(client.textRenderer, "Mapping...", 4, size / 2 - 4, Hud.MUTED, true);
        } else {
            context.drawTexture(RenderPipelines.GUI_TEXTURED, TEXTURE_ID, 0, 0, 0, 0, size, size, size, size);
        }
        float cell = size / (float) SAMPLES;
        int zoom = (int) VConfig.get(Module.MINIMAP, "zoom");
        int center = size / 2;
        if (client.player != null && client.world != null) {
            if (VConfig.bool(Module.MINIMAP, "waypoints")) {
                for (Waypoints.Waypoint point : Waypoints.current(client)) {
                    int[] p = toMap(point.x() - client.player.getX(), point.z() - client.player.getZ(), zoom, cell, size, center);
                    context.fill(p[0] - 2, p[1] - 2, p[0] + 2, p[1] + 2, 0xFF000000);
                    context.fill(p[0] - 1, p[1] - 1, p[0] + 1, p[1] + 1, point.color());
                }
            }
            if (VConfig.bool(Module.MINIMAP, "players")) {
                int shown = 0;
                for (AbstractClientPlayerEntity other : client.world.getPlayers()) {
                    if (other == client.player || other.isInvisible() || shown >= 40) continue;
                    double dx = other.getX() - client.player.getX();
                    double dz = other.getZ() - client.player.getZ();
                    if (Math.abs(dx) > SAMPLES / 2.0 * zoom || Math.abs(dz) > SAMPLES / 2.0 * zoom) continue;
                    int[] p = toMap(dx, dz, zoom, cell, size, center);
                    context.fill(p[0] - 1, p[1] - 1, p[0] + 2, p[1] + 2, 0xFFFFFFFF);
                    shown++;
                }
            }
            arrow(context, center, center, client.player.getYaw());
        }
        context.drawText(client.textRenderer, "N", center - 2, 2, 0xFFFF6B6B, true);
        String coords = client.player == null ? "0 0 0"
                : client.player.getBlockX() + " " + client.player.getBlockY() + " " + client.player.getBlockZ();
        context.drawText(client.textRenderer, coords, (size - client.textRenderer.getWidth(coords)) / 2, size + 2, Hud.TEXT, true);
    }

    private static int[] toMap(double dx, double dz, int zoom, float cell, int size, int center) {
        int x = center + Math.round((float) (dx / zoom * cell));
        int y = center + Math.round((float) (dz / zoom * cell));
        int half = size / 2 - 2;
        return new int[]{Math.clamp(x, center - half, center + half), Math.clamp(y, center - half, center + half)};
    }

    /** A small arrow pointing where you face (yaw 0 = south = down on a north-up map). */
    private static void arrow(DrawContext context, int cx, int cy, float yaw) {
        double angle = Math.toRadians(yaw);
        double fx = -Math.sin(angle), fy = Math.cos(angle);
        for (int i = 0; i <= 5; i += 1) {
            int px = cx + (int) Math.round(fx * i);
            int py = cy + (int) Math.round(fy * i);
            int half = i < 2 ? 2 : 1;
            context.fill(px - half, py - half, px + half, py + half, i == 5 ? 0xFFFF6B6B : 0xFFFFFFFF);
        }
    }
}
