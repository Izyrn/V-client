package com.vclient.mod;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;
import net.minecraft.client.gui.screen.option.OptionsScreen;
import net.minecraft.client.gui.screen.world.SelectWorldScreen;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * VSMP's title screen: the frozen sky behind a centred snowflake mark, three wide buttons (Singleplayer,
 * Multiplayer, Cosmetics) and a row of small round-ish icon buttons (Options, Mods, FrostBoost, Cosmetics, Quit).
 * It replaces Minecraft's title screen; if anything goes wrong it switches itself off and the vanilla one returns.
 */
public final class FrostTitleScreen extends Screen {
    private static boolean disabled;

    private record Hit(ModMenuScreen.Area area, String label, Runnable action) {}

    private int markY;
    private final List<Hit> wide = new ArrayList<>();
    private final List<Hit> icons = new ArrayList<>();

    private FrostTitleScreen() {
        super(Text.literal("VSMP Client"));
    }

    /** Called every tick: swaps Minecraft's title screen for this one. */
    static void replaceTitle(MinecraftClient client) {
        if (disabled || !(client.currentScreen instanceof TitleScreen)) return;
        try {
            client.setScreen(new FrostTitleScreen());
        } catch (RuntimeException e) {
            disabled = true;
            VClientMod.LOGGER.warn("The VSMP title screen could not open; using the vanilla one: {}", e.toString());
        }
    }

    @Override
    protected void init() {
        wide.clear();
        icons.clear();
        int buttonWidth = 150;
        int buttonHeight = 18;
        int centreX = width / 2;
        // Buttons sit below the logo block (mark, name, slogan); on short windows everything moves up together.
        int top = Math.min(height / 2 + 12, height - 44 - 66);
        markY = top - 108;
        wide.add(new Hit(new ModMenuScreen.Area(centreX - buttonWidth / 2, top, buttonWidth, buttonHeight), "SINGLEPLAYER",
                () -> client.setScreen(new SelectWorldScreen(this))));
        wide.add(new Hit(new ModMenuScreen.Area(centreX - buttonWidth / 2, top + 24, buttonWidth, buttonHeight), "MULTIPLAYER",
                () -> client.setScreen(new MultiplayerScreen(this))));
        wide.add(new Hit(new ModMenuScreen.Area(centreX - buttonWidth / 2, top + 48, buttonWidth, buttonHeight), "COSMETICS",
                () -> com.vclient.mod.cosmetics.Cosmetics.openMenu(client, this)));

        String[][] entries = {{"⚙", "Options"}, {"▣", "Mods"}, {"❄", "FrostBoost"}, {"✦", "Cosmetics"}, {"✕", "Quit game"}};
        Runnable[] actions = {
                () -> client.setScreen(new OptionsScreen(this, client.options)),
                () -> client.setScreen(new ModMenuScreen()),
                () -> client.setScreen(new ModMenuScreen()),
                () -> com.vclient.mod.cosmetics.Cosmetics.openMenu(client, this),
                client::scheduleStop};
        int size = 20;
        int gap = 8;
        int total = entries.length * size + (entries.length - 1) * gap;
        int x = centreX - total / 2;
        for (int index = 0; index < entries.length; index++) {
            icons.add(new Hit(new ModMenuScreen.Area(x + index * (size + gap), height - 34, size, size),
                    entries[index][0] + "|" + entries[index][1], actions[index]));
        }
    }

    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, width, height, 0xFF071321);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        NightSky.render(context);
        context.fill(0, 0, width, height, 0x55071321); // soften the sky so the buttons read clearly
        double time = System.nanoTime() / 1e9;
        int centreX = width / 2;


        // Snowflake mark with a slow cold glow behind it.
        int glow = (int) (0x22 + 0x12 * Math.sin(time * 1.2));
        for (int ring = 4; ring >= 1; ring--) {
            int radius = 22 + ring * 7;
            context.fill(centreX - radius, markY + 30 - radius, centreX + radius, markY + 30 + radius, (glow / ring) << 24 | 0x0000CFFF);
        }
        context.getMatrices().pushMatrix();
        context.getMatrices().translate(centreX, markY + 14);
        context.getMatrices().scale(4.0f, 4.0f);
        context.drawText(textRenderer, "❄", -textRenderer.getWidth("❄") / 2, 0, 0xFFB8F4FF, true);
        context.getMatrices().popMatrix();

        context.getMatrices().pushMatrix();
        context.getMatrices().translate(centreX, markY + 66);
        context.getMatrices().scale(1.3f, 1.3f);
        String name = "VSMP CLIENT";
        context.drawText(textRenderer, name, -textRenderer.getWidth(name) / 2, 0, 0xFFEAF9FF, true);
        context.getMatrices().popMatrix();
        String slogan = "Play Beyond The Frost.";
        context.drawText(textRenderer, slogan, centreX - textRenderer.getWidth(slogan) / 2, markY + 82, 0xFF7CEBFF, false);

        for (int index = 0; index < wide.size(); index++) {
            Hit hit = wide.get(index);
            boolean hovered = hit.area().contains(mouseX, mouseY);
            boolean accent = index == wide.size() - 1;
            int base = accent ? 0x8800A6CC : 0x88173553;
            context.fill(hit.area().x(), hit.area().y(), hit.area().x() + hit.area().width(),
                    hit.area().y() + hit.area().height(), hovered ? (accent ? 0xCC00CFFF : 0xCC1E4260) : base);
            context.drawStrokedRectangle(hit.area().x(), hit.area().y(), hit.area().width(), hit.area().height(),
                    hovered ? 0xCCB8F4FF : 0x557CEBFF);
            context.drawText(textRenderer, hit.label(), hit.area().x() + (hit.area().width() - textRenderer.getWidth(hit.label())) / 2,
                    hit.area().y() + 5, 0xFFEAF9FF, false);
        }

        String tooltip = null;
        for (Hit hit : icons) {
            String[] parts = hit.label().split("\\|", 2);
            boolean hovered = hit.area().contains(mouseX, mouseY);
            context.fill(hit.area().x(), hit.area().y(), hit.area().x() + hit.area().width(),
                    hit.area().y() + hit.area().height(), hovered ? 0xCC1E4260 : 0x88173553);
            context.drawStrokedRectangle(hit.area().x(), hit.area().y(), hit.area().width(), hit.area().height(),
                    hovered ? 0xCCB8F4FF : 0x447CEBFF);
            context.drawText(textRenderer, parts[0], hit.area().x() + (hit.area().width() - textRenderer.getWidth(parts[0])) / 2,
                    hit.area().y() + 6, hovered ? 0xFFFFFFFF : 0xFFC4E6F5, false);
            if (hovered) tooltip = parts[1];
        }
        if (tooltip != null) {
            context.drawText(textRenderer, tooltip, centreX - textRenderer.getWidth(tooltip) / 2, height - 48, 0xFFB8F4FF, true);
        }
        String version = "VSMP Client";
        context.drawText(textRenderer, version, 6, height - 12, 0xFF6F9BB5, false);
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        if (click.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            double x = click.comp_4798();
            double y = click.comp_4799();
            for (Hit hit : wide) {
                if (hit.area().contains(x, y)) {
                    hit.action().run();
                    return true;
                }
            }
            for (Hit hit : icons) {
                if (hit.area().contains(x, y)) {
                    hit.action().run();
                    return true;
                }
            }
        }
        return super.mouseClicked(click, doubled);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }
}
