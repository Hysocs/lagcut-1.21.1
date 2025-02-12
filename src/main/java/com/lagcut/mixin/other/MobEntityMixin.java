package com.lagcut.mixin.other;

import com.lagcut.api.StackDataProvider;
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

    @Unique
    private boolean isItemPickupDisabled() {
        MobEntity self = (MobEntity) (Object) this;
        // Only disable pickup if entity is stacked and config says so
        return self instanceof StackDataProvider stackProvider &&
                stackProvider.isStackedCompat() &&
                !LagCutConfig.INSTANCE.getConfig()
                        .getEntityStacking()
                        .getCanStackedEntityPickUpItems();
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