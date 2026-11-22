package com.vclient.mod;

import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

/** Drag enabled HUD elements to move them; positions are saved when the editor closes. */
public final class HudEditorScreen extends Screen {
    private final Screen parent;
    private Module dragging;
    private double grabOffsetX;
    private double grabOffsetY;

    public HudEditorScreen(Screen parent) {
        super(Text.literal("Edit HUD Layout"));
        this.parent = parent;
    }

    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, width, height, 0x50080918);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        for (Module module : Module.values()) {
            if (!module.hudElement() || !VConfig.enabled(module) || Safe.broken(module.title())) continue;
            Safe.run(module.title(), () -> {
                int[] bounds = Hud.bounds(client, module);
                boolean active = module == dragging
                        || dragging == null && inside(bounds, mouseX, mouseY);
                Hud.draw(context, client, module, bounds[0], bounds[1], true);
                context.drawStrokedRectangle(bounds[0] - 1, bounds[1] - 1, bounds[2] + 2, bounds[3] + 2,
                        active ? Hud.ACCENT : 0x80FFFFFF);
            });
        }
        String hint = "Drag elements to move them  |  Esc to save";
        context.drawText(textRenderer, hint, (width - textRenderer.getWidth(hint)) / 2, height / 2 - 4,
                Hud.ACCENT, true);
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        if (click.button() != GLFW.GLFW_MOUSE_BUTTON_LEFT) return super.mouseClicked(click, doubled);
        double x = click.comp_4798();
        double y = click.comp_4799();
        Module[] modules = Module.values();
        for (int index = modules.length - 1; index >= 0; index--) {
            Module module = modules[index];
            if (!module.hudElement() || !VConfig.enabled(module)) continue;
            int[] bounds = Hud.bounds(client, module);
            if (!inside(bounds, x, y)) continue;
            dragging = module;
            grabOffsetX = x - bounds[0];
            grabOffsetY = y - bounds[1];
            return true;
        }
        return super.mouseClicked(click, doubled);
    }

    @Override
    public boolean mouseDragged(Click click, double deltaX, double deltaY) {
        if (dragging == null) return super.mouseDragged(click, deltaX, deltaY);
        VConfig.setPosition(dragging, (int) Math.round(click.comp_4798() - grabOffsetX),
                (int) Math.round(click.comp_4799() - grabOffsetY));
        return true;
    }

    @Override
    public boolean mouseReleased(Click click) {
        if (dragging != null) {
            int[] bounds = Hud.bounds(client, dragging);
            VConfig.setPosition(dragging, bounds[0], bounds[1]);
            dragging = null;
            return true;
        }
        return super.mouseReleased(click);
    }

    @Override
    public void close() {
        VConfig.save();
        client.setScreen(parent);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    private static boolean inside(int[] bounds, double x, double y) {
        return x >= bounds[0] && x < bounds[0] + bounds[2] && y >= bounds[1] && y < bounds[1] + bounds[3];
    }
}
