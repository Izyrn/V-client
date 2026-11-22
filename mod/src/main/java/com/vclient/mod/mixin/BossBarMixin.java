package com.vclient.mod.mixin;

import com.vclient.mod.Island;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.BossBarHud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Island Boss Bars: the Island shows the boss bar, so the vanilla one is hidden. Optional hook. */
@Mixin(BossBarHud.class)
public abstract class BossBarMixin {
    @Inject(method = "render(Lnet/minecraft/client/gui/DrawContext;)V", at = @At("HEAD"), cancellable = true, require = 0)
    private void vclient$bossBars(DrawContext context, CallbackInfo info) {
        if (Island.hidesBossBars()) info.cancel();
    }
}
