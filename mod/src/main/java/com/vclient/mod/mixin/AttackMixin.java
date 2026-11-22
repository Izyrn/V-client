package com.vclient.mod.mixin;

import com.vclient.mod.Combat;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Tells the combat HUD (Target HUD, Combo, Reach, Session Stats) when you swing at an entity. Changes nothing. */
@Mixin(ClientPlayerInteractionManager.class)
public abstract class AttackMixin {
    @Inject(method = "attackEntity(Lnet/minecraft/entity/player/PlayerEntity;Lnet/minecraft/entity/Entity;)V",
            at = @At("HEAD"), require = 0)
    private void vclient$onAttack(PlayerEntity player, Entity target, CallbackInfo info) {
        com.vclient.mod.Safe.run("Combat HUD", () -> Combat.onAttack(MinecraftClient.getInstance(), target));
    }
}
