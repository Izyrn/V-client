package com.vclient.mod;

import com.vclient.mod.ModMenuScreen.Area;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/** One module's settings: on/off, keybinds, sliders, and choices. */
public final class ModuleSettingsScreen extends Screen {
    private static final int ROW = 20;
    private static final int PANEL_WIDTH = 260;
    private static final int CONTROL_WIDTH = 100;
    private static final int BUTTON_HEIGHT = 18;

    private final Screen parent;
    private final Module module;
    private final List<Setting> rows = new ArrayList<>();
    private Setting listening;
    private int panelX;
    private int panelY;
    private int panelHeight;
    private Area resetButton;
    private Area backButton;

    public ModuleSettingsScreen(Screen parent, Module module) {
        super(Text.literal(module.title() + " Settings"));
        this.parent = parent;
        this.module = module;
    }

    @Override
    protected void init() {
        rows.clear();
        rows.addAll(module.settings());
        int enabledRow = module.toggleable() ? 1 : 0;
        int listHeight = Math.max(1, rows.size() + enabledRow) * ROW;
        panelHeight = Math.min(44 + listHeight + 12 + BUTTON_HEIGHT + 12, height - 8);
        panelX = (width - PANEL_WIDTH) / 2;
        panelY = (height - panelHeight) / 2;
        int buttonY = panelY + panelHeight - 12 - BUTTON_HEIGHT;
        int buttonWidth = (PANEL_WIDTH - 24 - 6) / 2;
        resetButton = new Area(panelX + 12, buttonY, buttonWidth, BUTTON_HEIGHT);
        backButton = new Area(panelX + 12 + buttonWidth + 6, buttonY, buttonWidth, BUTTON_HEIGHT);
    }

    private int rowY(int index) {
        return panelY + 44 + index * ROW;
    }

    /** Control area for a row; index 0 is the on/off row when the module can be toggled. */
    private Area control(int index) {
        return new Area(panelX + PANEL_WIDTH - 12 - CONTROL_WIDTH, rowY(index) + 2, CONTROL_WIDTH, ROW - 4);
    }

    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, width, height, 0x90071321);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        context.fill(panelX, panelY, panelX + PANEL_WIDTH, panelY + panelHeight, 0xEE0B1B30);
        context.drawStrokedRectangle(panelX, panelY, PANEL_WIDTH, panelHeight, 0x667CEBFF);
        context.drawText(textRenderer, module.title().toUpperCase(java.util.Locale.ROOT), panelX + 12,
                panelY + 10, Hud.ACCENT, true);
        context.drawText(textRenderer, module.description(), panelX + 12, panelY + 24, 0xFF86B3CE, false);

        int index = 0;
        if (module.toggleable()) {
            boolean enabled = VConfig.enabled(module);
            drawRow(context, index++, "Enabled", enabled ? "ON" : "OFF", enabled, true, mouseX, mouseY, false);
        }
        for (Setting setting : rows) {
            double value = VConfig.get(module, setting);
            String display = setting == listening ? "> Press a key <" : setting.display(value);
            boolean on = setting.type() == Setting.Type.TOGGLE && value >= 0.5;
            boolean arrows = setting.type() == Setting.Type.NUMBER || setting.type() == Setting.Type.CHOICE;
            drawRow(context, index++, setting.label(), display, on, setting.type() == Setting.Type.TOGGLE,
                    mouseX, mouseY, arrows);
        }
        if (rows.isEmpty() && !module.toggleable()) {
            context.drawText(textRenderer, "No settings.", panelX + 12, rowY(0) + 6, Hud.MUTED, false);
        }
        String hint = listening != null ? "Esc cancels  |  Backspace clears" : "";
        if (!hint.isEmpty()) {
            context.drawText(textRenderer, hint, panelX + (PANEL_WIDTH - textRenderer.getWidth(hint)) / 2,
                    resetButton.y() - 12, Hud.MUTED, false);
        }
        ModMenuScreen.drawButton(context, textRenderer, resetButton, "RESET", 0xFF482332, mouseX, mouseY);
        ModMenuScreen.drawButton(context, textRenderer, backButton, "BACK", Hud.ACCENT, mouseX, mouseY);
    }

    private void drawRow(DrawContext context, int index, String label, String value, boolean on, boolean toggle,
                         int mouseX, int mouseY, boolean arrows) {
        int y = rowY(index);
        context.drawText(textRenderer, label, panelX + 12, y + 6, 0xFFF3F6FC, false);
        Area control = control(index);
        boolean hovered = control.contains(mouseX, mouseY);
        int color = toggle ? (on ? 0xFF244A38 : 0xFF482332) : 0xFF11263D;
        context.fill(control.x(), control.y(), control.x() + control.width(), control.y() + control.height(), color);
        if (hovered) {
            context.fill(control.x(), control.y(), control.x() + control.width(), control.y() + control.height(),
                    0x25FFFFFF);
        }
        int textColor = toggle ? (on ? 0xFF7CF29A : 0xFFFF8F8F) : 0xFFEAF9FF;
        String shown = textRenderer.trimToWidth(value, control.width() - (arrows ? 24 : 8));
        context.drawText(textRenderer, shown, control.x() + (control.width() - textRenderer.getWidth(shown)) / 2,
                control.y() + (control.height() - 8) / 2, textColor, false);
        if (arrows) {
            context.drawText(textRenderer, "<", control.x() + 4, control.y() + (control.height() - 8) / 2,
                    Hud.ACCENT, false);
            context.drawText(textRenderer, ">", control.x() + control.width() - 4 - textRenderer.getWidth(">"),
                    control.y() + (control.height() - 8) / 2, Hud.ACCENT, false);
        }
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        if (click.button() != GLFW.GLFW_MOUSE_BUTTON_LEFT) return super.mouseClicked(click, doubled);
        double x = click.comp_4798();
        double y = click.comp_4799();
        listening = null;
        int index = 0;
        if (module.toggleable()) {
            if (control(index).contains(x, y)) {
                VConfig.setEnabled(module, !VConfig.enabled(module));
                applySideEffects();
                return true;
            }
            index++;
        }
        for (Setting setting : rows) {
            Area control = control(index++);
            if (!control.contains(x, y)) continue;
            double value = VConfig.get(module, setting);
            switch (setting.type()) {
                case TOGGLE -> VConfig.set(module, setting, value >= 0.5 ? 0 : 1);
                case KEY -> listening = setting;
                case NUMBER, CHOICE -> VConfig.set(module, setting,
                        x < control.x() + control.width() / 2.0 ? setting.previous(value) : setting.next(value));
            }
            applySideEffects();
            return true;
        }
        if (resetButton.contains(x, y)) {
            VConfig.resetSettings(module);
            return true;
        }
        if (backButton.contains(x, y)) {
            close();
            return true;
        }
        return super.mouseClicked(click, doubled);
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        if (listening != null) {
            int key = input.getKeycode();
            if (key == GLFW.GLFW_KEY_BACKSPACE || key == GLFW.GLFW_KEY_DELETE) {
                // The mod menu key can never be cleared, or the menu would be unreachable.
                boolean menuKey = module == Module.CLIENT && listening.id().equals("menuKey");
                VConfig.set(module, listening, menuKey ? listening.defaultValue() : Setting.NO_KEY);
            } else if (key != GLFW.GLFW_KEY_ESCAPE) {
                VConfig.set(module, listening, key);
            }
            listening = null;
            return true;
        }
        return super.keyPressed(input);
    }

    private void applySideEffects() {
        if (module == Module.TOGGLE_SPRINT || module == Module.TOGGLE_SNEAK) VClientMod.applyMovementToggles(client);
    }

    @Override
    public void close() {
        client.setScreen(parent);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
