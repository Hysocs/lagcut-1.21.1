package com.lagcut.mixin.other;

import com.lagcut.utils.LagCutConfig;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MobEntity.class)
public class MobEntityMixin {

    /**
     * Checks if item pickup is disabled based on configuration.
     *
     * @return true if item pickup is disabled, false otherwise.
     */
    @Unique
    private boolean isItemPickupDisabled() {
        return !LagCutConfig.INSTANCE.getConfig()
                .getEntityBehavior()
                .getAllowItemPickup();
    }

    @Inject(method = "canPickupItem", at = @At("HEAD"), cancellable = true)
    private void canPickupItems(ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
        if (isItemPickupDisabled()) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "canPickUpLoot", at = @At("HEAD"), cancellable = true)
    private void canPickUpLoot(CallbackInfoReturnable<Boolean> cir) {
        if (isItemPickupDisabled()) {
            cir.setReturnValue(false);
        }
    }
}
