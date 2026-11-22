package com.vclient.mod;

import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * The VYRN menu (Right Shift): a frozen-glass panel with a sidebar (Home, Cosmetics, Mods, FrostBoost,
 * Recordings, Screenshots, Profile, Settings). The Mods page groups every module into categories.
 */
public final class ModMenuScreen extends Screen {
    private static final int CARD_WIDTH = 118;
    private static final int CARD_HEIGHT = 32;
    private static final int CARD_GAP = 6;
    private static final int BUTTON_HEIGHT = 18;
    private static final int GEAR = 14;
    private static final int SIDEBAR = 96;
    private static final int TAB_HEIGHT = 16;

    record Area(int x, int y, int width, int height) {
        boolean contains(double pointX, double pointY) {
            return pointX >= x && pointX < x + width && pointY >= y && pointY < y + height;
        }
    }

    private enum PageId {
        HOME("❄", "Home"), COSMETICS("✦", "Cosmetics"), MODS("▣", "Mods"), FROSTBOOST("❄", "FrostBoost"),
        RECORDINGS("●", "Recordings"), SCREENSHOTS("▤", "Screenshots"), PROFILE("☺", "Profile"), SETTINGS("⚙", "Settings");

        final String icon;
        final String label;

        PageId(String icon, String label) {
            this.icon = icon;
            this.label = label;
        }
    }

    private enum Category {
        COMBAT("Combat", Module.CPS),
        RENDER("Render", Module.FULLBRIGHT, Module.NO_HURT_CAM, Module.MENU_BACKGROUND),
        PLAYER("Player", Module.ARMOR_STATUS, Module.COORDINATES, Module.POTION_EFFECTS, Module.DURABILITY,
                Module.ITEM_COUNT, Module.LAST_DEATH, Module.BIOME, Module.LIGHT_LEVEL, Module.DAY_COUNTER),
        MOVEMENT("Movement", Module.TOGGLE_SPRINT, Module.TOGGLE_SNEAK, Module.ZOOM, Module.SPEED),
        HUD("HUD", Module.FPS, Module.KEYSTROKES, Module.CPS, Module.PING, Module.CLOCK, Module.SERVER_ADDRESS),
        PERFORMANCE("Performance", Module.MEMORY, Module.FPS),
        FUN("Fun", Module.COSMETICS, Module.CLIENT);

        final String label;
        final List<Module> modules;

        Category(String label, Module... modules) {
            this.label = label;
            this.modules = List.of(modules);
        }
    }

    private PageId page = PageId.HOME;
    private Category category = Category.HUD;
    private long pageSince = System.nanoTime();
    private int panelX;
    private int panelY;
    private int panelWidth;
    private int panelHeight;
    private int columns;
    private Area content;
    private Area grid;
    private int gridContentHeight;
    private int scroll;
    private final List<Area> sidebarAreas = new ArrayList<>();
    private final List<Area> tabAreas = new ArrayList<>();
    private Area editButton;
    private Area resetButton;
    private Area menuKeyButton;
    private Area frostButton;
    private Area doneButton;

    public ModMenuScreen() {
        super(Text.literal("VYRN"));
    }

    @Override
    protected void init() {
        panelWidth = Math.min(width - 16, 540);
        panelHeight = Math.min(height - 12, 270);
        panelX = (width - panelWidth) / 2;
        panelY = (height - panelHeight) / 2;
        content = new Area(panelX + SIDEBAR + 10, panelY + 34, panelWidth - SIDEBAR - 20, panelHeight - 46);

        sidebarAreas.clear();
        int rowHeight = Math.min(22, (panelHeight - 54) / PageId.values().length);
        for (int index = 0; index < PageId.values().length; index++) {
            sidebarAreas.add(new Area(panelX + 8, panelY + 34 + index * (rowHeight + 2), SIDEBAR - 8, rowHeight));
        }

        columns = 1; // Lunar-style list: one module per row
        layoutMods();

        int buttonWidth = Math.min(150, content.width());
        int top = content.y() + 24;
        editButton = new Area(content.x(), top, buttonWidth, BUTTON_HEIGHT);
        resetButton = new Area(content.x(), top + 24, buttonWidth, BUTTON_HEIGHT);
        menuKeyButton = new Area(content.x(), top + 48, buttonWidth, BUTTON_HEIGHT);
        frostButton = new Area(content.x(), top + 72, buttonWidth, BUTTON_HEIGHT);
        doneButton = new Area(panelX + panelWidth - 78, panelY + panelHeight - 24, 70, BUTTON_HEIGHT);
    }

    private void layoutMods() {
        tabAreas.clear();
        int x = content.x();
        for (Category entry : Category.values()) {
            int tabWidth = textRenderer.getWidth(entry.label) + 12;
            tabAreas.add(new Area(x, content.y(), tabWidth, TAB_HEIGHT));
            x += tabWidth + 3;
        }
        List<Module> modules = category.modules;
        int rows = Math.max(1, (modules.size() + columns - 1) / columns);
        gridContentHeight = rows * CARD_HEIGHT + (rows - 1) * CARD_GAP;
        grid = new Area(content.x(), content.y() + TAB_HEIGHT + 8, content.width(),
                content.height() - TAB_HEIGHT - 8);
        scroll = Math.clamp(scroll, 0, maxScroll());
    }

    private int maxScroll() {
        return Math.max(0, gridContentHeight - grid.height());
    }

    private Area card(int index) {
        int column = index % columns;
        int row = index / columns;
        return new Area(grid.x() + column * (CARD_WIDTH + CARD_GAP),
                grid.y() + row * (CARD_HEIGHT + CARD_GAP) - scroll, grid.width() - 4, CARD_HEIGHT);
    }

    private static Area gear(Area card) {
        return new Area(card.x() + card.width() - GEAR - 8, card.y() + (card.height() - GEAR + 2) / 2, GEAR, GEAR - 2);
    }

    private void open(PageId target) {
        if (target == PageId.COSMETICS) {
            com.vclient.mod.cosmetics.Cosmetics.openMenu(client, this);
            return;
        }
        if (target == PageId.SCREENSHOTS) {
            openFolder(net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().resolve("screenshots"));
        }
        page = target;
        pageSince = System.nanoTime();
        scroll = 0;
    }

    private static void openFolder(java.nio.file.Path folder) {
        try {
            java.nio.file.Files.createDirectories(folder);
            net.minecraft.util.Util.getOperatingSystem().open(folder.toUri());
        } catch (java.io.IOException | RuntimeException e) {
            VClientMod.LOGGER.warn("Could not open {}: {}", folder, e.toString());
        }
    }

    // ---- drawing ---------------------------------------------------------------------------------------

    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, width, height, 0x90071321);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        double time = System.nanoTime() / 1e9;

        context.fill(panelX, panelY, panelX + panelWidth, panelY + panelHeight, 0xEE0B1B30);
        context.fill(panelX, panelY, panelX + SIDEBAR, panelY + panelHeight, 0x66071321);
        drawAuroraBorder(context, time);
        drawSnow(context, time);

        context.getMatrices().pushMatrix();
        context.getMatrices().translate(panelX + 10, panelY + 9);
        context.getMatrices().scale(1.4f, 1.4f);
        context.drawText(textRenderer, "❄ VSMP", 0, 0, Hud.ACCENT, true);
        context.getMatrices().popMatrix();
        context.drawText(textRenderer, "Play Beyond The Frost.", panelX + SIDEBAR + 10, panelY + 14, 0xFF7CEBFF, false);
        String hint = KeyNames.name(VConfig.integer(Module.CLIENT, "menuKey")) + " to close";
        context.drawText(textRenderer, hint, panelX + panelWidth - 10 - textRenderer.getWidth(hint), panelY + 14,
                0xFF6F9BB5, false);

        for (int index = 0; index < sidebarAreas.size(); index++) {
            PageId entry = PageId.values()[index];
            Area area = sidebarAreas.get(index);
            boolean selected = entry == page;
            boolean hovered = area.contains(mouseX, mouseY);
            if (selected) {
                context.fill(area.x(), area.y(), area.x() + area.width(), area.y() + area.height(), 0x4400CFFF);
                context.fill(area.x(), area.y(), area.x() + 2, area.y() + area.height(), Hud.ACCENT);
            } else if (hovered) {
                context.fill(area.x(), area.y(), area.x() + area.width(), area.y() + area.height(), 0x2A7CEBFF);
            }
            int offset = hovered && !selected ? 2 : 0; // hover nudge
            context.drawText(textRenderer, entry.icon + " " + entry.label, area.x() + 8 + offset,
                    area.y() + (area.height() - 8) / 2, selected ? 0xFFFFFFFF : hovered ? 0xFFB8F4FF : 0xFFA9D2E8, false);
        }

        // Page transition: the content slides in over a fifth of a second.
        double progress = Math.min(1.0, (System.nanoTime() - pageSince) / 220_000_000.0);
        int slide = (int) ((1 - progress) * 8);
        context.getMatrices().pushMatrix();
        context.getMatrices().translate(slide, 0);
        context.enableScissor(content.x() - 2, content.y() - 2, content.x() + content.width() + 2, content.y() + content.height() + 2);
        switch (page) {
            case HOME -> drawHome(context);
            case MODS -> drawMods(context, mouseX, mouseY);
            case FROSTBOOST -> drawFrostBoost(context);
            case RECORDINGS -> drawInfo(context, "❄ Frozen Memories",
                    "Recording gallery, clip manager and favorites live in the VSMP launcher.",
                    "Use your clip hotkey in game to save the last 30 seconds; the launcher imports it.");
            case SCREENSHOTS -> drawInfo(context, "Screenshots",
                    "Your screenshots folder was opened.", "Press F2 in game to take one. The launcher shows them too.");
            case PROFILE -> drawProfile(context);
            case SETTINGS -> drawSettings(context, mouseX, mouseY);
            default -> { }
        }
        context.disableScissor();
        context.getMatrices().popMatrix();
        drawButton(context, textRenderer, doneButton, "DONE", Hud.ACCENT, mouseX, mouseY);
    }

    /** A thin border whose colour slides between ice blue and aurora green. */
    private void drawAuroraBorder(DrawContext context, double time) {
        int segments = 48;
        for (int segment = 0; segment < segments; segment++) {
            double wave = 0.5 + 0.5 * Math.sin(time * 1.1 + segment * 0.35);
            int red = (int) (92 * wave);
            int green = (int) (207 + 48 * wave);
            int blue = (int) (255 - 55 * wave);
            int color = 0xCC000000 | (red << 16) | (green << 8) | blue;
            int x1 = panelX + panelWidth * segment / segments;
            int x2 = panelX + panelWidth * (segment + 1) / segments;
            context.fill(x1, panelY, x2, panelY + 1, color);
            context.fill(x1, panelY + panelHeight - 1, x2, panelY + panelHeight, color);
        }
        context.fill(panelX, panelY, panelX + 1, panelY + panelHeight, 0x887CEBFF);
        context.fill(panelX + panelWidth - 1, panelY, panelX + panelWidth, panelY + panelHeight, 0x887CEBFF);
    }

    private void drawSnow(DrawContext context, double time) {
        for (int index = 0; index < 18; index++) {
            double fall = (index * 0.137 + time * (0.05 + (index % 5) * 0.012)) % 1.0;
            int x = panelX + 2 + (int) (((index * 0.6180339 + Math.sin(time * 0.7 + index) * 0.01) % 1.0) * (panelWidth - 4));
            int y = panelY + 2 + (int) (fall * (panelHeight - 4));
            int size = 1 + index % 2;
            context.fill(x, y, x + size, y + size, 0x66E2F7FF);
        }
    }

    private void drawHome(DrawContext context) {
        context.drawText(textRenderer, "Welcome to VYRN", content.x(), content.y() + 2, 0xFFEAF9FF, true);
        context.drawText(textRenderer, "Ice. Snow. Frost. Glaciers. Blizzards. Aurora.", content.x(), content.y() + 16,
                0xFF7CEBFF, false);
        String[] live = PerfProbe.liveLines();
        for (int index = 0; index < live.length; index++) {
            context.drawText(textRenderer, live[index], content.x(), content.y() + 40 + index * 12, 0xFFC4E6F5, false);
        }
        context.drawText(textRenderer, "Pick a page on the left. Mods has every HUD and feature by category.",
                content.x(), content.y() + 40 + live.length * 12 + 12, 0xFF6F9BB5, false);
    }

    private void drawFrostBoost(DrawContext context) {
        context.drawText(textRenderer, "❄ FrostBoost", content.x(), content.y() + 2, 0xFFEAF9FF, true);
        context.drawText(textRenderer, "Live performance this session", content.x(), content.y() + 16, 0xFF7CEBFF, false);
        String[] live = PerfProbe.liveLines();
        for (int index = 0; index < live.length; index++) {
            context.drawText(textRenderer, live[index], content.x(), content.y() + 36 + index * 12, 0xFFC4E6F5, false);
        }
        int y = content.y() + 36 + live.length * 12 + 10;
        context.drawText(textRenderer, "Profiles: Glacier (max FPS), Blizzard (PvP), Aurora (balanced),", content.x(), y, 0xFF86B3CE, false);
        context.drawText(textRenderer, "Crystal (best quality). Change them in the launcher's FrostBoost page.", content.x(), y + 11, 0xFF86B3CE, false);
    }

    private void drawInfo(DrawContext context, String title, String line1, String line2) {
        context.drawText(textRenderer, title, content.x(), content.y() + 2, 0xFFEAF9FF, true);
        context.drawText(textRenderer, textRenderer.trimToWidth(line1, content.width()), content.x(), content.y() + 22, 0xFFC4E6F5, false);
        context.drawText(textRenderer, textRenderer.trimToWidth(line2, content.width()), content.x(), content.y() + 36, 0xFF86B3CE, false);
    }

    private void drawProfile(DrawContext context) {
        String name = client.getSession() == null ? "Player" : client.getSession().getUsername();
        context.drawText(textRenderer, "☺ " + name, content.x(), content.y() + 2, 0xFFEAF9FF, true);
        String[] live = PerfProbe.liveLines();
        for (int index = 0; index < live.length; index++) {
            context.drawText(textRenderer, live[index], content.x(), content.y() + 22 + index * 12, 0xFFC4E6F5, false);
        }
    }

    private void drawSettings(DrawContext context, int mouseX, int mouseY) {
        context.drawText(textRenderer, "Settings", content.x(), content.y() + 2, 0xFFEAF9FF, true);
        drawButton(context, textRenderer, editButton, "EDIT HUD", 0xFF11263D, mouseX, mouseY);
        drawButton(context, textRenderer, resetButton, "RESET HUD", 0xFF482332, mouseX, mouseY);
        drawButton(context, textRenderer, menuKeyButton, "MENU KEY", 0xFF11263D, mouseX, mouseY);
        drawButton(context, textRenderer, frostButton, "FROST MENUS", 0xFF11263D, mouseX, mouseY);
    }

    private void drawMods(DrawContext context, int mouseX, int mouseY) {
        for (int index = 0; index < tabAreas.size(); index++) {
            Category entry = Category.values()[index];
            Area tab = tabAreas.get(index);
            boolean selected = entry == category;
            boolean hovered = tab.contains(mouseX, mouseY);
            context.fill(tab.x(), tab.y(), tab.x() + tab.width(), tab.y() + tab.height(),
                    selected ? 0xFF0B5C78 : hovered ? 0xFF173553 : 0xFF0E2238);
            if (selected) context.fill(tab.x(), tab.y() + tab.height() - 1, tab.x() + tab.width(), tab.y() + tab.height(), Hud.ACCENT);
            context.drawText(textRenderer, entry.label, tab.x() + 6, tab.y() + 4, selected ? 0xFFFFFFFF : 0xFFA9D2E8, false);
        }
        boolean overGrid = grid.contains(mouseX, mouseY);
        context.enableScissor(grid.x(), grid.y(), grid.x() + grid.width(), grid.y() + grid.height());
        List<Module> modules = category.modules;
        for (int index = 0; index < modules.size(); index++) {
            Area area = card(index);
            if (area.y() + area.height() < grid.y() || area.y() > grid.y() + grid.height()) continue;
            drawCard(context, modules.get(index), area, overGrid ? mouseX : -1, overGrid ? mouseY : -1);
        }
        context.disableScissor();
    }

    private void drawCard(DrawContext context, Module module, Area area, int mouseX, int mouseY) {
        boolean enabled = VConfig.enabled(module);
        boolean hovered = area.contains(mouseX, mouseY);
        Area gear = gear(area);
        int right = area.x() + area.width();
        context.fill(area.x(), area.y(), right, area.y() + area.height(), hovered ? 0xCC173553 : 0xAA0E2238);
        context.fill(area.x(), area.y(), area.x() + 2, area.y() + area.height(), enabled ? Hud.ACCENT : 0x447CEBFF);

        // Icon tile with the module's initial, like the pack icons in Lunar's mod list.
        int tile = area.height() - 8;
        int tileX = area.x() + 8;
        int tileY = area.y() + 4;
        context.fill(tileX, tileY, tileX + tile, tileY + tile, enabled ? 0xFF0B5C78 : 0xFF11263D);
        context.drawStrokedRectangle(tileX, tileY, tile, tile, enabled ? 0xAAB8F4FF : 0x447CEBFF);
        String initial = module.title().substring(0, 1).toUpperCase();
        context.drawText(textRenderer, initial, tileX + (tile - textRenderer.getWidth(initial)) / 2, tileY + (tile - 8) / 2,
                enabled ? 0xFFFFFFFF : 0xFF86B3CE, false);

        int textX = tileX + tile + 8;
        int textWidth = right - textX - GEAR - 48;
        context.drawText(textRenderer, textRenderer.trimToWidth(module.title(), textWidth), textX, area.y() + 6, 0xFFEAF9FF, false);
        context.drawText(textRenderer, textRenderer.trimToWidth(module.description(), textWidth), textX, area.y() + 17, 0xFF86B3CE, false);

        boolean gearHovered = gear.contains(mouseX, mouseY);
        context.fill(gear.x(), gear.y(), gear.x() + gear.width(), gear.y() + gear.height(), gearHovered ? 0xFF1E4260 : 0xFF11263D);
        context.drawText(textRenderer, "⚙", gear.x() + (gear.width() - textRenderer.getWidth("⚙")) / 2,
                gear.y() + (gear.height() - 8) / 2, gearHovered ? Hud.ACCENT : 0xFFC4E6F5, false);

        if (module.toggleable()) {
            // Toggle switch: a track with a knob that sits right when on.
            int switchWidth = 26;
            int switchHeight = 12;
            int switchX = gear.x() - switchWidth - 8;
            int switchY = area.y() + (area.height() - switchHeight) / 2;
            context.fill(switchX, switchY, switchX + switchWidth, switchY + switchHeight, enabled ? 0xFF00A6CC : 0xFF1B2F44);
            context.drawStrokedRectangle(switchX, switchY, switchWidth, switchHeight, enabled ? 0xFFB8F4FF : 0x557CEBFF);
            int knobX = enabled ? switchX + switchWidth - switchHeight + 1 : switchX + 1;
            context.fill(knobX, switchY + 1, knobX + switchHeight - 2, switchY + switchHeight - 1, enabled ? 0xFFFFFFFF : 0xFF86B3CE);
        }
    }

    static void drawButton(DrawContext context, net.minecraft.client.font.TextRenderer font, Area area,
                           String label, int color, int mouseX, int mouseY) {
        boolean hovered = area.contains(mouseX, mouseY);
        context.fill(area.x(), area.y(), area.x() + area.width(), area.y() + area.height(), color);
        // Crystal edge: a bright top line, a faint frost glow on hover.
        context.fill(area.x(), area.y(), area.x() + area.width(), area.y() + 1, 0x88FFFFFF);
        if (hovered) {
            context.fill(area.x(), area.y(), area.x() + area.width(), area.y() + area.height(), 0x30B8F4FF);
            context.drawStrokedRectangle(area.x() - 1, area.y() - 1, area.width() + 2, area.height() + 2, 0x667CEBFF);
        }
        int textColor = color == Hud.ACCENT ? 0xFF071321 : 0xFFEAF9FF;
        context.drawText(font, label, area.x() + (area.width() - font.getWidth(label)) / 2,
                area.y() + (area.height() - 8) / 2, textColor, false);
    }

    // ---- input -----------------------------------------------------------------------------------------

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        if (click.button() != GLFW.GLFW_MOUSE_BUTTON_LEFT) return super.mouseClicked(click, doubled);
        double x = click.comp_4798();
        double y = click.comp_4799();
        if (doneButton.contains(x, y)) {
            close();
            return true;
        }
        for (int index = 0; index < sidebarAreas.size(); index++) {
            if (sidebarAreas.get(index).contains(x, y)) {
                open(PageId.values()[index]);
                return true;
            }
        }
        if (page == PageId.MODS) {
            for (int index = 0; index < tabAreas.size(); index++) {
                if (tabAreas.get(index).contains(x, y)) {
                    category = Category.values()[index];
                    scroll = 0;
                    layoutMods();
                    return true;
                }
            }
            if (grid.contains(x, y)) {
                List<Module> modules = category.modules;
                for (int index = 0; index < modules.size(); index++) {
                    Area area = card(index);
                    if (!area.contains(x, y)) continue;
                    Module module = modules.get(index);
                    if (gear(area).contains(x, y) || !module.toggleable()) {
                        client.setScreen(new ModuleSettingsScreen(this, module));
                        return true;
                    }
                    VConfig.setEnabled(module, !VConfig.enabled(module));
                    if (module == Module.TOGGLE_SPRINT || module == Module.TOGGLE_SNEAK) {
                        VClientMod.applyMovementToggles(client);
                    }
                    return true;
                }
            }
        }
        if (page == PageId.SETTINGS) {
            if (editButton.contains(x, y)) {
                client.setScreen(new HudEditorScreen(this));
                return true;
            }
            if (resetButton.contains(x, y)) {
                VConfig.resetPositions();
                return true;
            }
            if (menuKeyButton.contains(x, y)) {
                client.setScreen(new ModuleSettingsScreen(this, Module.CLIENT));
                return true;
            }
            if (frostButton.contains(x, y)) {
                client.setScreen(new ModuleSettingsScreen(this, Module.MENU_BACKGROUND));
                return true;
            }
        }
        return super.mouseClicked(click, doubled);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        if (page == PageId.MODS) scroll = Math.clamp(scroll - (int) Math.round(vertical * 24), 0, maxScroll());
        return true;
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        if (input.getKeycode() == VConfig.integer(Module.CLIENT, "menuKey")) {
            close();
            return true;
        }
        return super.keyPressed(input);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
