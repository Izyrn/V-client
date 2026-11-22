package com.vclient.mod;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Combat and session tracking for the HUD: Target HUD, Combo Counter, Reach Display, Damage Feed, Attack Cooldown and
 * Session Stats. Everything is read from what the client already sees (your swings, the target's health and hurt
 * animation); nothing is sent to the server and nothing changes how the game plays.
 */
public final class Combat {
    /** How long after your last hit the Target HUD keeps showing the target. */
    private static final long TARGET_MS = 6_000;
    /** A swing counts as a hit when the target starts its hurt animation within this time. */
    private static final long HIT_WINDOW_MS = 600;
    /** A combo ends when you land no hit for this long (or when you get hit). */
    private static final long COMBO_TIMEOUT_MS = 3_000;
    private static final long FEED_MS = 3_000;
    private static final int FEED_MAX = 5;

    private record Damage(String text, long at, boolean kill) {}

    private static LivingEntity target;
    private static long lastAttackAt;
    private static boolean pendingHit;
    private static float targetHealth;
    private static int targetHurtTime;
    private static double lastReach = -1;
    private static int combo;
    private static long lastHitAt;
    private static int playerHurtTime;
    private static boolean playerDead;
    private static final ArrayDeque<Damage> FEED = new ArrayDeque<>();
    private static final Set<Integer> KILLED = new HashSet<>();

    // Session stats (since the game started, counted only while in a world).
    private static long sessionMs;
    private static long lastSessionTick;
    private static int hits;
    private static int swings;
    private static int kills;
    private static int deaths;
    private static int bestCombo;
    private static double damageDealt;

    private Combat() {}

    /** Called from the attack hook whenever you swing at an entity. */
    public static void onAttack(MinecraftClient client, Entity entity) {
        if (client.player == null || !(entity instanceof LivingEntity living)) return;
        swings++;
        if (target != living) {
            target = living;
            targetHealth = living.getHealth();
            targetHurtTime = living.hurtTime;
        }
        lastAttackAt = System.currentTimeMillis();
        pendingHit = true;
        lastReach = reach(client, living);
    }

    /** Distance from your eyes to where the crosshair touched the target, or to the nearest point of its hitbox. */
    private static double reach(MinecraftClient client, LivingEntity living) {
        Vec3d eye = client.player.getEyePos();
        if (client.crosshairTarget instanceof EntityHitResult hit && hit.getEntity() == living) {
            return eye.distanceTo(hit.getPos());
        }
        Box box = living.getBoundingBox();
        Vec3d closest = new Vec3d(MathHelper.clamp(eye.x, box.minX, box.maxX),
                MathHelper.clamp(eye.y, box.minY, box.maxY), MathHelper.clamp(eye.z, box.minZ, box.maxZ));
        return eye.distanceTo(closest);
    }

    public static void tick(MinecraftClient client) {
        long now = System.currentTimeMillis();
        if (client.player == null || client.world == null) {
            target = null;
            pendingHit = false;
            combo = 0;
            lastSessionTick = 0;
            return;
        }
        if (lastSessionTick != 0) sessionMs += Math.min(1_000, now - lastSessionTick);
        lastSessionTick = now;

        // Getting hit ends your combo.
        int hurt = client.player.hurtTime;
        if (hurt > 0 && playerHurtTime == 0) combo = 0;
        playerHurtTime = hurt;
        if (combo > 0 && now - lastHitAt > COMBO_TIMEOUT_MS) combo = 0;

        boolean dead = client.player.isDead();
        if (dead && !playerDead) {
            deaths++;
            com.vclient.mod.cosmetics.Effects.onDeath(client);
            combo = 0;
        }
        playerDead = dead;

        if (target != null) {
            if (target.isRemoved() && target.getHealth() > 0) {
                target = null;
            } else {
                trackTarget(now);
            }
        }
        while (!FEED.isEmpty() && now - FEED.peekFirst().at() > FEED_MS) FEED.removeFirst();
    }

    private static void trackTarget(long now) {
        boolean recentlyAttacked = now - lastAttackAt < 1_500;
        int hurt = target.hurtTime;
        if (pendingHit && hurt > targetHurtTime && now - lastAttackAt <= HIT_WINDOW_MS) {
            pendingHit = false;
            hits++;
            combo++;
            bestCombo = Math.max(bestCombo, combo);
            lastHitAt = now;
            Visuals.onHit(MinecraftClient.getInstance(), target);
        } else if (pendingHit && now - lastAttackAt > HIT_WINDOW_MS) {
            pendingHit = false;
        }
        targetHurtTime = hurt;

        float health = target.getHealth();
        if (health < targetHealth && recentlyAttacked) {
            float dealt = targetHealth - health;
            damageDealt += dealt;
            boolean kill = health <= 0 && KILLED.add(target.getId());
            if (kill) {
                kills++;
                if (VConfig.bool(Module.ISLAND, "combat")) Island.notify("\u2694", "You killed " + name(target), 0xFFFF6B6B);
                com.vclient.mod.cosmetics.Effects.onKill(MinecraftClient.getInstance(), target.getX(), target.getY() + 1, target.getZ());
            }
            FEED.addLast(new Damage(String.format(Locale.ROOT, "-%.1f ❤ %s", dealt / 2f, name(target)), now, kill));
            while (FEED.size() > FEED_MAX) FEED.removeFirst();
        }
        targetHealth = health;
        if (KILLED.size() > 256) KILLED.clear();
    }

    private static String name(Entity entity) {
        String name = entity.getDisplayName().getString();
        return name.length() > 16 ? name.substring(0, 15) + "…" : name;
    }

    static boolean targetActive() {
        return target != null && System.currentTimeMillis() - lastAttackAt < TARGET_MS;
    }

    static boolean feedActive() {
        return !FEED.isEmpty();
    }

    static boolean cooldownCharging(MinecraftClient client) {
        return client.player != null && client.player.getAttackCooldownProgress(0) < 1f;
    }

    // ---- HUD text and drawing -------------------------------------------------------------------------------

    static String[] comboText() {
        return new String[]{"Combo: ", combo == 0 ? "0" : combo + (combo >= 3 ? "!" : "")};
    }

    static String[] reachText(boolean editing) {
        if (lastReach < 0) return new String[]{"Reach: ", editing ? "3.00 m" : "-"};
        return new String[]{"Reach: ", String.format(Locale.ROOT, "%.2f m", lastReach)};
    }

    static final int TARGET_WIDTH = 128;
    static final int TARGET_HEIGHT = 36;

    static void drawTarget(DrawContext context, MinecraftClient client, boolean background, int label, boolean editing) {
        TextRenderer font = client.textRenderer;
        LivingEntity shown = targetActive() ? target : null;
        String name = shown == null ? (editing ? "Steve" : "") : name(shown);
        float health = shown == null ? 14f : Math.max(0, shown.getHealth());
        float max = shown == null ? 20f : Math.max(1, shown.getMaxHealth());
        float absorption = shown == null ? 0 : shown.getAbsorptionAmount();
        int armor = shown == null ? 12 : shown.getArmor();
        double distance = shown == null || client.player == null ? 3.1 : Math.sqrt(client.player.squaredDistanceTo(shown));
        if (background) Hud.frostPanel(context, Module.TARGET_HUD, TARGET_WIDTH, TARGET_HEIGHT);
        context.drawText(font, name, 4, 4, label, true);
        String hp = String.format(Locale.ROOT, "%.1f", (health + absorption) / 2f) + " ❤";
        context.drawText(font, hp, TARGET_WIDTH - 4 - font.getWidth(hp), 4, 0xFFFF6B6B, true);

        int barX = 4;
        int barY = 15;
        int barWidth = TARGET_WIDTH - 8;
        context.fill(barX, barY, barX + barWidth, barY + 5, 0x80000000);
        float fraction = MathHelper.clamp(health / max, 0, 1);
        int color = fraction > 0.5f ? 0xFF7CF29A : fraction > 0.25f ? 0xFFFFD166 : 0xFFFF6B6B;
        context.fill(barX, barY, barX + Math.round(barWidth * fraction), barY + 5, color);
        if (absorption > 0) {
            float extra = MathHelper.clamp(absorption / max, 0, 1);
            context.fill(barX, barY + 4, barX + Math.round(barWidth * extra), barY + 5, 0xFFFFD700);
        }
        String details = "⛨ " + armor + "   " + String.format(Locale.ROOT, "%.1f m", distance);
        if (combo > 0) details += "   x" + combo;
        context.drawText(font, details, 4, 24, Hud.MUTED, true);
    }

    static final int COOLDOWN_WIDTH = 60;
    static final int COOLDOWN_HEIGHT = 13;

    static void drawCooldown(DrawContext context, MinecraftClient client, boolean background, boolean editing) {
        float progress = client.player == null ? 0.65f : client.player.getAttackCooldownProgress(0);
        if (editing && progress >= 1f) progress = 0.65f;
        if (background) Hud.frostPanel(context, Module.ATTACK_COOLDOWN, COOLDOWN_WIDTH, COOLDOWN_HEIGHT);
        int inner = COOLDOWN_WIDTH - 8;
        context.fill(4, 5, 4 + inner, 8, 0x80000000);
        context.fill(4, 5, 4 + Math.round(inner * progress), 8, progress >= 1f ? 0xFF7CF29A : Hud.ACCENT);
    }

    static List<String> feedLines(boolean editing) {
        List<String> lines = new ArrayList<>();
        for (Damage damage : FEED) lines.add(damage.text() + (damage.kill() ? "  KILL" : ""));
        if (lines.isEmpty() && editing) {
            lines.add("-3.5 ❤ Zombie");
            lines.add("-2.0 ❤ Steve  KILL");
        }
        return lines;
    }

    static List<String[]> sessionLines() {
        List<String[]> lines = new ArrayList<>();
        long minutes = sessionMs / 60_000;
        lines.add(new String[]{"Playtime: ", minutes / 60 + "h " + String.format(Locale.ROOT, "%02d", minutes % 60) + "m"});
        lines.add(new String[]{"Kills: ", kills + "   Deaths: " + deaths});
        String accuracy = swings == 0 ? "-" : Math.round(hits * 100f / swings) + "%";
        lines.add(new String[]{"Hits: ", hits + " (" + accuracy + ")   Best combo: " + bestCombo});
        lines.add(new String[]{"Damage: ", String.format(Locale.ROOT, "%.1f ❤", damageDealt / 2)});
        return lines;
    }
}
