package com.lagcut.mixin.aithrottling;

import com.lagcut.AIModification;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MobEntity.class)
public abstract class MobEntityMixin {

    /**
     * Cancels the isInWalkTargetRange method when the mob is not in an active chunk.
     * If the mob is out of an active chunk, the method will immediately return false.
     */
    @Inject(method = "isInWalkTargetRange(Lnet/minecraft/util/math/BlockPos;)Z", at = @At("HEAD"), cancellable = true)
    private void onIsInWalkTargetRange(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        MobEntity mob = (MobEntity) (Object) this;
        if (!AIModification.isEntityInActiveChunk(mob)) {
            cir.setReturnValue(false);
        }
    }

    /**
     * Cancels the onStartPathfinding method when the mob is not in an active chunk.
     */
    @Inject(method = "onStartPathfinding", at = @At("HEAD"), cancellable = true)
    private void onStartPathfinding(CallbackInfo ci) {
        MobEntity mob = (MobEntity) (Object) this;
        if (!AIModification.isEntityInActiveChunk(mob)) {
            ci.cancel();
        }
    }

    /**
     * Cancels the changeAngle method when the mob is not in an active chunk.
     */
    @Inject(method = "changeAngle", at = @At("HEAD"), cancellable = true)
    private void onChangeAngle(float from, float _to, float max, CallbackInfoReturnable<Float> cir) {
        MobEntity mob = (MobEntity) (Object) this;
        if (!AIModification.isEntityInActiveChunk(mob)) {
            cir.cancel();
        }
    }

    /**
     * Cancels the lookAtEntity method when the mob is not in an active chunk.
     */
    @Inject(method = "lookAtEntity", at = @At("HEAD"), cancellable = true)
    private void onLookAtEntity(CallbackInfo ci) {
        MobEntity mob = (MobEntity) (Object) this;
        if (!AIModification.isEntityInActiveChunk(mob)) {
            ci.cancel();
        }
    }

    @Inject(method = "tickMovement", at = @At("HEAD"), cancellable = true)
    private void onTickMovement(CallbackInfo ci) {
        MobEntity mob = (MobEntity) (Object) this;
        if (!AIModification.isEntityInActiveChunk(mob)) {
            ci.cancel();
        }
    }
}
