package com.vclient.mod.cosmetics;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.entity.feature.FeatureRenderer;
import net.minecraft.client.render.entity.feature.FeatureRendererContext;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;

/**
 * Draws model cosmetics (hats, wings, animated pieces) on the local player. Capes are handled through the
 * player's skin textures instead, trails through particles, and emotes through the model pose. Visual only.
 */
public final class CosmeticsFeatureRenderer extends FeatureRenderer<PlayerEntityRenderState, PlayerEntityModel> {
    public CosmeticsFeatureRenderer(FeatureRendererContext<PlayerEntityRenderState, PlayerEntityModel> context) {
        super(context);
    }

    @Override
    public void render(MatrixStack matrices, OrderedRenderCommandQueue queue, int light,
                       PlayerEntityRenderState state, float limbAngle, float limbDistance) {
        if (state.invisible || state.spectator || !CosmeticState.enabled()) return;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || state.id != client.player.getId()) return;
        draw(CosmeticSlot.HAT, matrices, queue, light);
        draw(CosmeticSlot.WINGS, matrices, queue, light);
        draw(CosmeticSlot.PET, matrices, queue, light);
    }

    private void draw(CosmeticSlot slot, MatrixStack matrices, OrderedRenderCommandQueue queue, int light) {
        Cosmetic cosmetic = CosmeticState.effective(slot);
        if (cosmetic == null) return;
        Identifier texture = CosmeticTextures.current(cosmetic);
        if (texture == null) return;
        CosmeticModels.Built model = CosmeticModels.get(cosmetic);
        if (model == null) return;
        model.animate();
        PlayerEntityModel player = getContextModel();
        drawBone(player.head, model.head(), texture, matrices, queue, light);
        drawBone(player.body, model.body(), texture, matrices, queue, light);
    }

    private static void drawBone(ModelPart bone, ModelPart root, Identifier texture, MatrixStack matrices,
                                 OrderedRenderCommandQueue queue, int light) {
        matrices.push();
        bone.applyTransform(matrices);
        queue.submitModelPart(root, matrices, RenderLayers.entityCutoutNoCull(texture), light,
                OverlayTexture.DEFAULT_UV, null);
        matrices.pop();
    }
}
