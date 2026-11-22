package com.vclient.mod.mixin;

import com.vclient.mod.Interface;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Custom Scoreboard and victory detection. Optional hooks: if either cannot apply, vanilla behaviour stays. */
@Mixin(InGameHud.class)
public abstract class ScoreboardMixin {
    @Inject(method = "renderScoreboardSidebar(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/scoreboard/ScoreboardObjective;)V",
            at = @At("HEAD"), cancellable = true, require = 0)
    private void vclient$customScoreboard(DrawContext context, ScoreboardObjective objective, CallbackInfo info) {
        boolean[] drawn = {false};
        com.vclient.mod.Safe.run("Custom Scoreboard", () -> drawn[0] = Interface.drawScoreboard(context, objective));
        if (drawn[0]) info.cancel();
    }

    @Inject(method = "setTitle(Lnet/minecraft/text/Text;)V", at = @At("TAIL"), require = 0)
    private void vclient$title(Text title, CallbackInfo info) {
        com.vclient.mod.Safe.run("Victory effects", () -> com.vclient.mod.cosmetics.Effects.onTitle(title == null ? "" : title.getString()));
    }
}
