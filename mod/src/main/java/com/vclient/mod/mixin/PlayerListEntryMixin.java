package com.vclient.mod.mixin;

import com.vclient.mod.cosmetics.SkinFix;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.entity.player.SkinTextures;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The one place the local player's skin textures are resolved. SkinFix swaps in the launcher-supplied
 * Ely.by skin when vanilla fell back to the default skin, and the equipped cosmetic cape.
 */
@Mixin(PlayerListEntry.class)
public abstract class PlayerListEntryMixin {
    @Inject(method = "getSkinTextures()Lnet/minecraft/entity/player/SkinTextures;", at = @At("RETURN"),
            cancellable = true)
    private void vclient$skin(CallbackInfoReturnable<SkinTextures> info) {
        PlayerListEntry self = (PlayerListEntry) (Object) this;
        SkinTextures original = info.getReturnValue();
        SkinTextures replaced = SkinFix.apply(self.getProfile().id(), self.getProfile().name(), original);
        if (replaced != original) info.setReturnValue(replaced);
    }
}
