package com.vclient.mod;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

import java.util.List;
import java.util.Locale;

/** The Waypoints menu (N): every waypoint in this world and dimension with its distance, add one here, delete any. */
public final class WaypointsScreen extends Screen {
    private static final int ROW = 22;
    private static final int PER_PAGE = 8;
    private int page;

    public WaypointsScreen() {
        super(Text.literal("Waypoints"));
    }

    @Override
    protected void init() {
        clearChildren();
        int panelWidth = 300;
        int left = (width - panelWidth) / 2;
        int top = height / 2 - 110;
        List<Waypoints.Waypoint> points = client == null ? List.of() : Waypoints.current(client);
        int pages = Math.max(1, (points.size() + PER_PAGE - 1) / PER_PAGE);
        page = Math.clamp(page, 0, pages - 1);
        for (int i = 0; i < PER_PAGE; i++) {
            int index = page * PER_PAGE + i;
            if (index >= points.size()) break;
            Waypoints.Waypoint point = points.get(index);
            addDrawableChild(ButtonWidget.builder(Text.literal("Delete"), button -> {
                Waypoints.remove(client, point);
                init();
            }).dimensions(left + panelWidth - 58, top + 30 + i * ROW, 54, 18).build());
        }
        int bottom = top + 30 + PER_PAGE * ROW + 6;
        addDrawableChild(ButtonWidget.builder(Text.literal("Add waypoint here"), button -> {
            Waypoints.addHere(client);
            init();
        }).dimensions(left, bottom, 140, 20).build());
        if (pages > 1) {
            addDrawableChild(ButtonWidget.builder(Text.literal("<"), button -> {
                page--;
                init();
            }).dimensions(left + 146, bottom, 20, 20).build()).active = page > 0;
            addDrawableChild(ButtonWidget.builder(Text.literal(">"), button -> {
                page++;
                init();
            }).dimensions(left + 170, bottom, 20, 20).build()).active = page < pages - 1;
        }
        addDrawableChild(ButtonWidget.builder(Text.literal("Done"), button -> close())
                .dimensions(left + panelWidth - 80, bottom, 80, 20).build());
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        int panelWidth = 300;
        int left = (width - panelWidth) / 2;
        int top = height / 2 - 110;
        context.drawText(textRenderer, "Waypoints", left, top + 8, Hud.ACCENT, true);
        context.drawText(textRenderer, "Markers show in the world and on the minimap", left + 70, top + 8, Hud.MUTED, false);
        List<Waypoints.Waypoint> points = client == null ? List.of() : Waypoints.current(client);
        if (points.isEmpty()) {
            context.drawText(textRenderer, "No waypoints in this world yet. Press \"Add waypoint here\".", left, top + 36, Hud.TEXT, true);
            return;
        }
        for (int i = 0; i < PER_PAGE; i++) {
            int index = page * PER_PAGE + i;
            if (index >= points.size()) break;
            Waypoints.Waypoint point = points.get(index);
            int y = top + 30 + i * ROW;
            context.fill(left, y, left + panelWidth - 62, y + 18, 0x900C1F33);
            context.fill(left, y, left + 3, y + 18, point.color());
            String distance = client.player == null ? "" : String.format(Locale.ROOT, "%dm",
                    Math.round(Math.sqrt(client.player.squaredDistanceTo(point.x() + 0.5, point.y(), point.z() + 0.5))));
            context.drawText(textRenderer, point.name(), left + 8, y + 5, Hud.TEXT, true);
            String where = point.x() + " " + point.y() + " " + point.z() + "  " + distance;
            context.drawText(textRenderer, where, left + panelWidth - 66 - textRenderer.getWidth(where), y + 5, Hud.MUTED, false);
        }
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
