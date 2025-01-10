package com.lagcut.mixin.aithrottling;

import com.lagcut.AIModification;
import com.lagcut.utils.LagCutConfig;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.server.world.ServerWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerWorld.class)
public class ServerWorldMixin {
    @Unique
    private static final int CACHE_SIZE = 32768; // Power of 2 for fast modulo

    @Unique
    private static final byte[] ENTITY_LAYERS = new byte[CACHE_SIZE];

    @Unique
    private static final long[] LAST_UPDATE_TIME = new long[CACHE_SIZE];

    @Inject(method = "tickEntity", at = @At("HEAD"), cancellable = true)
    private void throttleEntityTick(Entity entity, CallbackInfo ci) {
        // First check if AI throttling is enabled in config
        if (!LagCutConfig.INSTANCE.getConfig().getAiThrottling().getEnabled()) {
            return;
        }

        // Skip if not a living entity or is a player
        if (!(entity instanceof LivingEntity) ||
                entity instanceof net.minecraft.server.network.ServerPlayerEntity) {
            return;
        }

        // Fast modulo using bitwise AND since CACHE_SIZE is power of 2
        int entityIndex = entity.getId() & (CACHE_SIZE - 1);
        long currentTick = entity.getWorld().getTime();

        // Check if we need to update the layer
        if (currentTick - LAST_UPDATE_TIME[entityIndex] > 100) { // Update layer every 5 seconds
            ENTITY_LAYERS[entityIndex] = (byte)(AIModification.getEntityLayer(entity).ordinal() + 1);
            LAST_UPDATE_TIME[entityIndex] = currentTick;
        }

        byte layer = ENTITY_LAYERS[entityIndex];

        // Check if movement ticks should be throttled
        boolean shouldThrottleMovement = LagCutConfig.INSTANCE.getConfig()
                .getAiThrottling().getThrottleMovementTicks();

        // Layer 1: No throttling
        if (layer == 1) {
            return;
        }
        // Layer 2: Use fast bitwise check for even/odd
        else if (layer == 2) {
            if ((currentTick & 1) == 0 && shouldThrottleMovement) { // Fast modulo 2
                ci.cancel();
            }
        }
        // Layer 3+: Fast modulo 20 check
        else {
            if ((currentTick % 20) < 15 && shouldThrottleMovement) {
                ci.cancel();
            }
        }
    }
}