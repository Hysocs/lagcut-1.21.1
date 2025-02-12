package com.lagcut.mixin.itemstacking;

import com.lagcut.ItemStackingManager;
import com.lagcut.utils.LagCutConfig;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.HashMap;
import java.util.Map;

@Mixin(ItemEntity.class)
public class ItemEntityMixin {
    @Unique
    private static final int CHECK_INTERVAL = 10;
    @Unique
    private final Map<ServerPlayerEntity, Integer> lastChecked = new HashMap<>();

    @Inject(method = "tick", at = @At("HEAD"))
    public void onItemTick(CallbackInfo ci) {
        ItemEntity itemEntity = (ItemEntity) (Object) this;

        if (itemEntity.age == 1) {
            ItemStackingManager.INSTANCE.updateItemDisplay(itemEntity);
        }

        // Always try to merge, not just when on ground
        ItemStackingManager.INSTANCE.tryMergeItemEntities(itemEntity);

        // Handle nametag visibility if needed
        if (itemEntity.hasCustomName() && itemEntity.age % CHECK_INTERVAL == 0) {
            handleNametagVisibility(itemEntity);
        }
    }

    @Unique
    private void handleNametagVisibility(ItemEntity itemEntity) {
        boolean hideNametags = LagCutConfig.INSTANCE.getConfig().getItemStacking().getHideNametagsThroughBlocks();
        if (!hideNametags) {
            itemEntity.setCustomNameVisible(true);
            return;
        }

        itemEntity.getWorld().getPlayers().forEach(player -> {
            if (player instanceof ServerPlayerEntity serverPlayer) {
                itemEntity.setCustomNameVisible(isItemVisibleToPlayer(itemEntity, serverPlayer));
            }
        });
    }

    @Unique
    private boolean isItemVisibleToPlayer(ItemEntity item, ServerPlayerEntity player) {
        Vec3d playerEyePos = player.getCameraPosVec(1.0f);
        Vec3d itemPos = new Vec3d(item.getX(), item.getY() + item.getHeight() / 2, item.getZ());

        if (playerEyePos.squaredDistanceTo(itemPos) > 500.0D) return false;

        HitResult hitResult = player.getWorld().raycast(new RaycastContext(
                playerEyePos,
                itemPos,
                RaycastContext.ShapeType.COLLIDER,
                RaycastContext.FluidHandling.NONE,
                player
        ));

        return hitResult == null || hitResult.getType() == HitResult.Type.MISS ||
                (hitResult instanceof BlockHitResult blockHit &&
                        blockHit.getBlockPos().equals(item.getBlockPos()));
    }

    @Inject(method = "writeCustomDataToNbt", at = @At("HEAD"))
    public void enforceStackLimit(NbtCompound nbt, CallbackInfo ci) {
        ItemEntity item = (ItemEntity) (Object) this;
        ItemStack stack = item.getStack();

        if (stack.getCount() > 99) {
            int excessCount = stack.getCount() - 99;
            ItemStack overflowStack = stack.copy();
            overflowStack.setCount(excessCount);
            stack.setCount(99);

            ItemEntity overflowEntity = new ItemEntity(
                    item.getWorld(),
                    item.getX(),
                    item.getY(),
                    item.getZ(),
                    overflowStack
            );

            item.getWorld().spawnEntity(overflowEntity);
            ItemStackingManager.INSTANCE.tryMergeItemEntities(overflowEntity);
        }
    }

    @Inject(method = "cannotPickup", at = @At("HEAD"), cancellable = true)
    private void onCannotPickup(CallbackInfoReturnable<Boolean> cir) {
        ItemEntity self = (ItemEntity) (Object) this;
        // Force allow pickup even with custom name
        if (self.hasCustomName()) {
            cir.setReturnValue(false);  // false means it CAN be picked up
        }
    }
}