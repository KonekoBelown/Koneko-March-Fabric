package com.koneko.march.mixin;

import com.koneko.march.KonekoMarch;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.ItemStack;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Hand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {
    @Inject(method = "tryUseDeathProtector", at = @At("HEAD"), cancellable = true)
    private void konekomarch$tryUseJadeCharm(DamageSource source, CallbackInfoReturnable<Boolean> cir) {
        LivingEntity self = (LivingEntity)(Object)this;
        if (!(self.getEntityWorld() instanceof ServerWorld world)) {
            return;
        }

        ItemStack mainHand = self.getStackInHand(Hand.MAIN_HAND);
        ItemStack offHand = self.getStackInHand(Hand.OFF_HAND);
        ItemStack chosen = null;

        if (KonekoMarch.getJadeCharmLevel(world, mainHand) > 0) {
            chosen = mainHand;
        } else if (KonekoMarch.getJadeCharmLevel(world, offHand) > 0) {
            chosen = offHand;
        }

        if (chosen == null) {
            return;
        }

        KonekoMarch.useJadeTotem(world, self, chosen);

        self.setHealth(1.0F);
        self.clearStatusEffects();
        self.addStatusEffect(new StatusEffectInstance(StatusEffects.REGENERATION, 900, 1));
        self.addStatusEffect(new StatusEffectInstance(StatusEffects.ABSORPTION, 100, 1));
        self.addStatusEffect(new StatusEffectInstance(StatusEffects.FIRE_RESISTANCE, 800, 0));

        // Vanilla totem activation status byte. This drives the client-side totem animation.
        world.sendEntityStatus(self, (byte)35);
        world.playSound(null, self.getBlockPos(), SoundEvents.ITEM_TOTEM_USE, self.getSoundCategory(), 1.0F, 1.0F);

        cir.setReturnValue(true);
    }
}
