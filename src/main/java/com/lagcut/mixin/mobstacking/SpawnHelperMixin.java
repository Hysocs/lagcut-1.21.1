// SpawnHelperMixin.java
package com.lagcut.mixin.mobstacking;


import com.lagcut.api.WorldStore;
import com.lagcut.utils.LagCutConfig;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.SpawnHelper;
import net.minecraft.world.chunk.WorldChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SpawnHelper.class)
public class SpawnHelperMixin {
    @Inject(
            method = "spawn",
            at = @At("HEAD")
    )
    private static void captureWorld(ServerWorld world, WorldChunk chunk, SpawnHelper.Info info, boolean spawnAnimals, boolean spawnMonsters, boolean rareSpawn, CallbackInfo ci) {
        // Check if entity list adjustment is enabled
        boolean adjustEntityList = LagCutConfig.INSTANCE.getConfig()
                .getEntityStacking()
                .getAdjustEntityListForStackSize();

        if (!adjustEntityList) {
            return; // Do nothing if adjustEntityList is false
        }
        WorldStore.setCurrentWorld(world);
    }
}