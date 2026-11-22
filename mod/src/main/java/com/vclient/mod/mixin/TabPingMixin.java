package com.vclient.mod.mixin;

import com.vclient.mod.Interface;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.PlayerListHud;
import net.minecraft.client.network.PlayerListEntry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Custom Tab List: ping as a coloured number. Optional hook: vanilla bars stay if it cannot apply. */
@Mixin(PlayerListHud.class)
public abstract class TabPingMixin {
    @Inject(method = "renderLatencyIcon(Lnet/minecraft/client/gui/DrawContext;IIILnet/minecraft/client/network/PlayerListEntry;)V",
            at = @At("HEAD"), cancellable = true, require = 0)
    private void vclient$ping(DrawContext context, int width, int x, int y, PlayerListEntry entry, CallbackInfo info) {
        boolean[] drawn = {false};
        com.vclient.mod.Safe.run("Custom Tab List", () -> drawn[0] = Interface.drawPing(context, width, x, y, entry));
        if (drawn[0]) info.cancel();
    }
}
