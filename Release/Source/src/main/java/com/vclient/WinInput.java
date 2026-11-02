package com.vclient;

import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinUser;

import java.awt.Rectangle;
import java.util.LinkedHashMap;
import java.util.Map;

/** The few Win32 calls V-Client needs: finding the game window, reading hotkeys and sending media keys. */
final class WinInput {
    /** Hotkeys the player can choose for "save the last 30 seconds", with their virtual-key codes. */
    static final Map<String, Integer> HOTKEYS = new LinkedHashMap<>();
    static {
        for (int i = 1; i <= 12; i++) HOTKEYS.put("F" + i, 0x6F + i);
        HOTKEYS.put("Insert", 0x2D);
        HOTKEYS.put("Delete", 0x2E);
        HOTKEYS.put("Home", 0x24);
        HOTKEYS.put("End", 0x23);
        HOTKEYS.put("Page Up", 0x21);
        HOTKEYS.put("Page Down", 0x22);
        HOTKEYS.put("Pause", 0x13);
    }

    static final String DEFAULT_HOTKEY = "F8";
    private static final int VK_MEDIA_PLAY_PAUSE = 0xB3;
    private static final int VK_MEDIA_NEXT_TRACK = 0xB0;
    private static final int VK_MEDIA_PREV_TRACK = 0xB1;

    private WinInput() {}

    static boolean available() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    static boolean keyDown(int virtualKey) {
        try {
            return (User32.INSTANCE.GetAsyncKeyState(virtualKey) & 0x8000) != 0;
        } catch (Throwable e) {
            return false;
        }
    }

    /** First visible top-level window whose title starts with {@code prefix}, or null. */
    static WinDef.HWND findWindowStartingWith(String prefix) {
        WinDef.HWND[] found = new WinDef.HWND[1];
        try {
            User32.INSTANCE.EnumWindows((hwnd, data) -> {
                if (!User32.INSTANCE.IsWindowVisible(hwnd)) return true;
                if (windowTitle(hwnd).startsWith(prefix)) {
                    found[0] = hwnd;
                    return false;
                }
                return true;
            }, null);
        } catch (Throwable e) {
            return null;
        }
        return found[0];
    }

    static String windowTitle(WinDef.HWND hwnd) {
        int length = User32.INSTANCE.GetWindowTextLength(hwnd);
        if (length <= 0) return "";
        char[] buffer = new char[length + 1];
        User32.INSTANCE.GetWindowText(hwnd, buffer, buffer.length);
        return Native.toString(buffer);
    }

    static String windowClass(WinDef.HWND hwnd) {
        char[] buffer = new char[256];
        int length = User32.INSTANCE.GetClassName(hwnd, buffer, buffer.length);
        return length <= 0 ? "" : new String(buffer, 0, length);
    }

    static boolean isForeground(WinDef.HWND hwnd) {
        try {
            if (Boolean.getBoolean("vclient.assumeForeground")) return hwnd != null; // test hook
            return hwnd != null && hwnd.equals(User32.INSTANCE.GetForegroundWindow());
        } catch (Throwable e) {
            return false;
        }
    }

    /** The window's rectangle in Java screen coordinates (physical pixels divided by the display scale). */
    static Rectangle bounds(WinDef.HWND hwnd, double displayScale) {
        WinDef.RECT rect = new WinDef.RECT();
        if (!User32.INSTANCE.GetWindowRect(hwnd, rect)) return null;
        double scale = displayScale <= 0 ? 1 : displayScale;
        int x = (int) Math.round(rect.left / scale);
        int y = (int) Math.round(rect.top / scale);
        int width = (int) Math.round((rect.right - rect.left) / scale);
        int height = (int) Math.round((rect.bottom - rect.top) / scale);
        return width < 64 || height < 64 ? null : new Rectangle(x, y, width, height);
    }

    static void playPause() {
        press(VK_MEDIA_PLAY_PAUSE);
    }

    static void nextTrack() {
        press(VK_MEDIA_NEXT_TRACK);
    }

    static void previousTrack() {
        press(VK_MEDIA_PREV_TRACK);
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
                inputs[i].input.ki.dwFlags = new WinDef.DWORD(i == 0 ? 0 : WinUser.KEYBDINPUT.KEYEVENTF_KEYUP);
                inputs[i].input.ki.dwExtraInfo = new com.sun.jna.platform.win32.BaseTSD.ULONG_PTR(0);
            }
            User32.INSTANCE.SendInput(new WinDef.DWORD(2), inputs, inputs[0].size());
        } catch (Throwable e) {
            // No media key support on this system.
        }
    }

    private static final class Native {
        static String toString(char[] buffer) {
            return com.sun.jna.Native.toString(buffer);
        }
    }
}
