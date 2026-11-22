package com.vclient.mod.mixin;

import com.vclient.mod.Interface;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.item.HeldItemRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Hand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Better Hand: moves and resizes the first-person item. Push at the start, pop at every return, so nothing leaks. */
@Mixin(HeldItemRenderer.class)
public abstract class HandMixin {
    private static final String TARGET = "renderFirstPersonItem(Lnet/minecraft/client/network/AbstractClientPlayerEntity;FFLnet/minecraft/util/Hand;FLnet/minecraft/item/ItemStack;FLnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;I)V";
    @Unique
    private boolean vclient$pushed;

    @Inject(method = TARGET, at = @At("HEAD"), require = 0)
    private void vclient$handStart(AbstractClientPlayerEntity player, float tickProgress, float pitch, Hand hand,
                                   float swingProgress, ItemStack item, float equipProgress, MatrixStack matrices,
                                   OrderedRenderCommandQueue queue, int light, CallbackInfo info) {
        vclient$pushed = false;
        if (!Interface.handEnabled()) return;
        matrices.push();
        vclient$pushed = true;
        try {
            Interface.applyHand(matrices);
        } catch (RuntimeException e) {
            com.vclient.mod.VClientMod.LOGGER.warn("[VYRN] Better Hand failed: {}", e.toString());
        }
    }

    @Inject(method = TARGET, at = @At("RETURN"), require = 0)
    private void vclient$handEnd(AbstractClientPlayerEntity player, float tickProgress, float pitch, Hand hand,
                                 float swingProgress, ItemStack item, float equipProgress, MatrixStack matrices,
                                 OrderedRenderCommandQueue queue, int light, CallbackInfo info) {
        if (vclient$pushed) matrices.pop();
        vclient$pushed = false;
    }
}
