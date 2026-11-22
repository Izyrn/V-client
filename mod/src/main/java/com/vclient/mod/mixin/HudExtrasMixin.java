package com.vclient.mod.mixin;

import com.vclient.mod.Extras;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.render.RenderTickCounter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Custom Crosshair. Optional hook: vanilla's crosshair stays if it cannot apply. */
@Mixin(InGameHud.class)
public abstract class HudExtrasMixin {
    @Inject(method = "renderCrosshair(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/client/render/RenderTickCounter;)V",
            at = @At("HEAD"), cancellable = true, require = 0)
    private void vclient$crosshair(DrawContext context, RenderTickCounter tickCounter, CallbackInfo info) {
        boolean[] drawn = {false};
        com.vclient.mod.Safe.run("Custom Crosshair", () -> drawn[0] = Extras.drawCrosshair(context));
        if (drawn[0]) info.cancel();
    }
}
