package com.vclient.mod;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.item.ItemStack;
import org.lwjgl.glfw.GLFW;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Draws the movable HUD elements and tracks clicks per second. */
public final class Hud {
    static final int PANEL = 0xA00C1F33;
    static final int TEXT = 0xFFFFFFFF;
    static final int ACCENT = 0xFF00CFFF;
    static final int MUTED = 0xFFA9D2E8;
    private static final int KEY = 22;
    private static final int GAP = 2;
    private static final int LINE = 12;
    // Looked up by serialized name: enum constant names are obfuscated in the running game.
    private static final EquipmentSlot[] ARMOR_SLOTS = {EquipmentSlot.byName("head"),
            EquipmentSlot.byName("chest"), EquipmentSlot.byName("legs"), EquipmentSlot.byName("feet")};
    private static final Module[] LEFT_COLUMN = {Module.FPS, Module.CPS, Module.PING, Module.SPEED, Module.CLOCK,
            Module.MEMORY, Module.DAY_COUNTER, Module.SERVER_ADDRESS, Module.ITEM_COUNT, Module.LAST_DEATH,
            Module.DURABILITY, Module.LIGHT_LEVEL, Module.BIOME, Module.COORDINATES, Module.KEYSTROKES};
    private static final String[] ROMAN = {"I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"};

    private static final ArrayDeque<Long> LEFT_CLICKS = new ArrayDeque<>();
    private static final ArrayDeque<Long> RIGHT_CLICKS = new ArrayDeque<>();
    private static boolean leftWasDown;
    private static boolean rightWasDown;

    private Hud() {}

    public static void render(DrawContext context) {
        PerfProbe.frame();
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.options.hudHidden) return;
        trackClicks(client);
        if (client.currentScreen instanceof HudEditorScreen) return;
        RecordingMarker.render(context, client);
        for (Module module : Module.values()) {
            if (!module.hudElement() || !VConfig.enabled(module) || !visible(client, module)) continue;
            int[] bounds = bounds(client, module);
            draw(context, client, module, bounds[0], bounds[1], false);
        }
    }

    /** Elements that only appear while relevant (the editor shows them all). */
    /**
     * Frosted-glass HUD background with an ice border. FPS gets a crystal border (bright corners), CPS a soft blue
     * glow, everything else a thin frost edge.
     */
    static void frostPanel(DrawContext context, Module module, int width, int height) {
        if (module == Module.CPS) {
            context.fill(-2, -2, width + 2, height + 2, 0x2600CFFF);
            context.fill(-1, -1, width + 1, height + 1, 0x3A00CFFF);
        }
        context.fill(0, 0, width, height, PANEL);
        context.fill(0, 0, width, 1, 0x667CEBFF);
        context.fill(0, height - 1, width, height, 0x447CEBFF);
        context.fill(0, 0, 1, height, 0x447CEBFF);
        context.fill(width - 1, 0, width, height, 0x447CEBFF);
        if (module == Module.FPS) {
            int corner = 3;
            int bright = 0xFFB8F4FF;
            context.fill(0, 0, corner, 1, bright);
            context.fill(0, 0, 1, corner, bright);
            context.fill(width - corner, 0, width, 1, bright);
            context.fill(width - 1, 0, width, corner, bright);
            context.fill(0, height - 1, corner, height, bright);
            context.fill(0, height - corner, 1, height, bright);
            context.fill(width - corner, height - 1, width, height, bright);
            context.fill(width - 1, height - corner, width, height, bright);
        }
    }

    private static boolean visible(MinecraftClient client, Module module) {
        return switch (module) {
            case TOGGLE_SPRINT -> client.options.sprintKey.isPressed();
            case POTION_EFFECTS -> client.player != null && !client.player.getStatusEffects().isEmpty();
            default -> true;
        };
    }

    private static float scale(Module module) {
        return (float) (VConfig.get(module, CommonSettings.SCALE) / 100.0);
    }

    /** Unscaled width of an element. */
    private static int width(MinecraftClient client, Module module, boolean editing) {
        TextRenderer font = client.textRenderer;
        return switch (module) {
            case KEYSTROKES -> KEY * 3 + GAP * 2;
            case ARMOR_STATUS -> 16 + (VConfig.bool(module, "numbers") ? 4 + font.getWidth("9999") : 0) + 4;
            case COORDINATES -> {
                int widest = font.getWidth("XYZ: " + coordinateText(client.player));
                if (VConfig.bool(module, "facing")) widest = Math.max(widest, font.getWidth("Facing: southeast"));
                yield widest + 8;
            }
            case POTION_EFFECTS -> {
                int widest = 0;
                for (String line : effectLines(client, editing)) widest = Math.max(widest, font.getWidth(line));
                yield widest + 8;
            }
            default -> {
                String[] text = text(client, module);
                yield font.getWidth(text[0] + text[1]) + 8;
            }
        };
    }

    /** Unscaled height of an element. */
    private static int height(MinecraftClient client, Module module, boolean editing) {
        return switch (module) {
            case COORDINATES -> VConfig.bool(module, "facing") ? 26 : 13;
            case KEYSTROKES -> {
                int rows = KEY * 2 + GAP;
                if (VConfig.bool(module, "mouse")) rows += GAP + KEY;
                if (VConfig.bool(module, "space")) rows += GAP + 12;
                yield rows;
            }
            case ARMOR_STATUS -> (VConfig.bool(module, "held") ? 5 : 4) * 17;
            case POTION_EFFECTS -> Math.max(1, effectLines(client, editing).size()) * LINE + 2;
            default -> 13;
        };
    }

    /** Current top-left corner and scaled size, kept inside the screen. */
    static int[] bounds(MinecraftClient client, Module module) {
        return bounds(client, module, client.currentScreen instanceof HudEditorScreen);
    }

    private static int[] bounds(MinecraftClient client, Module module, boolean editing) {
        int screenWidth = client.getWindow().getScaledWidth();
        int screenHeight = client.getWindow().getScaledHeight();
        float scale = scale(module);
        int width = Math.round(width(client, module, editing) * scale);
        int height = Math.round(height(client, module, editing) * scale);
        int[] saved = VConfig.position(module);
        int[] position = saved != null ? saved
                : defaultPosition(client, module, screenWidth, screenHeight, width, editing);
        int x = Math.clamp(position[0], 0, Math.max(0, screenWidth - width));
        int y = Math.clamp(position[1], 0, Math.max(0, screenHeight - height));
        return new int[]{x, y, width, height};
    }

    private static int[] defaultPosition(MinecraftClient client, Module module, int screenWidth,
                                         int screenHeight, int width, boolean editing) {
        return switch (module) {
            case ARMOR_STATUS -> new int[]{screenWidth - width - 4, screenHeight / 2 - 42};
            case POTION_EFFECTS -> new int[]{screenWidth - width - 4, 4};
            case TOGGLE_SPRINT -> new int[]{4, screenHeight - Math.round(17 * scale(module))};
            default -> new int[]{4, leftColumnY(client, module, editing)};
        };
    }

    /** Stacks the left-column elements that still use default positions, with no gaps. */
    private static int leftColumnY(MinecraftClient client, Module target, boolean editing) {
        int y = 4;
        for (Module module : LEFT_COLUMN) {
            if (module == target) return y;
            if (VConfig.enabled(module) && VConfig.position(module) == null) {
                y += Math.round(height(client, module, editing) * scale(module)) + 3;
            }
        }
        return y;
    }

    static void draw(DrawContext context, MinecraftClient client, Module module, int x, int y, boolean editing) {
        float scale = scale(module);
        context.getMatrices().pushMatrix();
        context.getMatrices().translate(x, y);
        context.getMatrices().scale(scale, scale);
        try {
            drawLocal(context, client, module, editing);
        } finally {
            context.getMatrices().popMatrix();
        }
    }

    private static void drawLocal(DrawContext context, MinecraftClient client, Module module, boolean editing) {
        TextRenderer font = client.textRenderer;
        int width = width(client, module, editing);
        int height = height(client, module, editing);
        int label = CommonSettings.COLORS[VConfig.integer(module, CommonSettings.LABEL_COLOR.id())];
        boolean background = VConfig.get(module, CommonSettings.BACKGROUND) >= 0.5;
        switch (module) {
            case KEYSTROKES -> keystrokes(context, client, background);
            case ARMOR_STATUS -> armor(context, client, editing);
            case COORDINATES -> {
                if (background) frostPanel(context, module, width, height);
                context.drawText(font, "XYZ: ", 4, 3, label, true);
                context.drawText(font, coordinateText(client.player), 4 + font.getWidth("XYZ: "), 3, TEXT, true);
                if (VConfig.bool(module, "facing")) {
                    String facing = client.player == null ? "north" : client.player.getHorizontalFacing().asString();
                    context.drawText(font, "Facing: ", 4, 15, label, true);
                    context.drawText(font, facing, 4 + font.getWidth("Facing: "), 15, TEXT, true);
                }
            }
            case POTION_EFFECTS -> {
                if (background) frostPanel(context, module, width, height);
                int line = 0;
                for (String text : effectLines(client, editing)) {
                    context.drawText(font, text, 4, 3 + line * LINE, line % 2 == 0 ? label : TEXT, true);
                    line++;
                }
            }
            default -> {
                String[] text = text(client, module);
                if (background) frostPanel(context, module, width, height);
                context.drawText(font, text[0], 4, 3, label, true);
                context.drawText(font, text[1], 4 + font.getWidth(text[0]), 3, TEXT, true);
            }
        }
    }

    /** Label and value for the single-line elements. */
    private static String[] text(MinecraftClient client, Module module) {
        return switch (module) {
            case FPS -> new String[]{"FPS: ", Integer.toString(client.getCurrentFps())};
            case CPS -> new String[]{"CPS: ", VConfig.bool(module, "rightClicks")
                    ? count(LEFT_CLICKS) + " | " + count(RIGHT_CLICKS) : Integer.toString(count(LEFT_CLICKS))};
            case PING -> new String[]{"Ping: ", ping(client)};
            case SPEED -> {
                double blocksPerSecond = VClientMod.speed();
                yield VConfig.integer(module, "unit") == 1
                        ? new String[]{"Speed: ", String.format(Locale.ROOT, "%.1f km/h", blocksPerSecond * 3.6)}
                        : new String[]{"Speed: ", String.format(Locale.ROOT, "%.2f b/s", blocksPerSecond)};
            }
            case CLOCK -> new String[]{"Time: ", clock(module)};
            case MEMORY -> new String[]{"Memory: ", memory()};
            case DAY_COUNTER -> new String[]{"Day: ", client.world == null ? "0"
                    : Long.toString(client.world.getTimeOfDay() / 24000L)};
            case SERVER_ADDRESS -> new String[]{"Server: ", server(client)};
            case ITEM_COUNT -> {
                String[] item = ITEM_CHOICES[Math.clamp(VConfig.integer(module, "item"), 0, ITEM_CHOICES.length - 1)];
                yield new String[]{item[0] + ": ", Integer.toString(VClientMod.countItem(client, item[1]))};
            }
            case LAST_DEATH -> new String[]{"Died: ", VClientMod.lastDeath()};
            case DURABILITY -> new String[]{"Durability: ", durability(client)};
            case LIGHT_LEVEL -> new String[]{"Light: ", client.world == null || client.player == null ? "-"
                    : Integer.toString(client.world.getLightLevel(client.player.getBlockPos()))};
            case BIOME -> new String[]{"Biome: ", biome(client)};
            case TOGGLE_SPRINT -> new String[]{"[Sprinting (Toggled)]", ""};
            default -> new String[]{"", ""};
        };
    }

    /** Item Counter choices: label and registry path. */
    private static final String[][] ITEM_CHOICES = {{"Totems", "totem_of_undying"}, {"Arrows", "arrow"},
            {"Ender Pearls", "ender_pearl"}, {"Golden Apples", "golden_apple"}, {"XP Bottles", "experience_bottle"},
            {"Fireworks", "firework_rocket"}};

    private static String durability(MinecraftClient client) {
        if (client.player == null) return "-";
        var stack = client.player.getMainHandStack();
        if (stack.isEmpty() || !stack.isDamageable()) return "-";
        int left = stack.getMaxDamage() - stack.getDamage();
        return left + "/" + stack.getMaxDamage() + " (" + Math.round(left * 100f / stack.getMaxDamage()) + "%)";
    }

    private static String biome(MinecraftClient client) {
        if (client.world == null || client.player == null) return "-";
        String name = client.world.getBiome(client.player.getBlockPos()).getKey()
                .map(key -> key.getValue().getPath()).orElse("unknown");
        StringBuilder pretty = new StringBuilder();
        for (String word : name.split("_")) {
            if (pretty.length() > 0) pretty.append(' ');
            pretty.append(word.isEmpty() ? word : Character.toUpperCase(word.charAt(0)) + word.substring(1));
        }
        return pretty.toString();
    }

    private static String clock(Module module) {
        boolean twentyFour = VConfig.bool(module, "twentyFour");
        boolean seconds = VConfig.bool(module, "seconds");
        String pattern = (twentyFour ? "HH:mm" : "h:mm") + (seconds ? ":ss" : "") + (twentyFour ? "" : " a");
        return LocalTime.now().format(DateTimeFormatter.ofPattern(pattern, Locale.ROOT));
    }

    private static String memory() {
        Runtime runtime = Runtime.getRuntime();
        long used = runtime.totalMemory() - runtime.freeMemory();
        long max = runtime.maxMemory();
        return String.format(Locale.ROOT, "%d%% %.1f/%.1f GB", used * 100 / Math.max(1, max),
                used / 1073741824.0, max / 1073741824.0);
    }

    private static String server(MinecraftClient client) {
        if (client.isInSingleplayer()) return "Singleplayer";
        ServerInfo server = client.getCurrentServerEntry();
        return server == null ? "Not connected" : server.address;
    }

    private static List<String> effectLines(MinecraftClient client, boolean editing) {
        List<String> lines = new ArrayList<>();
        boolean level = VConfig.bool(Module.POTION_EFFECTS, "level");
        if (client.player != null) {
            for (StatusEffectInstance effect : client.player.getStatusEffects()) {
                String name = effect.getEffectType().comp_349().getName().getString();
                int amplifier = effect.getAmplifier();
                if (level && amplifier > 0) {
                    name += " " + (amplifier < ROMAN.length ? ROMAN[amplifier] : Integer.toString(amplifier + 1));
                }
                lines.add(name + "  " + duration(effect));
            }
        }
        if (lines.isEmpty() && editing) lines.add("Speed" + (level ? " II" : "") + "  1:30");
        return lines;
    }

    private static String duration(StatusEffectInstance effect) {
        if (effect.isInfinite()) return "**:**";
        int seconds = effect.getDuration() / 20;
        return seconds / 60 + ":" + String.format(Locale.ROOT, "%02d", seconds % 60);
    }

    private static String coordinateText(ClientPlayerEntity player) {
        if (player == null) return "0 / 0 / 0";
        return player.getBlockPos().getX() + " / " + player.getBlockPos().getY() + " / " + player.getBlockPos().getZ();
    }

    private static String ping(MinecraftClient client) {
        if (client.player == null || client.getNetworkHandler() == null) return "0 ms";
        PlayerListEntry entry = client.getNetworkHandler().getPlayerListEntry(client.player.getUuid());
        return (entry == null ? 0 : entry.getLatency()) + " ms";
    }

    private static void keystrokes(DrawContext context, MinecraftClient client, boolean background) {
        TextRenderer font = client.textRenderer;
        key(context, font, KEY + GAP, 0, KEY, KEY, "W", client.options.forwardKey, background);
        int row = KEY + GAP;
        key(context, font, 0, row, KEY, KEY, "A", client.options.leftKey, background);
        key(context, font, KEY + GAP, row, KEY, KEY, "S", client.options.backKey, background);
        key(context, font, (KEY + GAP) * 2, row, KEY, KEY, "D", client.options.rightKey, background);
        row += KEY + GAP;
        int fullWidth = KEY * 3 + GAP * 2;
        if (VConfig.bool(Module.KEYSTROKES, "mouse")) {
            int mouseWidth = (KEY * 3 + GAP) / 2;
            key(context, font, 0, row, mouseWidth, KEY, "LMB", client.options.attackKey, background);
            key(context, font, mouseWidth + GAP, row, fullWidth - mouseWidth - GAP, KEY, "RMB",
                    client.options.useKey, background);
            row += KEY + GAP;
        }
        if (VConfig.bool(Module.KEYSTROKES, "space")) {
            key(context, font, 0, row, fullWidth, 12, "", client.options.jumpKey, background);
        }
    }

    private static void key(DrawContext context, TextRenderer font, int x, int y, int width, int height,
                            String text, KeyBinding binding, boolean background) {
        boolean pressed = binding.isPressed();
        if (pressed || background) context.fill(x, y, x + width, y + height, pressed ? 0xD0B8F4FF : PANEL);
        if (text.isEmpty()) {
            int barY = y + height / 2;
            context.fill(x + width / 4, barY, x + width * 3 / 4, barY + 1, pressed ? 0xFF071321 : TEXT);
            return;
        }
        context.drawText(font, text, x + (width - font.getWidth(text)) / 2, y + (height - 8) / 2,
                pressed ? 0xFF071321 : TEXT, !pressed);
    }

    private static void armor(DrawContext context, MinecraftClient client, boolean editing) {
        ClientPlayerEntity player = client.player;
        boolean numbers = VConfig.bool(Module.ARMOR_STATUS, "numbers");
        int row = 0;
        for (EquipmentSlot slot : ARMOR_SLOTS) {
            ItemStack stack = player == null ? ItemStack.EMPTY : player.getEquippedStack(slot);
            armorRow(context, client.textRenderer, stack, row * 17, editing, numbers);
            row++;
        }
        if (VConfig.bool(Module.ARMOR_STATUS, "held")) {
            armorRow(context, client.textRenderer, player == null ? ItemStack.EMPTY : player.getMainHandStack(),
                    row * 17, editing, numbers);
        }
    }

    private static void armorRow(DrawContext context, TextRenderer font, ItemStack stack, int y, boolean editing,
                                 boolean numbers) {
        if (stack.isEmpty()) {
            if (editing) context.fill(0, y, 16, y + 16, 0x40FFFFFF);
            return;
        }
        context.drawItem(stack, 0, y);
        context.drawStackOverlay(font, stack, 0, y);
        if (!numbers) return;
        if (stack.isDamageable()) {
            int remaining = stack.getMaxDamage() - stack.getDamage();
            float fraction = remaining / (float) Math.max(1, stack.getMaxDamage());
            int color = fraction > 0.5f ? 0xFF7CF29A : fraction > 0.2f ? ACCENT : 0xFFFF6B6B;
            context.drawText(font, Integer.toString(remaining), 20, y + 4, color, true);
        } else if (stack.getCount() > 1) {
            context.drawText(font, Integer.toString(stack.getCount()), 20, y + 4, TEXT, true);
        }
    }

    private static void trackClicks(MinecraftClient client) {
        long now = System.currentTimeMillis();
        boolean playing = client.currentScreen == null && client.isWindowFocused();
        long window = client.getWindow().getHandle();
        boolean leftDown = playing && GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
        boolean rightDown = playing && GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_RIGHT) == GLFW.GLFW_PRESS;
        if (leftDown && !leftWasDown) LEFT_CLICKS.addLast(now);
        if (rightDown && !rightWasDown) RIGHT_CLICKS.addLast(now);
        leftWasDown = leftDown;
        rightWasDown = rightDown;
        while (!LEFT_CLICKS.isEmpty() && now - LEFT_CLICKS.peekFirst() > 1000) LEFT_CLICKS.removeFirst();
        while (!RIGHT_CLICKS.isEmpty() && now - RIGHT_CLICKS.peekFirst() > 1000) RIGHT_CLICKS.removeFirst();
    }

    private static int count(ArrayDeque<Long> clicks) {
        long now = System.currentTimeMillis();
        int total = 0;
        for (long time : clicks) if (now - time <= 1000) total++;
        return total;
    }
}
