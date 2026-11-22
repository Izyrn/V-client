package com.vclient.mod;

import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;

/**
 * Minimap: a north-up top-down map of the blocks around you (map colours, shaded by height), with other players,
 * your waypoints and an arrow for you. In the Nether it maps the layer you stand on instead of the roof.
 */
public final class Minimap {
    static final int SAMPLES = 64;
    private static final int[] COLORS = new int[SAMPLES * SAMPLES];
    private static int lastX = Integer.MIN_VALUE;
    private static int lastZ;
    private static int ticks;

    private Minimap() {}

    static int size() {
        return (int) VConfig.get(Module.MINIMAP, "size");
    }

    public static void tick(MinecraftClient client) {
        if (!VConfig.enabled(Module.MINIMAP) || client.player == null || client.world == null) return;
        if (++ticks % 4 != 0) return;
        int zoom = (int) VConfig.get(Module.MINIMAP, "zoom");
        BlockPos center = client.player.getBlockPos();
        ClientWorld world = client.world;
        boolean ceiling = "the_nether".equals(world.getRegistryKey().getValue().getPath());
        int[] heights = new int[SAMPLES * SAMPLES];
        BlockPos.Mutable pos = new BlockPos.Mutable();
        for (int sz = 0; sz < SAMPLES; sz++) {
            for (int sx = 0; sx < SAMPLES; sx++) {
                int x = center.getX() + (sx - SAMPLES / 2) * zoom;
                int z = center.getZ() + (sz - SAMPLES / 2) * zoom;
                int y = ceiling ? center.getY() + 1 : surface(world, x, z, center.getY());
                pos.set(x, y, z);
                BlockState state = world.getBlockState(pos);
                int steps = 0;
                while (state.isAir() && steps++ < (ceiling ? 24 : 64)) {
                    pos.move(0, -1, 0);
                    state = world.getBlockState(pos);
                }
                int color = state.isAir() ? 0x0A0F18 : state.getMapColor(world, pos).color;
                if (color == 0) color = 0x0A0F18;
                COLORS[sz * SAMPLES + sx] = color;
                heights[sz * SAMPLES + sx] = pos.getY();
            }
        }
        // Shade by the height step to the north, like paper maps: slopes read as hills.
        for (int sz = 1; sz < SAMPLES; sz++) {
            for (int sx = 0; sx < SAMPLES; sx++) {
                int i = sz * SAMPLES + sx;
                int step = heights[i] - heights[i - SAMPLES];
                float shade = step > 0 ? 1.12f : step < 0 ? 0.82f : 1f;
                COLORS[i] = scale(COLORS[i], shade);
            }
        }
        lastX = center.getX();
        lastZ = center.getZ();
    }

    /** Highest non-air block in the column near the player (scans down from a little above them). */
    private static int surface(ClientWorld world, int x, int z, int playerY) {
        BlockPos.Mutable pos = new BlockPos.Mutable(x, playerY + 24, z);
        for (int i = 0; i < 24 && world.getBlockState(pos).isAir(); i++) pos.move(0, -1, 0);
        if (!world.getBlockState(pos).isAir()) {
            // Climb back up to the top of a column we started inside (under a tree, a roof).
            while (!world.getBlockState(pos.up()).isAir() && pos.getY() < playerY + 40) pos.move(0, 1, 0);
        }
        return pos.getY();
    }

    private static int scale(int rgb, float factor) {
        int r = Math.min(255, (int) (((rgb >> 16) & 0xFF) * factor));
        int g = Math.min(255, (int) (((rgb >> 8) & 0xFF) * factor));
        int b = Math.min(255, (int) ((rgb & 0xFF) * factor));
        return r << 16 | g << 8 | b;
    }

    static void draw(DrawContext context, MinecraftClient client, boolean background, boolean editing) {
        int size = size();
        int cell = Math.max(1, size / SAMPLES);
        int mapSize = cell * SAMPLES;
        int offset = (size - mapSize) / 2;
        if (background) Hud.frostPanel(context, Module.MINIMAP, size, size + 12);
        if (lastX == Integer.MIN_VALUE && !editing) {
            context.drawText(client.textRenderer, "Mapping...", 6, size / 2 - 4, Hud.MUTED, true);
        } else {
            for (int sz = 0; sz < SAMPLES; sz++) {
                for (int sx = 0; sx < SAMPLES; sx++) {
                    int color = lastX == Integer.MIN_VALUE ? ((sx + sz) % 7 == 0 ? 0x3A6B44 : 0x4E8A55) : COLORS[sz * SAMPLES + sx];
                    int x = offset + sx * cell;
                    int y = offset + sz * cell;
                    context.fill(x, y, x + cell, y + cell, 0xFF000000 | color);
                }
            }
        }
        int zoom = (int) VConfig.get(Module.MINIMAP, "zoom");
        int centerX = offset + mapSize / 2;
        int centerY = offset + mapSize / 2;
        if (client.player != null && client.world != null) {
            if (VConfig.bool(Module.MINIMAP, "waypoints")) {
                for (Waypoints.Waypoint point : Waypoints.current(client)) {
                    int[] p = toMap(point.x() - client.player.getX(), point.z() - client.player.getZ(), zoom, cell, mapSize, centerX, centerY);
                    context.fill(p[0] - 2, p[1] - 2, p[0] + 2, p[1] + 2, 0xFF000000);
                    context.fill(p[0] - 1, p[1] - 1, p[0] + 1, p[1] + 1, point.color());
                }
            }
            if (VConfig.bool(Module.MINIMAP, "players")) {
                for (AbstractClientPlayerEntity other : client.world.getPlayers()) {
                    if (other == client.player || other.isInvisible()) continue;
                    double dx = other.getX() - client.player.getX();
                    double dz = other.getZ() - client.player.getZ();
                    if (Math.abs(dx) > SAMPLES / 2.0 * zoom || Math.abs(dz) > SAMPLES / 2.0 * zoom) continue;
                    int[] p = toMap(dx, dz, zoom, cell, mapSize, centerX, centerY);
                    context.fill(p[0] - 2, p[1] - 2, p[0] + 2, p[1] + 2, 0xFF000000);
                    context.fill(p[0] - 1, p[1] - 1, p[0] + 1, p[1] + 1, 0xFFFFFFFF);
                }
            }
            arrow(context, centerX, centerY, client.player.getYaw());
        }
        context.fill(offset, offset, offset + mapSize, offset + 1, 0x667CEBFF);
        context.drawText(client.textRenderer, "N", centerX - 2, offset + 2, 0xFFFF6B6B, true);
        String coords = client.player == null ? "0 0 0" : client.player.getBlockX() + " " + client.player.getBlockY() + " " + client.player.getBlockZ();
        context.drawText(client.textRenderer, coords, (size - client.textRenderer.getWidth(coords)) / 2, size + 2, Hud.TEXT, true);
    }

    private static int[] toMap(double dx, double dz, int zoom, int cell, int mapSize, int centerX, int centerY) {
        int x = centerX + (int) Math.round(dx / zoom * cell);
        int y = centerY + (int) Math.round(dz / zoom * cell);
        int half = mapSize / 2 - 2;
        return new int[]{Math.clamp(x, centerX - half, centerX + half), Math.clamp(y, centerY - half, centerY + half)};
    }

    /** A small arrow pointing where you face (yaw 0 = south = down on a north-up map). */
    private static void arrow(DrawContext context, int cx, int cy, float yaw) {
        double angle = Math.toRadians(yaw);
        double fx = -Math.sin(angle), fy = Math.cos(angle);
        for (int i = -1; i <= 5; i++) {
            int px = cx + (int) Math.round(fx * i);
            int py = cy + (int) Math.round(fy * i);
            int half = i < 2 ? 2 : 1;
            context.fill(px - half, py - half, px + half, py + half, i == 5 ? 0xFFFF6B6B : 0xFFFFFFFF);
        }
        context.fill(cx - 1, cy - 1, cx + 1, cy + 1, 0xFF00CFFF);
    }
}
