package com.vclient;

import java.util.List;
import java.util.Locale;

/**
 * Launcher colour themes. A theme is an accent colour plus a very dark background tinted toward it. The default
 * "Frost" theme is the ice-blue default; the others are Glacier, Blizzard, Aurora, Polar Dusk and a Custom RGB accent.
 */
final class Theme {
    record Preset(String id, String label, String accent, String background) {}

    static final String CUSTOM = "custom";
    static final List<Preset> PRESETS = List.of(
            new Preset("frost", "Frost (default)", "#00cfff", "#071321"),
            new Preset("glacier", "Glacier", "#7cebff", "#08182a"),
            new Preset("blizzard", "Blizzard", "#b8f4ff", "#0b1424"),
            new Preset("aurora", "Aurora", "#5cffc8", "#061a1f"),
            new Preset("dusk", "Polar Dusk", "#9db8ff", "#0a1030"));

    private static String accent = PRESETS.getFirst().accent();
    private static String background = PRESETS.getFirst().background();

    private Theme() {}

    static String accent() {
        return accent;
    }

    static String background() {
        return background;
    }

    /** Applies a preset id, or {@link #CUSTOM} with {@code customAccent} (a #rrggbb string). */
    static void apply(String id, String customAccent) {
        if (CUSTOM.equals(id) && validHex(customAccent)) {
            accent = customAccent.toLowerCase(Locale.ROOT);
            background = tintedBackground(accent);
        } else {
            Preset preset = PRESETS.stream().filter(p -> p.id().equals(id)).findFirst().orElse(PRESETS.getFirst());
            accent = preset.accent();
            background = preset.background();
        }
        ModsUi.ACCENT = accent;
        LauncherApp.setThemeColors(accent, background);
    }

    static boolean validHex(String value) {
        return value != null && value.matches("#[0-9a-fA-F]{6}");
    }

    /** {@code rgba(r,g,b,alpha)} for the current accent, for translucent highlights. */
    static String accentRgba(double alpha) {
        int[] rgb = rgb(accent);
        return "rgba(" + rgb[0] + ", " + rgb[1] + ", " + rgb[2] + ", " + alpha + ")";
    }

    static int[] rgb(String hex) {
        int value = Integer.parseInt(hex.substring(1), 16);
        return new int[]{(value >> 16) & 0xFF, (value >> 8) & 0xFF, value & 0xFF};
    }

    /** A near-black colour carrying a hint of the accent's hue, so any custom accent still gets a readable page. */
    static String tintedBackground(String accentHex) {
        int[] rgb = rgb(accentHex);
        int max = Math.max(1, Math.max(rgb[0], Math.max(rgb[1], rgb[2])));
        int r = 5 + rgb[0] * 14 / max;
        int g = 5 + rgb[1] * 14 / max;
        int b = 5 + rgb[2] * 14 / max;
        return String.format(Locale.ROOT, "#%02x%02x%02x", r, g, b);
    }

    /** The background nudged lighter, for the layered hills and glow drawn over it. */
    static int[] lifted(String backgroundHex, int amount) {
        int[] rgb = rgb(backgroundHex);
        return new int[]{Math.min(255, rgb[0] + amount), Math.min(255, rgb[1] + amount), Math.min(255, rgb[2] + amount)};
    }

    static int[] glow() {
        // Every theme glows in its own accent.
        return rgb(accent);
    }
}
