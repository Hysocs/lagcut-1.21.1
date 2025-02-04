package com.lagcut.mixin.mobstacking;

import com.lagcut.api.StackDataProvider;
import com.lagcut.api.WorldStore;
import com.lagcut.utils.LagCutConfig;
import net.minecraft.entity.Entity;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.SpawnHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import it.unimi.dsi.fastutil.objects.Object2IntMap;

@Mixin(SpawnHelper.Info.class)
public abstract class SpawnInfoMixin {
    @Shadow
    abstract Object2IntMap<SpawnGroup> getGroupToCount();

    private static final Logger LOGGER = LoggerFactory.getLogger("LagCut-EntityStacking");
    private static final int CHUNK_CHECK_RADIUS = 2;

    @Inject(
            method = "isBelowCap",
            at = @At("HEAD"),
            cancellable = true
    )
    private void checkSpawnCap(SpawnGroup group, ChunkPos chunkPos, CallbackInfoReturnable<Boolean> cir) {
        try {
            ServerWorld world = WorldStore.getCurrentWorld();
            if (world == null) {
                return;
            }

            // Check if entity list adjustment is enabled
            boolean adjustEntityList = LagCutConfig.INSTANCE.getConfig()
                    .getEntityStacking()
                    .getAdjustEntityListForStackSize();

            if (!adjustEntityList) {
                return; // Do nothing if adjustEntityList is false
            }

            int baseCount = getGroupToCount().getInt(group);
            int capacity = group.getCapacity();
            int totalStackAddition = 0;

            // Check surrounding chunks
            for (int xOffset = -CHUNK_CHECK_RADIUS; xOffset <= CHUNK_CHECK_RADIUS; xOffset++) {
                for (int zOffset = -CHUNK_CHECK_RADIUS; zOffset <= CHUNK_CHECK_RADIUS; zOffset++) {
                    ChunkPos checkPos = new ChunkPos(
                            chunkPos.x + xOffset,
                            chunkPos.z + zOffset
                    );

                    Box chunkBox = new Box(
                            checkPos.getStartX(), world.getBottomY(), checkPos.getStartZ(),
                            checkPos.getEndX(), world.getTopY(), checkPos.getEndZ()
                    );

                    // Get all entities in this chunk
                    for (Entity entity : world.getOtherEntities(null, chunkBox, e -> e.getType().getSpawnGroup() == group)) {
                        if (entity instanceof StackDataProvider provider && provider.isStackedCompat()) {
                            totalStackAddition += provider.getStackSizeCompat() - 1;
                        }
                    }
                }
            }

            int adjustedCount = baseCount + totalStackAddition;
            boolean belowCap = adjustedCount < capacity;
            cir.setReturnValue(belowCap);

        } catch (Exception e) {
            LOGGER.error("Error checking cap", e);
        }
    }
}
