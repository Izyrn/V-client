package com.vclient.mod;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.player.ItemCooldownManager;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Cooldown Timer, Custom Crosshair, Streamer Mode and the Combat Notifier checks that run every tick. */
public final class Extras {
    private static final String[][] COOLDOWN_ITEMS = {{"ender_pearl", "Pearl"}, {"chorus_fruit", "Chorus"},
            {"wind_charge", "Wind Charge"}, {"shield", "Shield"}, {"goat_horn", "Goat Horn"}, {"mace", "Mace"},
            {"trident", "Trident"}, {"firework_rocket", "Rocket"}};
    private static final EquipmentSlot[] ARMOR = {EquipmentSlot.byName("head"), EquipmentSlot.byName("chest"),
            EquipmentSlot.byName("legs"), EquipmentSlot.byName("feet")};
    private static final String[] ARMOR_NAMES = {"helmet", "chestplate", "leggings", "boots"};
    private static final ItemStack[] LAST_ARMOR = new ItemStack[4];
    private static ItemStack[] cooldownStacks;
    private static int lastTotems = -1;

    private Extras() {}

    public static boolean streamer() {
        return VConfig.enabled(Module.STREAMER_MODE);
    }

    // ---- combat notifier ----------------------------------------------------------------------------------

    public static void tick(MinecraftClient client) {
        if (client.player == null) {
            lastTotems = -1;
            return;
        }
        if (!VConfig.enabled(Module.ISLAND) || !VConfig.bool(Module.ISLAND, "combat")) return;
        int totems = VClientMod.countItem(client, "totem_of_undying");
        if (lastTotems > totems && !client.player.isDead() && client.player.hurtTime > 0) {
            Island.notify("✦", "Totem popped! " + totems + " left", 0xFFFFD166);
        }
        lastTotems = totems;
        for (int i = 0; i < 4; i++) {
            ItemStack now = client.player.getEquippedStack(ARMOR[i]);
            ItemStack before = LAST_ARMOR[i];
            if (before != null && !before.isEmpty() && now.isEmpty() && before.isDamageable()
                    && before.getMaxDamage() - before.getDamage() <= Math.max(3, before.getMaxDamage() / 40)) {
                Island.notify("⚠", "Your " + ARMOR_NAMES[i] + " broke!", 0xFFFF6B6B);
            }
            LAST_ARMOR[i] = now.copy();
        }
    }

    // ---- cooldown timer ----------------------------------------------------------------------------------

    static List<String[]> cooldownLines(MinecraftClient client, boolean editing) {
        List<String[]> lines = new ArrayList<>();
        if (cooldownStacks == null) {
            cooldownStacks = new ItemStack[COOLDOWN_ITEMS.length];
            for (int i = 0; i < COOLDOWN_ITEMS.length; i++) {
                Item item = Registries.ITEM.get(Identifier.of("minecraft", COOLDOWN_ITEMS[i][0]));
                cooldownStacks[i] = new ItemStack(item);
            }
        }
        if (client.player != null) {
            ItemCooldownManager cooldowns = client.player.getItemCooldownManager();
            for (int i = 0; i < COOLDOWN_ITEMS.length; i++) {
                ItemStack stack = cooldownStacks[i];
                if (stack.isEmpty() || !cooldowns.isCoolingDown(stack)) continue;
                float left = cooldowns.getCooldownProgress(stack, 0);
                lines.add(new String[]{COOLDOWN_ITEMS[i][1], Integer.toString(Math.round(left * 100))});
            }
        }
        if (lines.isEmpty() && editing) lines.add(new String[]{"Pearl", "60"});
        return lines;
    }

    static boolean cooldownsActive(MinecraftClient client) {
        return !cooldownLines(client, false).isEmpty();
    }

    static void drawCooldowns(DrawContext context, MinecraftClient client, boolean background, int label, boolean editing,
                              int width, int height) {
        if (background) Hud.frostPanel(context, Module.COOLDOWNS, width, height);
        int row = 0;
        for (String[] line : cooldownLines(client, editing)) {
            int y = 3 + row * 12;
            context.drawText(client.textRenderer, line[0], 4, y, label, true);
            float left = Integer.parseInt(line[1]) / 100f;
            int barX = 68;
            int barWidth = width - barX - 4;
            context.fill(barX, y + 2, barX + barWidth, y + 6, 0x80000000);
            context.fill(barX, y + 2, barX + Math.round(barWidth * left), y + 6, 0xFFFF9F43);
            row++;
        }
    }

    // ---- crosshair ----------------------------------------------------------------------------------------

    /** Draws the custom crosshair; returns true when it drew (vanilla's is then skipped). */
    public static boolean drawCrosshair(DrawContext context) {
        if (!VConfig.enabled(Module.CROSSHAIR)) return false;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.options.hudHidden || !client.options.getPerspective().isFirstPerson()) return true;
        int cx = client.getWindow().getScaledWidth() / 2;
        int cy = client.getWindow().getScaledHeight() / 2;
        int style = VConfig.integer(Module.CROSSHAIR, "style");
        int size = (int) VConfig.get(Module.CROSSHAIR, "size");
        int gap = (int) VConfig.get(Module.CROSSHAIR, "gap");
        int thick = (int) VConfig.get(Module.CROSSHAIR, "thickness");
        int color = CommonSettings.COLORS[Math.clamp(VConfig.integer(Module.CROSSHAIR, "color"), 0, CommonSettings.COLORS.length - 1)];
        boolean outline = VConfig.bool(Module.CROSSHAIR, "outline");
        int half = thick / 2;
        if (style == 0 || style == 3) {
            bar(context, cx - half, cy - gap - size, thick, size, color, outline);
            bar(context, cx - half, cy + gap + 1, thick, size, color, outline);
            bar(context, cx - gap - size, cy - half, size, thick, color, outline);
            bar(context, cx + gap + 1, cy - half, size, thick, color, outline);
        }
        if (style == 1 || style == 3) bar(context, cx - half, cy - half, thick, thick, color, outline);
        if (style == 2) {
            int radius = gap + size / 2 + 1;
            for (int angle = 0; angle < 360; angle += 10) {
                int px = cx + (int) Math.round(Math.cos(Math.toRadians(angle)) * radius);
                int py = cy + (int) Math.round(Math.sin(Math.toRadians(angle)) * radius);
                context.fill(px, py, px + 1, py + 1, color);
            }
            context.fill(cx, cy, cx + 1, cy + 1, color);
        }
        return true;
    }

    private static void bar(DrawContext context, int x, int y, int w, int h, int color, boolean outline) {
        if (outline) context.fill(x - 1, y - 1, x + w + 1, y + h + 1, 0xC0000000);
        context.fill(x, y, x + w, y + h, color);
    }

    static String hidden(String value) {
        return streamer() ? "hidden" : value;
    }

    static String percent(int value) {
        return String.format(Locale.ROOT, "%d%%", value);
    }
}
