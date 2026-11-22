package com.vclient.mod;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreboardEntry;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.scoreboard.Team;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Custom Scoreboard (frosted, optional numbers), Custom Tab List (ping in milliseconds), Heal Helper and the hand
 * position used by Better Hand. Called from the HUD and from small hooks; every hook falls back to vanilla drawing if
 * it cannot apply.
 */
public final class Interface {
    private Interface() {}

    // ---- scoreboard -------------------------------------------------------------------------------------

    /** Returns true when the custom scoreboard drew (so vanilla should not). */
    public static boolean drawScoreboard(DrawContext context, ScoreboardObjective objective) {
        if (!VConfig.enabled(Module.CUSTOM_SCOREBOARD) || objective == null) return false;
        MinecraftClient client = MinecraftClient.getInstance();
        TextRenderer font = client.textRenderer;
        Scoreboard scoreboard = objective.getScoreboard();
        List<ScoreboardEntry> entries = new ArrayList<>();
        for (ScoreboardEntry entry : scoreboard.getScoreboardEntries(objective)) if (!entry.hidden()) entries.add(entry);
        entries.sort(Comparator.comparingInt(ScoreboardEntry::comp_2128).reversed()
                .thenComparing(ScoreboardEntry::comp_2127, String.CASE_INSENSITIVE_ORDER));
        if (entries.size() > 15) entries = entries.subList(0, 15);
        boolean numbers = VConfig.bool(Module.CUSTOM_SCOREBOARD, "numbers");
        List<Text> lines = new ArrayList<>();
        List<String> scores = new ArrayList<>();
        int widest = font.getWidth(objective.getDisplayName());
        for (ScoreboardEntry entry : entries) {
            Text name = Team.decorateName(scoreboard.getScoreHolderTeam(entry.comp_2127()), entry.name());
            String score = numbers ? Integer.toString(entry.comp_2128()) : "";
            lines.add(name);
            scores.add(score);
            widest = Math.max(widest, font.getWidth(name) + (score.isEmpty() ? 0 : font.getWidth(score) + 8));
        }
        float scale = (float) (VConfig.get(Module.CUSTOM_SCOREBOARD, "scale") / 100.0);
        int width = widest + 8;
        int height = 13 + lines.size() * 10 + 3;
        int screenWidth = client.getWindow().getScaledWidth();
        int screenHeight = client.getWindow().getScaledHeight();
        int x = Math.round(screenWidth / scale) - width - 3;
        int y = Math.round(screenHeight / scale / 2f) - height / 2;
        context.getMatrices().pushMatrix();
        context.getMatrices().scale(scale, scale);
        try {
            if (VConfig.bool(Module.CUSTOM_SCOREBOARD, "background")) {
                context.getMatrices().pushMatrix();
                context.getMatrices().translate(x, y);
                Hud.frostPanel(context, Module.CUSTOM_SCOREBOARD, width, height);
                context.getMatrices().popMatrix();
            }
            Text title = objective.getDisplayName();
            context.drawText(font, title, x + (width - font.getWidth(title)) / 2, y + 3, 0xFFFFFFFF, true);
            context.fill(x + 4, y + 12, x + width - 4, y + 13, 0x667CEBFF);
            for (int i = 0; i < lines.size(); i++) {
                int lineY = y + 15 + i * 10;
                context.drawText(font, lines.get(i), x + 4, lineY, 0xFFFFFFFF, true);
                if (!scores.get(i).isEmpty()) {
                    context.drawText(font, scores.get(i), x + width - 4 - font.getWidth(scores.get(i)), lineY, 0xFFFF6B6B, true);
                }
            }
        } finally {
            context.getMatrices().popMatrix();
        }
        return true;
    }

    // ---- tab list ---------------------------------------------------------------------------------------

    /** Returns true when the ping was drawn as a number (so vanilla's bars are skipped). */
    public static boolean drawPing(DrawContext context, int width, int x, int y, PlayerListEntry entry) {
        if (!VConfig.enabled(Module.CUSTOM_TAB) || entry == null) return false;
        TextRenderer font = MinecraftClient.getInstance().textRenderer;
        int latency = entry.getLatency();
        String text = latency <= 0 ? "-" : Integer.toString(latency);
        int color = latency <= 0 ? 0xFFA9D2E8 : latency < 80 ? 0xFF7CF29A : latency < 160 ? 0xFFFFD166 : latency < 300 ? 0xFFFF9F43 : 0xFFFF6B6B;
        context.getMatrices().pushMatrix();
        float scale = 0.75f;
        context.getMatrices().translate(x + width - 1 - font.getWidth(text) * scale, y + 1.5f);
        context.getMatrices().scale(scale, scale);
        context.drawText(font, text, 0, 0, color, true);
        context.getMatrices().popMatrix();
        return true;
    }

    // ---- better hand --------------------------------------------------------------------------------------

    public static boolean handEnabled() {
        return VConfig.enabled(Module.BETTER_HAND);
    }

    public static void applyHand(MatrixStack matrices) {
        matrices.translate(VConfig.get(Module.BETTER_HAND, "x") / 100.0, VConfig.get(Module.BETTER_HAND, "y") / 100.0,
                VConfig.get(Module.BETTER_HAND, "z") / 100.0);
        float scale = (float) (VConfig.get(Module.BETTER_HAND, "size") / 100.0);
        matrices.scale(scale, scale, scale);
    }

    // ---- heal helper --------------------------------------------------------------------------------------

    static String[] healText(MinecraftClient client) {
        if (client.player == null) return new String[]{"Heal: ", "-"};
        float health = client.player.getHealth() + client.player.getAbsorptionAmount();
        int gapples = VClientMod.countItem(client, "golden_apple") + VClientMod.countItem(client, "enchanted_golden_apple");
        int totems = VClientMod.countItem(client, "totem_of_undying");
        int potions = VClientMod.countItem(client, "potion") + VClientMod.countItem(client, "splash_potion");
        int food = client.player.getHungerManager().getFoodLevel();
        return new String[]{"❤ ", String.format(Locale.ROOT, "%.1f  🍗 %d  Gap %d  Pot %d  Totem %d",
                health / 2f, food, gapples, potions, totems)};
    }

    /** Big flashing warning above the hotbar when health is low. */
    static void renderHealWarning(DrawContext context, MinecraftClient client) {
        if (!VConfig.enabled(Module.HEAL_HELPER) || client.player == null || client.player.isDead()) return;
        if (!VConfig.bool(Module.HEAL_HELPER, "warning")) return;
        float hearts = client.player.getHealth() / 2f;
        if (hearts > VConfig.get(Module.HEAL_HELPER, "warnAt")) return;
        boolean on = (System.currentTimeMillis() / 400) % 2 == 0;
        if (!on) return;
        String text = "LOW HEALTH - " + (VClientMod.countItem(client, "golden_apple") > 0 ? "eat a golden apple!" : "heal up!");
        TextRenderer font = client.textRenderer;
        int width = client.getWindow().getScaledWidth();
        int y = client.getWindow().getScaledHeight() - 72;
        int textWidth = font.getWidth(text);
        context.fill(width / 2 - textWidth / 2 - 4, y - 3, width / 2 + textWidth / 2 + 4, y + 10, 0xA0330A12);
        context.drawText(font, text, width / 2 - textWidth / 2, y, 0xFFFF6B6B, true);
    }
}
