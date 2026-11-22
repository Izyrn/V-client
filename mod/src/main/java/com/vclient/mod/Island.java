package com.vclient.mod;

import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.platform.win32.WinUser;
import com.sun.jna.ptr.IntByReference;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.BossBarHud;
import net.minecraft.client.gui.hud.ClientBossBar;

import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.Map;

/**
 * The Island: a rounded pill at the top of the screen that grows to show what matters right now, in this order:
 * notifications (kills, totem pops, broken armor, server titles), the server's boss bar, then the song Spotify is
 * playing. Spotify is read from its window title (no login) and controlled with media keys.
 */
public final class Island {
    private record Note(String icon, String text, int color, long until) {}

    private static final ArrayDeque<Note> NOTES = new ArrayDeque<>();
    private static volatile String song = "";
    private static volatile boolean spotifyPaused;
    private static long lastSpotifyCheck;
    private static float width = 40;
    private static boolean[] keysDown = new boolean[3];
    private static Field bossBarsField;
    private static boolean bossBarsLookedUp;

    private Island() {}

    /** Queues a notification (shown for 3 seconds, newest last). */
    public static void notify(String icon, String text, int color) {
        if (!VConfig.enabled(Module.ISLAND)) return;
        NOTES.addLast(new Note(icon, text, color, 0));
        while (NOTES.size() > 4) NOTES.removeFirst();
    }

    /** A server title such as "BEDWARS STARTED" or "VICTORY!". */
    public static void serverTitle(String title) {
        if (title == null || title.isBlank() || !VConfig.bool(Module.ISLAND, "events")) return;
        notify("★", title.strip(), 0xFFFFD166);
    }

    public static void tick(MinecraftClient client) {
        if (!VConfig.enabled(Module.ISLAND)) return;
        long now = System.currentTimeMillis();
        if (VConfig.bool(Module.ISLAND, "spotify") && now - lastSpotifyCheck > 2000) {
            lastSpotifyCheck = now;
            Thread.ofVirtual().start(Island::readSpotify);
        }
        String[] ids = {"playKey", "nextKey", "previousKey"};
        int[] keys = {0xB3, 0xB0, 0xB1};
        for (int i = 0; i < 3; i++) {
            int key = VConfig.integer(Module.ISLAND, ids[i]);
            boolean down = client.currentScreen == null && key != Setting.NO_KEY
                    && org.lwjgl.glfw.GLFW.glfwGetKey(client.getWindow().getHandle(), key) == org.lwjgl.glfw.GLFW.GLFW_PRESS;
            if (down && !keysDown[i]) {
                int virtualKey = keys[i];
                Thread.ofVirtual().start(() -> press(virtualKey));
                lastSpotifyCheck = 0;
            }
            keysDown[i] = down;
        }
    }

    static void render(DrawContext context, MinecraftClient client) {
        if (!VConfig.enabled(Module.ISLAND) || client.player == null) return;
        long now = System.currentTimeMillis();
        // Start the timer of the note at the front when it first shows.
        Note front = NOTES.peekFirst();
        if (front != null && front.until() == 0) {
            NOTES.removeFirst();
            front = new Note(front.icon(), front.text(), front.color(), now + 3000);
            NOTES.addFirst(front);
        }
        if (front != null && now > front.until()) {
            NOTES.removeFirst();
            front = null;
        }
        String icon;
        String text;
        int color;
        float progress = -1;
        ClientBossBar boss = VConfig.bool(Module.ISLAND, "bossBars") ? firstBossBar(client) : null;
        if (front != null) {
            icon = front.icon();
            text = front.text();
            color = front.color();
        } else if (boss != null) {
            icon = "☠";
            text = boss.getName().getString();
            color = 0xFFFF9FD5;
            progress = boss.getPercent();
        } else if (VConfig.bool(Module.ISLAND, "spotify") && !song.isEmpty()) {
            icon = spotifyPaused ? "⏸" : "♫";
            text = song;
            color = 0xFF7CF29A;
        } else {
            icon = "";
            text = "";
            color = 0xFFFFFFFF;
        }
        TextRenderer font = client.textRenderer;
        if (text.length() > 48) text = text.substring(0, 47) + "…";
        float target = text.isEmpty() ? 34 : Math.min(320, font.getWidth(icon + "  " + text) + 22);
        width += (target - width) * 0.25f;
        int w = Math.round(width);
        int h = progress >= 0 ? 20 : 16;
        int screenWidth = client.getWindow().getScaledWidth();
        int x = (screenWidth - w) / 2;
        int y = 4;
        pill(context, x, y, w, h);
        if (text.isEmpty() || w < target - 6) return;
        context.drawText(font, icon, x + 9, y + 4, color, false);
        context.drawText(font, text, x + 9 + font.getWidth(icon + "  "), y + 4, 0xFFFFFFFF, false);
        if (progress >= 0) {
            int barWidth = w - 18;
            context.fill(x + 9, y + 15, x + 9 + barWidth, y + 17, 0x60FFFFFF);
            context.fill(x + 9, y + 15, x + 9 + Math.round(barWidth * progress), y + 17, color);
        }
    }

    /** A black glass pill with fully rounded ends. */
    private static void pill(DrawContext context, int x, int y, int w, int h) {
        int body = 0xE6050A12;
        int radius = h / 2;
        for (int row = 0; row < h; row++) {
            double dy = Math.abs(row + 0.5 - h / 2.0);
            int inset = (int) Math.round(radius - Math.sqrt(Math.max(0, radius * radius - dy * dy)));
            context.fill(x + inset, y + row, x + w - inset, y + row + 1, body);
        }
        context.fill(x + radius, y, x + w - radius, y + 1, 0x557CEBFF);
    }

    /** First active boss bar, read from the boss bar HUD (field found by type, since names differ at runtime). */
    private static ClientBossBar firstBossBar(MinecraftClient client) {
        try {
            BossBarHud hud = client.inGameHud.getBossBarHud();
            if (!bossBarsLookedUp) {
                bossBarsLookedUp = true;
                for (Field field : BossBarHud.class.getDeclaredFields()) {
                    if (Map.class.isAssignableFrom(field.getType())) {
                        field.setAccessible(true);
                        bossBarsField = field;
                    }
                }
            }
            if (bossBarsField == null) return null;
            Map<?, ?> bars = (Map<?, ?>) bossBarsField.get(hud);
            for (Object bar : bars.values()) if (bar instanceof ClientBossBar clientBar) return clientBar;
        } catch (ReflectiveOperationException | RuntimeException e) {
            bossBarsField = null;
        }
        return null;
    }

    /** Whether vanilla boss bars should be hidden because the Island shows them. */
    public static boolean hidesBossBars() {
        return VConfig.enabled(Module.ISLAND) && VConfig.bool(Module.ISLAND, "bossBars");
    }

    // ---- Spotify ------------------------------------------------------------------------------------------

    private static void readSpotify() {
        try {
            StringBuilder found = new StringBuilder();
            boolean[] open = {false};
            User32.INSTANCE.EnumWindows((hwnd, data) -> {
                if (!User32.INSTANCE.IsWindowVisible(hwnd) || !spotifyWindow(hwnd)) return true;
                char[] buffer = new char[512];
                int length = User32.INSTANCE.GetWindowText(hwnd, buffer, buffer.length);
                String title = new String(buffer, 0, length).strip();
                if (title.isEmpty()) return true;
                open[0] = true;
                if (!title.startsWith("Spotify") && found.isEmpty()) found.append(title);
                return true;
            }, null);
            song = found.toString();
            spotifyPaused = found.isEmpty() && open[0];
            if (spotifyPaused) song = "Spotify paused";
        } catch (Throwable e) {
            song = "";
        }
    }

    private static boolean spotifyWindow(WinDef.HWND hwnd) {
        IntByReference pid = new IntByReference();
        User32.INSTANCE.GetWindowThreadProcessId(hwnd, pid);
        WinNT.HANDLE process = Kernel32.INSTANCE.OpenProcess(0x1000, false, pid.getValue());
        if (process == null) return false;
        try {
            char[] buffer = new char[1024];
            IntByReference size = new IntByReference(buffer.length);
            if (!Kernel32.INSTANCE.QueryFullProcessImageName(process, 0, buffer, size)) return false;
            return new String(buffer, 0, size.getValue()).toLowerCase(java.util.Locale.ROOT).endsWith("\\spotify.exe");
        } finally {
            Kernel32.INSTANCE.CloseHandle(process);
        }
    }

    private static void press(int virtualKey) {
        try {
            WinUser.INPUT[] inputs = (WinUser.INPUT[]) new WinUser.INPUT().toArray(2);
            for (int i = 0; i < 2; i++) {
                inputs[i].type = new WinDef.DWORD(WinUser.INPUT.INPUT_KEYBOARD);
                inputs[i].input.setType("ki");
                inputs[i].input.ki.wVk = new WinDef.WORD(virtualKey);
                inputs[i].input.ki.wScan = new WinDef.WORD(0);
                inputs[i].input.ki.time = new WinDef.DWORD(0);
                inputs[i].input.ki.dwExtraInfo = new com.sun.jna.platform.win32.BaseTSD.ULONG_PTR(0);
                inputs[i].input.ki.dwFlags = new WinDef.DWORD(i == 0 ? 0 : 2);
            }
            User32.INSTANCE.SendInput(new WinDef.DWORD(2), inputs, inputs[0].size());
        } catch (Throwable ignored) {
            // media keys unavailable
        }
    }

}
