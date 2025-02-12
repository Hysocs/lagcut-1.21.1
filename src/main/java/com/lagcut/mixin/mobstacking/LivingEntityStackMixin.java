package com.lagcut.mixin.mobstacking;

import com.lagcut.EntityStackManager;
import com.lagcut.api.StackDataProvider;
import com.lagcut.utils.LagCutConfig;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.WeakHashMap;
import java.util.Map;

@Mixin(LivingEntity.class)
public class LivingEntityStackMixin {
    @Unique
    private static final int CHECK_INTERVAL = 10;
    @Unique
    private static final double MAX_VISIBILITY_DISTANCE_SQ = 1000.0D;
    @Unique
    private final Map<ServerPlayerEntity, Integer> lastChecked = new WeakHashMap<>();

    @Inject(method = "tick", at = @At("HEAD"))
    private void onEntityTick(CallbackInfo ci) {
        LivingEntity self = (LivingEntity)(Object)this;

        if (shouldSkipTick(self)) return;

        StackDataProvider provider = (StackDataProvider)self;
        updateNametagVisibility(self, provider);
    }

    @Unique
    private boolean shouldSkipTick(LivingEntity entity) {
        if (entity instanceof ServerPlayerEntity) return true;
        if (!(entity instanceof StackDataProvider provider)) return true;
        if (!provider.isStackedCompat()) return true;
        if (entity.age % CHECK_INTERVAL != 0) return true;
        if (!entity.hasCustomName()) return true;
        return false;
    }

    @Unique
    private void updateNametagVisibility(LivingEntity entity, StackDataProvider provider) {
        boolean currentVisibility = entity.isCustomNameVisible();
        boolean hideNametags = LagCutConfig.INSTANCE.getConfig()
                .getEntityStacking()
                .getHideNametagsThroughBlocks();

        if (!hideNametags) {
            if (!currentVisibility) entity.setCustomNameVisible(true);
            return;
        }

        entity.getWorld().getPlayers().stream()
                .filter(player -> player instanceof ServerPlayerEntity)
                .map(player -> (ServerPlayerEntity)player)
                .forEach(serverPlayer -> {
                    lastChecked.put(serverPlayer, entity.age);
                    boolean visible = isEntityVisibleToPlayer(entity, serverPlayer);
                    if (currentVisibility != visible) {
                        entity.setCustomNameVisible(visible);
                    }
                });
    }

    @Unique
    private boolean isEntityVisibleToPlayer(LivingEntity entity, ServerPlayerEntity player) {
        Vec3d cameraPos = player.getCameraPosVec(1.0f);  // Get exact camera view position
        Vec3d entityPos = entity.getPos().add(0, entity.getHeight() / 2, 0);

        if (cameraPos.squaredDistanceTo(entityPos) > MAX_VISIBILITY_DISTANCE_SQ) {
            return false;
        }

        HitResult hitResult = player.getWorld().raycast(new RaycastContext(
                cameraPos,
                entityPos,
                RaycastContext.ShapeType.COLLIDER,
                RaycastContext.FluidHandling.NONE,
                player
        ));

        return hitResult == null ||
                hitResult.getType() == HitResult.Type.MISS ||
                (hitResult instanceof BlockHitResult blockHit &&
                        blockHit.getBlockPos().equals(entity.getBlockPos()));
    }




    @Inject(method = "onDeath", at = @At("HEAD"))
    private void onEntityDeath(DamageSource damageSource, CallbackInfo ci) {
        LivingEntity self = (LivingEntity)(Object)this;

        if (self instanceof ServerPlayerEntity) return;

        if (self instanceof StackDataProvider provider &&
                provider.isStackedCompat() &&
                provider.getStackSizeCompat() > 1) {

            // Get the attacker if it's a player
            PlayerEntity attacker = null;
            if (damageSource.getAttacker() instanceof PlayerEntity) {
                attacker = (PlayerEntity) damageSource.getAttacker();
            }

            EntityStackManager.INSTANCE.handleDeathAtPosition(
                    self,
                    provider.getStackSizeCompat(),
                    self.getPos(),
                    self.getYaw(),
                    self.getPitch(),
                    attacker  // Pass the attacker
            );
        }
    }


}