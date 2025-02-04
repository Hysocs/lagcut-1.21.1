package com.lagcut.mixin.mobstacking;

import com.lagcut.EntityStackManager;
import com.lagcut.api.StackDataProvider;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.NameTagItem;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(NameTagItem.class)
public class NameTagItemMixin {
    private static final Logger LOGGER = LoggerFactory.getLogger("LagCut");

    @Inject(method = "useOnEntity", at = @At("HEAD"), cancellable = true)
    private void onUseOnEntity(ItemStack stack, PlayerEntity user, LivingEntity entity, Hand hand, CallbackInfoReturnable<ActionResult> cir) {

        if (!(entity instanceof StackDataProvider provider)) {
            return;
        }

        if (!provider.isStackedCompat()) {
            return;
        }

        var customName = stack.get(DataComponentTypes.CUSTOM_NAME);

        if (customName == null) {
            return;
        }

        int stackSize = provider.getStackSizeCompat();

        if (stackSize <= 1) {
            return;
        }


        // Split the stack and name the new entity
        EntityStackManager.INSTANCE.handleStackSplit(
                entity,
                stackSize,
                customName,
                user
        );

        // Consume the name tag if not in creative mode
        if (!user.isCreative()) {
            stack.decrement(1);
        }

        // Cancel the original naming operation
        cir.setReturnValue(ActionResult.SUCCESS);
    }
}