package com.vclient.mod.cosmetics;

import com.vclient.mod.Module;
import com.vclient.mod.VClientMod;
import com.vclient.mod.VConfig;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.KeyInput;
import net.minecraft.client.render.entity.state.EntityRenderState;
import net.minecraft.client.render.entity.state.LivingEntityRenderState;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * The Cosmetics menu: categories on the left, the cosmetics grid in the middle, and a live preview of the
 * player on the right. Clicking a card previews it on your character (drag the preview to turn it);
 * Equip saves it. Locked items can be previewed but not equipped. Everything here is visual only.
 */
public final class CosmeticsScreen extends Screen {
    private static final int BACKGROUND = 0xEE0B1B30;
    private static final int CARD = 0xFF0E2238;
    private static final int CARD_HOVER = 0xFF173553;
    private static final int OUTLINE = 0x667CEBFF;
    private static final int ACCENT = 0xFF00CFFF;
    private static final int TEXT = 0xFFF3F6FC;
    private static final int MUTED = 0xFF86B3CE;
    private static final int GOOD = 0xFF7CF29A;
    private static final int BAD = 0xFFFF8F8F;
    private static final int CARD_W = 82;
    private static final int CARD_H = 44;
    private static final int GAP = 5;

    private record Rect(int x, int y, int w, int h) {
        boolean contains(double px, double py) {
            return px >= x && px < x + w && py >= y && py < y + h;
        }
    }

    private final Screen parent;
    private CosmeticCategory tab = CosmeticCategory.CAPE;
    private CosmeticTier tierFilter;
    private Cosmetic selected;
    private int scroll;
    private float yaw = 200;
    private boolean draggingPreview;
    private int catalogRevision = -1;
    private List<Cosmetic> visible = List.of();

    private int panelX, panelY, panelW, panelH;
    private int tabsW = 74;
    private int previewW = 122;
    private Rect grid, preview, closeButton, refreshButton, clearButton, diagnoseButton, masterToggle, equipButton, playButton;
    private boolean showDiagnostics;
    private int columns = 1;
    private int contentHeight;

    public CosmeticsScreen(Screen parent) {
        super(Text.literal("VYRN Cosmetics"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        panelW = Math.min(width - 12, 500);
        panelH = Math.min(height - 12, 270);
        panelX = (width - panelW) / 2;
        panelY = (height - panelH) / 2;
        tabsW = panelW < 420 ? 62 : 74;
        previewW = panelW < 420 ? 104 : 122;
        int top = panelY + 56;
        int bottom = panelY + panelH - 30;
        int gridX = panelX + 10 + tabsW + 6;
        int gridW = panelX + panelW - 10 - previewW - 6 - gridX;
        grid = new Rect(gridX, top, gridW, bottom - top);
        columns = Math.max(1, (gridW + GAP) / (CARD_W + GAP));
        preview = new Rect(panelX + panelW - 10 - previewW, panelY + 30, previewW, Math.min(112, panelH - 150));
        int buttonY = panelY + panelH - 24;
        closeButton = new Rect(panelX + panelW - 10 - 64, buttonY, 64, 18);
        refreshButton = new Rect(panelX + 10, buttonY, 64, 18);
        clearButton = new Rect(refreshButton.x() + 68, buttonY, 82, 18);
        diagnoseButton = new Rect(clearButton.x() + 86, buttonY, 72, 18);
        masterToggle = new Rect(panelX + panelW - 10 - 44, panelY + 10, 44, 14);
        int detailBottom = bottom + 2;
        equipButton = new Rect(preview.x(), detailBottom - 18, previewW, 16);
        playButton = new Rect(preview.x(), detailBottom - 38, previewW, 16);
        rebuild();
    }

    private void rebuild() {
        List<Cosmetic> filtered = new ArrayList<>();
        for (Cosmetic cosmetic : CosmeticCatalog.items()) {
            if (cosmetic.category() == tab && (tierFilter == null || cosmetic.tier() == tierFilter)) {
                filtered.add(cosmetic);
            }
        }
        visible = filtered;
        catalogRevision = CosmeticCatalog.revision();
        int rows = (visible.size() + columns - 1) / columns;
        contentHeight = rows == 0 ? 0 : rows * CARD_H + (rows - 1) * GAP;
        scroll = Math.clamp(scroll, 0, maxScroll());
        if (selected != null && CosmeticCatalog.byId(selected.id()) == null) select(null);
    }

    private int maxScroll() {
        return Math.max(0, contentHeight - grid.h());
    }

    private Rect card(int index) {
        return new Rect(grid.x() + (index % columns) * (CARD_W + GAP),
                grid.y() + (index / columns) * (CARD_H + GAP) - scroll, CARD_W, CARD_H);
    }

    private Rect tabRect(int index) {
        int count = CosmeticCategory.values().length;
        int step = Math.max(12, Math.min(22, (panelH - 56 - 30) / count));
        return new Rect(panelX + 10, panelY + 56 + index * step, tabsW, step - 2);
    }

    private Rect chipRect(int index, int count) {
        int chipW = Math.min(52, (grid.w() - (count - 1) * 4) / count);
        return new Rect(grid.x() + index * (chipW + 4), panelY + 32, chipW, 16);
    }

    private void select(Cosmetic cosmetic) {
        selected = cosmetic;
        CosmeticState.setPreview(cosmetic);
        if (cosmetic == null || cosmetic.slot() != CosmeticSlot.EMOTE) Emotes.stop();
    }

    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, width, height, 0x90071321);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        if (catalogRevision != CosmeticCatalog.revision()) rebuild();
        context.fill(panelX, panelY, panelX + panelW, panelY + panelH, BACKGROUND);
        context.drawStrokedRectangle(panelX, panelY, panelW, panelH, OUTLINE);

        context.getMatrices().pushMatrix();
        context.getMatrices().translate(panelX + 10, panelY + 9);
        context.getMatrices().scale(1.5f, 1.5f);
        context.drawText(textRenderer, "VYRN", 0, 0, ACCENT, true);
        context.getMatrices().popMatrix();
        context.drawText(textRenderer, "COSMETICS", panelX + 10 + (int) (textRenderer.getWidth("VYRN") * 1.5f) + 6,
                panelY + 13, 0xFFA9D2E8, false);
        boolean master = CosmeticState.enabled();
        drawPill(context, masterToggle, master ? "ON" : "OFF", master ? 0xFF244A38 : 0xFF482332,
                master ? GOOD : BAD, mouseX, mouseY);
        CosmeticCatalog.State loadState = CosmeticCatalog.state();
        String statusText = (CosmeticNet.debug() ? "[DEBUG] " : "") + CosmeticCatalog.status();
        String status = textRenderer.trimToWidth(statusText, panelW - 190);
        int statusColor = loadState == CosmeticCatalog.State.FAILED ? BAD
                : loadState == CosmeticCatalog.State.LOADING ? ACCENT : 0xFF6F9BB5;
        context.drawText(textRenderer, status, masterToggle.x() - 8 - textRenderer.getWidth(status), panelY + 13,
                statusColor, false);
        if (loadState == CosmeticCatalog.State.FAILED && !CosmeticCatalog.error().isEmpty()) {
            String detail = textRenderer.trimToWidth("Press REFRESH to retry, or DIAGNOSE to see what is wrong.", panelW - 190);
            context.drawText(textRenderer, detail, masterToggle.x() - 8 - textRenderer.getWidth(detail), panelY + 24,
                    0xFF9A7A7A, false);
        }

        drawTabs(context, mouseX, mouseY);
        drawChips(context, mouseX, mouseY);
        drawGrid(context, mouseX, mouseY);
        drawPreview(context, mouseX, mouseY);
        drawDetails(context, mouseX, mouseY);

        drawButton(context, refreshButton, "REFRESH", 0xFF11263D, true, mouseX, mouseY);
        drawButton(context, clearButton, "UNEQUIP ALL", 0xFF482332, true, mouseX, mouseY);
        drawButton(context, diagnoseButton, showDiagnostics ? "HIDE" : "DIAGNOSE", 0xFF11263D, true, mouseX, mouseY);
        drawButton(context, closeButton, "DONE", ACCENT, true, mouseX, mouseY);
    }

    private void drawTabs(DrawContext context, int mouseX, int mouseY) {
        CosmeticCategory[] categories = CosmeticCategory.values();
        for (int i = 0; i < categories.length; i++) {
            Rect rect = tabRect(i);
            boolean active = categories[i] == tab;
            context.fill(rect.x(), rect.y(), rect.x() + rect.w(), rect.y() + rect.h(),
                    active ? CARD_HOVER : rect.contains(mouseX, mouseY) ? 0xFF211F3A : CARD);
            context.drawStrokedRectangle(rect.x(), rect.y(), rect.w(), rect.h(), active ? 0xAA00CFFF : 0x447CEBFF);
            context.drawText(textRenderer, categories[i].label(), rect.x() + 6, rect.y() + (rect.h() - 8) / 2,
                    active ? ACCENT : TEXT, false);
        }
    }

    private void drawChips(DrawContext context, int mouseX, int mouseY) {
        CosmeticTier[] tiers = CosmeticTier.values();
        for (int i = 0; i <= tiers.length; i++) {
            Rect rect = chipRect(i, tiers.length + 1);
            CosmeticTier tier = i == 0 ? null : tiers[i - 1];
            boolean active = tier == tierFilter;
            context.fill(rect.x(), rect.y(), rect.x() + rect.w(), rect.y() + rect.h(),
                    active ? 0xFF2D2A52 : rect.contains(mouseX, mouseY) ? 0xFF211F3A : CARD);
            context.drawStrokedRectangle(rect.x(), rect.y(), rect.w(), rect.h(),
                    active ? (tier == null ? ACCENT : tier.color()) : 0x447CEBFF);
            String label = tier == null ? "All" : tier.label();
            context.drawText(textRenderer, label, rect.x() + (rect.w() - textRenderer.getWidth(label)) / 2,
                    rect.y() + 4, active ? (tier == null ? ACCENT : tier.color()) : TEXT, false);
        }
    }

    private void drawDiagnostics(DrawContext context) {
        int x = grid.x() + 4;
        int y = grid.y() + 2;
        int width = grid.w() - 8;
        context.enableScissor(grid.x(), grid.y(), grid.x() + grid.w(), grid.y() + grid.h());
        context.drawText(textRenderer, CosmeticDiagnostics.running() ? "Checking..." : "Diagnostics", x, y, ACCENT, false);
        y += 12;
        for (CosmeticDiagnostics.Check check : CosmeticDiagnostics.checks()) {
            int color = switch (check.result()) {
                case OK -> GOOD;
                case FAIL -> BAD;
                case RUNNING -> ACCENT;
                case INFO -> MUTED;
            };
            String tag = switch (check.result()) {
                case OK -> "OK ";
                case FAIL -> "FAIL ";
                case RUNNING -> "... ";
                case INFO -> "i ";
            };
            context.drawText(textRenderer, tag + check.name(), x, y, color, false);
            y += 10;
            for (net.minecraft.text.OrderedText line : textRenderer.wrapLines(Text.literal(check.detail()), width - 8)) {
                if (y > grid.y() + grid.h() - 10) break;
                context.drawText(textRenderer, line, x + 6, y, 0xFFA9D2E8, false);
                y += 9;
            }
        }
        if (y < grid.y() + grid.h() - 22) {
            y += 4;
            context.drawText(textRenderer, "Recent requests", x, y, ACCENT, false);
            y += 11;
            java.util.List<CosmeticNet.Request> recent = CosmeticNet.recent();
            for (int i = recent.size() - 1; i >= 0 && y < grid.y() + grid.h() - 9; i--) {
                CosmeticNet.Request request = recent.get(i);
                String line = request.method() + " " + request.url().replaceFirst("^https?://", "") + "  "
                        + (request.error() != null ? request.error() : request.status() + " " + request.millis() + "ms");
                context.drawText(textRenderer, textRenderer.trimToWidth(line, width - 4), x, y,
                        request.error() != null || request.status() >= 400 ? BAD : MUTED, false);
                y += 9;
            }
        }
        context.disableScissor();
    }

    private void drawGrid(DrawContext context, int mouseX, int mouseY) {
        if (showDiagnostics) {
            drawDiagnostics(context);
            return;
        }
        if (visible.isEmpty()) {
            String empty = "Nothing here yet.";
            context.drawText(textRenderer, empty, grid.x() + (grid.w() - textRenderer.getWidth(empty)) / 2,
                    grid.y() + 20, MUTED, false);
            return;
        }
        context.enableScissor(grid.x(), grid.y(), grid.x() + grid.w(), grid.y() + grid.h());
        boolean overGrid = grid.contains(mouseX, mouseY);
        for (int i = 0; i < visible.size(); i++) {
            Rect rect = card(i);
            if (rect.y() + rect.h() < grid.y() || rect.y() > grid.y() + grid.h()) continue;
            drawCard(context, visible.get(i), rect, overGrid && rect.contains(mouseX, mouseY));
        }
        context.disableScissor();
        if (maxScroll() > 0) {
            int trackX = grid.x() + grid.w() - 2;
            int thumbH = Math.max(14, grid.h() * grid.h() / contentHeight);
            int thumbY = grid.y() + (grid.h() - thumbH) * scroll / maxScroll();
            context.fill(trackX, grid.y(), trackX + 2, grid.y() + grid.h(), 0x40FFFFFF);
            context.fill(trackX, thumbY, trackX + 2, thumbY + thumbH, ACCENT);
        }
    }

    private void drawCard(DrawContext context, Cosmetic cosmetic, Rect rect, boolean hovered) {
        boolean isSelected = cosmetic == selected;
        boolean equipped = CosmeticState.isEquipped(cosmetic);
        boolean unlocked = CosmeticCatalog.unlocked(cosmetic);
        context.fill(rect.x(), rect.y(), rect.x() + rect.w(), rect.y() + rect.h(), hovered ? CARD_HOVER : CARD);
        context.drawStrokedRectangle(rect.x(), rect.y(), rect.w(), rect.h(),
                isSelected ? 0xFFFFFFFF : equipped ? 0xAA00CFFF : 0x447CEBFF);
        context.fill(rect.x(), rect.y(), rect.x() + 2, rect.y() + rect.h(), cosmetic.tier().color());
        context.drawText(textRenderer, textRenderer.trimToWidth(cosmetic.name(), rect.w() - 10), rect.x() + 6,
                rect.y() + 5, unlocked ? TEXT : 0xFF9A9AB8, false);
        context.drawText(textRenderer, cosmetic.slot().label(), rect.x() + 6, rect.y() + 17, MUTED, false);

        String badge;
        int badgeColor;
        if (equipped) {
            badge = "EQUIPPED";
            badgeColor = GOOD;
        } else if (!unlocked) {
            badge = CosmeticCatalog.lockReason(cosmetic).toUpperCase(java.util.Locale.ROOT);
            badgeColor = BAD;
        } else if (cosmetic.definition().has("texture") && CosmeticTextures.failed(cosmetic)) {
            badge = "UNAVAILABLE";
            badgeColor = BAD;
        } else if (cosmetic.definition().has("texture") && CosmeticTextures.current(cosmetic) == null) {
            badge = "LOADING";
            badgeColor = MUTED;
        } else {
            badge = cosmetic.tier().label().toUpperCase(java.util.Locale.ROOT);
            badgeColor = cosmetic.tier().color();
        }
        context.drawText(textRenderer, badge, rect.x() + 6, rect.y() + rect.h() - 12, badgeColor, false);
    }

    private void drawPreview(DrawContext context, int mouseX, int mouseY) {
        context.fill(preview.x(), preview.y(), preview.x() + preview.w(), preview.y() + preview.h(), 0xFF141330);
        context.drawStrokedRectangle(preview.x(), preview.y(), preview.w(), preview.h(), OUTLINE);
        ClientPlayerEntity player = client == null ? null : client.player;
        if (player == null) {
            context.drawText(textRenderer, "Join a world to preview.", preview.x() + 8, preview.y() + 8, MUTED, false);
            return;
        }
        if (!draggingPreview) yaw += 0.6f;
        EntityRenderState state = client.getEntityRenderDispatcher().getAndUpdateRenderState(player, 1.0f);
        state.light = 15728880;
        state.shadowPieces.clear();
        state.outlineColor = 0;
        float scale = 1;
        if (state instanceof LivingEntityRenderState living) {
            living.bodyYaw = yaw;
            living.relativeHeadYaw = 0;
            living.pitch = 0;
            living.width /= living.baseScale;
            living.height /= living.baseScale;
            living.baseScale = 1;
            scale = living.height;
        }
        int size = (int) (preview.h() / Math.max(1.8f, scale) * 0.82f);
        Quaternionf rotation = new Quaternionf().rotateZ((float) Math.PI).rotateX((float) Math.toRadians(-8));
        context.addEntity(state, size, new Vector3f(0, state.height / 2.0f + 0.0625f, 0), rotation, null,
                preview.x(), preview.y(), preview.x() + preview.w(), preview.y() + preview.h());
        String hint = "drag to rotate";
        context.drawText(textRenderer, hint, preview.x() + (preview.w() - textRenderer.getWidth(hint)) / 2,
                preview.y() + preview.h() - 11, 0xFF6F6F96, false);
    }

    private void drawDetails(DrawContext context, int mouseX, int mouseY) {
        int x = preview.x();
        int y = preview.y() + preview.h() + 6;
        if (selected == null) {
            context.drawText(textRenderer, "Click a cosmetic to", x, y, MUTED, false);
            context.drawText(textRenderer, "preview it on you.", x, y + 11, MUTED, false);
            return;
        }
        context.drawText(textRenderer, textRenderer.trimToWidth(selected.name(), previewW), x, y, TEXT, false);
        String tier = selected.tier().label();
        context.drawText(textRenderer, tier, x, y + 11, selected.tier().color(), false);
        if (!selected.description().isEmpty()) {
            List<OrderedText> lines = textRenderer.wrapLines(Text.literal(selected.description()), previewW);
            int bottom = (playButton == null ? 0 : playButton.y()) - 2;
            for (int i = 0; i < lines.size() && y + 22 + i * 10 < bottom - 8; i++) {
                context.drawText(textRenderer, lines.get(i), x, y + 22 + i * 10, MUTED, false);
            }
        }
        boolean unlocked = CosmeticCatalog.unlocked(selected);
        boolean equipped = CosmeticState.isEquipped(selected);
        if (CosmeticTextures.failed(selected)) {
            List<OrderedText> reason = textRenderer.wrapLines(
                    Text.literal("Could not load its texture: " + CosmeticTextures.failureReason(selected)), previewW);
            for (int i = 0; i < reason.size() && i < 3; i++) {
                context.drawText(textRenderer, reason.get(i), x, playButton.y() - 32 + i * 9, BAD, false);
            }
        }
        if (selected.slot() == CosmeticSlot.EMOTE) {
            drawButton(context, playButton, Emotes.active() ? "STOP" : "PLAY EMOTE", 0xFF11263D, true, mouseX, mouseY);
        }
        if (unlocked) {
            drawButton(context, equipButton, equipped ? "UNEQUIP" : "EQUIP", equipped ? 0xFF482332 : ACCENT, true,
                    mouseX, mouseY);
        } else {
            drawButton(context, equipButton, CosmeticCatalog.lockReason(selected).toUpperCase(java.util.Locale.ROOT)
                    + " - LOCKED", 0xFF2A2A3A, false, mouseX, mouseY);
        }
    }

    private void drawPill(DrawContext context, Rect rect, String label, int color, int textColor, int mouseX,
                          int mouseY) {
        context.fill(rect.x(), rect.y(), rect.x() + rect.w(), rect.y() + rect.h(), color);
        if (rect.contains(mouseX, mouseY)) {
            context.fill(rect.x(), rect.y(), rect.x() + rect.w(), rect.y() + rect.h(), 0x30FFFFFF);
        }
        context.drawText(textRenderer, label, rect.x() + (rect.w() - textRenderer.getWidth(label)) / 2, rect.y() + 3,
                textColor, false);
    }

    private void drawButton(DrawContext context, Rect rect, String label, int color, boolean enabled, int mouseX,
                            int mouseY) {
        context.fill(rect.x(), rect.y(), rect.x() + rect.w(), rect.y() + rect.h(), color);
        if (enabled && rect.contains(mouseX, mouseY)) {
            context.fill(rect.x(), rect.y(), rect.x() + rect.w(), rect.y() + rect.h(), 0x30FFFFFF);
        }
        int textColor = !enabled ? 0xFF8E8EA8 : color == ACCENT ? 0xFF071321 : 0xFFEAF9FF;
        String trimmed = textRenderer.trimToWidth(label, rect.w() - 4);
        context.drawText(textRenderer, trimmed, rect.x() + (rect.w() - textRenderer.getWidth(trimmed)) / 2,
                rect.y() + (rect.h() - 8) / 2, textColor, false);
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        if (click.button() != GLFW.GLFW_MOUSE_BUTTON_LEFT) return super.mouseClicked(click, doubled);
        double x = click.comp_4798();
        double y = click.comp_4799();
        if (closeButton.contains(x, y)) {
            close();
            return true;
        }
        if (refreshButton.contains(x, y)) {
            CosmeticTextures.retryFailed();
            CosmeticCatalog.refreshAsync();
            return true;
        }
        if (diagnoseButton.contains(x, y)) {
            showDiagnostics = !showDiagnostics;
            if (showDiagnostics) CosmeticDiagnostics.run();
            return true;
        }
        if (clearButton.contains(x, y)) {
            for (CosmeticSlot slot : CosmeticSlot.values()) CosmeticState.unequip(slot);
            Emotes.stop();
            return true;
        }
        if (masterToggle.contains(x, y)) {
            VConfig.setEnabled(Module.COSMETICS, !CosmeticState.enabled());
            return true;
        }
        CosmeticCategory[] categories = CosmeticCategory.values();
        for (int i = 0; i < categories.length; i++) {
            if (tabRect(i).contains(x, y)) {
                tab = categories[i];
                scroll = 0;
                select(null);
                rebuild();
                return true;
            }
        }
        CosmeticTier[] tiers = CosmeticTier.values();
        for (int i = 0; i <= tiers.length; i++) {
            if (chipRect(i, tiers.length + 1).contains(x, y)) {
                tierFilter = i == 0 ? null : tiers[i - 1];
                scroll = 0;
                rebuild();
                return true;
            }
        }
        if (grid.contains(x, y)) {
            for (int i = 0; i < visible.size(); i++) {
                if (card(i).contains(x, y)) {
                    select(visible.get(i) == selected ? null : visible.get(i));
                    return true;
                }
            }
        }
        if (selected != null) {
            if (selected.slot() == CosmeticSlot.EMOTE && playButton.contains(x, y)) {
                if (Emotes.active()) Emotes.stop();
                else Emotes.play(selected);
                return true;
            }
            if (equipButton.contains(x, y)) {
                if (CosmeticState.isEquipped(selected)) CosmeticState.unequip(selected.slot());
                else if (!CosmeticState.equip(selected)) VClientMod.LOGGER.info("[Cosmetics] {} is locked.", selected.id());
                return true;
            }
        }
        if (preview.contains(x, y)) {
            draggingPreview = true;
            return true;
        }
        return super.mouseClicked(click, doubled);
    }

    @Override
    public boolean mouseDragged(Click click, double deltaX, double deltaY) {
        if (draggingPreview) {
            yaw += (float) deltaX * 1.6f;
            return true;
        }
        return super.mouseDragged(click, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(Click click) {
        draggingPreview = false;
        return super.mouseReleased(click);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        if (grid.contains(mouseX, mouseY)) {
            scroll = Math.clamp(scroll - (int) Math.round(vertical * 24), 0, maxScroll());
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontal, vertical);
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        if (input.getKeycode() == VConfig.integer(Module.COSMETICS, "menuKey")) {
            close();
            return true;
        }
        return super.keyPressed(input);
    }

    @Override
    public void close() {
        client.setScreen(parent);
    }

    @Override
    public void removed() {
        CosmeticState.setPreview(null);
        Emotes.stop();
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
