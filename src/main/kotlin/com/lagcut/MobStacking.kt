package com.lagcut

import com.lagcut.api.StackDataProvider
import com.lagcut.utils.LagCutConfig
import com.lagcut.utils.LagCutConfig.logDebug
import com.lagcut.utils.MiniMessageHelper
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.minecraft.entity.Entity
import net.minecraft.entity.EntityType
import net.minecraft.entity.EquipmentSlot
import net.minecraft.entity.LivingEntity
import net.minecraft.entity.passive.PassiveEntity
import net.minecraft.entity.player.PlayerEntity // Import PlayerEntity
import net.minecraft.item.ItemStack
import net.minecraft.server.world.ServerWorld
import net.minecraft.text.Text
import net.minecraft.util.math.Vec3d
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.collections.Set

object EntityStackManager {
    private const val SPAWN_HEIGHT_OFFSET = 0.1
    private const val MIN_STACK_SIZE = 1

    private enum class EntityStatus { STACKED, DYING, PROCESSING }
    private val entityTracker = ConcurrentHashMap<UUID, EntityStatus>()

    private var tickCounter = 0
    private var initialized = false
    private var isEventRegistered = false

    // Replace lazy with mutable property
    private var excludedEntities: Set<String> = emptySet()

    // Keep config accessor for convenience
    private val config get() = LagCutConfig.config.entityStacking

    fun reinitialize() {
        // Update excluded entities
        excludedEntities = config.excludedEntities.toSet()

        if (!initialized) {
            initialize()
        } else if (config.enabled && !isEventRegistered) {
            // Re-register tick event if needed
            registerTickEvent()
        }
    }

    fun initialize() {
        if (!config.enabled) {
            logDebug("[ReduceAllTheLag] Entity stacking is disabled")
            return
        }

        excludedEntities = config.excludedEntities.toSet()
        registerTickEvent()
        initialized = true
    }

    private fun registerTickEvent() {
        if (!isEventRegistered) {
            ServerTickEvents.END_SERVER_TICK.register { server ->
                try {
                    if (++tickCounter % config.stackingFrequencyTicks == 0) {
                        server.worlds.filterIsInstance<ServerWorld>().forEach(::processMerges)
                        if (config.clearStacksOnServerStop) {
                            cleanupTracker(server.worlds)
                        }
                    }
                } catch (e: Exception) {
                    logDebug("[ReduceAllTheLag] Tick error: ${e.message}")
                }
            }
            isEventRegistered = true
        }
    }

    fun handleDeathAtPosition(
        entity: Entity,
        currentStackSize: Int,
        pos: Vec3d,
        yaw: Float,
        pitch: Float,
        attacker: PlayerEntity? = null  // Add attacker parameter
    ) {
        if (!config.enabled || currentStackSize <= MIN_STACK_SIZE ||
            entityTracker[entity.uuid] == EntityStatus.PROCESSING ||
            isEntityExcluded(entity)) return

        try {
            // Remove the nametag before processing death
            entity.apply {
                customName = null
                isCustomNameVisible = false
                setCustomNameVisible(false)
            }

            entityTracker[entity.uuid] = EntityStatus.PROCESSING
            val world = entity.world as? ServerWorld ?: return
            entityTracker[entity.uuid] = EntityStatus.DYING
            entityTracker.remove(entity.uuid)

            if (config.deleteEntireStackOnKill) {
                // Generate drops and XP for each entity in the stack
                repeat(currentStackSize - 1) {
                    world.server.execute {
                        try {
                            val entityToKill = entity.type.create(world)?.apply {
                                setPosition(pos)
                                setYaw(yaw)
                                setPitch(pitch)
                                setSilent(true)
                                isSilent = true
                                setNoGravity(true)
                                noClip = true
                                isInvisible = true
                                boundingBox = boundingBox.shrink(0.0, 0.0, 0.0)

                                // Copy relevant properties from the original entity
                                (this as? LivingEntity)?.let { living ->
                                    // Set to 1 HP so it's alive when spawned
                                    living.health = 1f
                                    // Copy baby state if the original was a baby
                                    if (entity is LivingEntity && entity.isBaby) {
                                        when (living) {
                                            is PassiveEntity -> living.breedingAge = -24000  // Set as baby
                                            // Add other specific entity types if needed
                                        }
                                    }
                                    // Copy equipment if KeepOriginalStackedmobondeath is true
                                    if (config.preserveOriginalEntityOnDeath) {
                                        (entity as? LivingEntity)?.let { originalEntity ->
                                            for (slot in EquipmentSlot.values()) {
                                                living.equipStack(slot, originalEntity.getEquippedStack(slot).copy())
                                            }
                                        }
                                    }
                                }
                            }

                            // Spawn and then kill to generate drops and XP
                            entityToKill?.let { newEntity ->
                                if (world.spawnEntity(newEntity)) {
                                    // If killed by a player, use player damage source to ensure XP drops
                                    (newEntity as? LivingEntity)?.let { living ->
                                        if (attacker != null) {
                                            // Use playerAttack directly from damageSources
                                            living.damage(
                                                world.damageSources.playerAttack(attacker),
                                                Float.MAX_VALUE
                                            )
                                        } else {
                                            living.damage(
                                                world.damageSources.generic(),
                                                Float.MAX_VALUE
                                            )
                                        }
                                    } ?: newEntity.kill()
                                }
                            }
                        } catch (e: Exception) {
                            logDebug("[ReduceAllTheLag] Error spawning entity for drops: ${e.message}")
                        }
                    }
                }
            } else {
                // Original behavior - spawn remaining stack with equipment if configured
                val spawnParams = SpawnParameters(
                    world,
                    entity.type,
                    pos.add(0.0, SPAWN_HEIGHT_OFFSET, 0.0),
                    yaw,
                    pitch,
                    currentStackSize - 1
                ).apply {
                    if (config.preserveOriginalEntityOnDeath) {
                        (entity as? LivingEntity)?.let { originalEntity ->
                            equipmentToCopy = EquipmentSlot.values().associateWith { slot ->
                                originalEntity.getEquippedStack(slot).copy()
                            }
                        }
                    }
                }

                world.server.execute { safelySpawnReplacement(spawnParams) }
            }
        } finally {
            entityTracker.remove(entity.uuid)
        }
    }

    data class SpawnParameters(
        val world: ServerWorld,
        val entityType: EntityType<*>,
        val position: Vec3d,
        val yaw: Float,
        val pitch: Float,
        val stackSize: Int,
        var equipmentToCopy: Map<EquipmentSlot, ItemStack>? = null,
        var isBaby: Boolean = false  // Add baby state parameter
    )


    private fun safelySpawnReplacement(params: SpawnParameters) {
        try {
            val newEntity = params.entityType.create(params.world)?.apply {
                setPosition(params.position)
                setYaw(params.yaw)
                setPitch(params.pitch)

                // Apply equipment if available
                params.equipmentToCopy?.let { equipment ->
                    (this as? LivingEntity)?.let { living ->
                        equipment.forEach { (slot, stack) ->
                            living.equipStack(slot, stack)
                        }
                    }
                }
            } as? StackDataProvider ?: return

            val stackSize = params.stackSize.coerceAtMost(config.maxStackSize)

            with(newEntity) {
                setStackSizeCompat(stackSize)
                setStackedCompat(true)
            }

            updateEntityDisplay(newEntity as Entity, stackSize)

            (newEntity as? LivingEntity)?.let { it.health = it.maxHealth }

            if (params.world.spawnEntity(newEntity as Entity)) {
                entityTracker[newEntity.uuid] = EntityStatus.STACKED
            }
        } catch (e: Exception) {
            logDebug("[ReduceAllTheLag] Spawn error: ${e.message}")
        }
    }

    private fun isPlayerNamed(entity: Entity): Boolean {
        // First check if it's part of our stacking system
        if (entity is StackDataProvider) {
            // If it's already part of a stack, it's not considered player-named
            if (entity.isStackedCompat()) {
                return false
            }
        }

        // Now check if it has a custom name
        return entity.customName != null
    }

    private fun isValidForMerge(entity: LivingEntity): Boolean {
        // Quick initial checks
        if (!entity.isAlive ||
            entity.isRemoved ||
            entity.health <= 0 ||
            entityTracker[entity.uuid] == EntityStatus.DYING ||
            entityTracker[entity.uuid] == EntityStatus.PROCESSING ||
            isEntityExcluded(entity)) {
            return false
        }

        // If the entity is player-named (and not part of a stack)
        if (isPlayerNamed(entity)) {
            return config.stackPlayerNamedEntity // Only allow if player-named stacking is enabled
        }

        // Allow merging for all other cases
        return true
    }

    private fun isEntityExcluded(entity: Entity): Boolean {
        // Quick check for players and non-minecraft entities
        if (entity is PlayerEntity ||
            EntityType.getId(entity.type).namespace != "minecraft") {
            return true
        }

        val entityType = entity.type.toString()
        val config = LagCutConfig.config.entityStacking

        // Check entity type exclusions
        if (config.excludedEntityTypes.any { it.equals(entityType, ignoreCase = true) }) {
            logDebug("[DEBUG] Entity type $entityType is in excludedEntityTypes")
            return true
        }

        // Check specific entity exclusions
        if (excludedEntities.contains(EntityType.getId(entity.type).toString())) {
            logDebug("[DEBUG] Entity $entityType is in excludedEntities")
            return true
        }

        // Check dimension exclusions
        val dimensionId = (entity.world as? ServerWorld)?.registryKey?.value?.toString()
        if (dimensionId != null && config.excludedDimensions.any { it.equals(dimensionId, ignoreCase = true) }) {
            logDebug("[DEBUG] Entity in excluded dimension: $dimensionId")
            return true
        }

        // Check NBT patterns
        val nbt = net.minecraft.nbt.NbtCompound()
        entity.writeNbt(nbt)

        val hasMatchingNbtPattern = config.nbtExclusionPatterns.any { pattern ->
            // Convert NBT to string for raw searching
            val nbtString = nbt.toString()

            // Search for the pattern in the raw NBT string
            val matchFound = nbtString.contains(pattern)

            if (matchFound) {
                logDebug("[DEBUG] Found matching NBT value: $pattern in NBT: $nbtString")
            }

            matchFound
        }

        if (hasMatchingNbtPattern) {
            logDebug("[DEBUG] Entity matches NBT exclusion pattern")
            return true
        }

        return false
    }

    private fun processMerges(world: ServerWorld) {
        if (!config.enabled) return

        val processed = mutableSetOf<UUID>()

        world.iterateEntities()
            .filterIsInstance<LivingEntity>()
            .filter { shouldProcessEntityMerge(it, processed) }
            .forEach { entity ->
                findValidNearbyEntities(world, entity, processed)
                    .takeIf { it.isNotEmpty() }
                    ?.let { safelyMergeEntityGroup(entity, it, processed) }
            }
    }

    private fun shouldProcessEntityMerge(entity: LivingEntity, processed: Set<UUID>): Boolean =
        !processed.contains(entity.uuid) &&
                isValidForMerge(entity) &&
                entityTracker[entity.uuid] != EntityStatus.PROCESSING

    private fun findValidNearbyEntities(
        world: ServerWorld,
        entity: LivingEntity,
        processed: Set<UUID>
    ): List<LivingEntity> {
        // Get the bounding box to search for nearby entities
        val searchBox = entity.boundingBox.expand(config.detectionRadius)

        // Find all valid entities within the search radius
        return world.getOtherEntities(
            entity,
            searchBox
        ) { other ->
            other is LivingEntity &&                    // Must be a living entity
                    other.type == entity.type &&            // Must be same entity type
                    !processed.contains(other.uuid) &&       // Not already processed
                    isValidForMerge(other) &&               // Passes basic merge checks
                    (config.stackBabyWithAdult ||           // Either allow baby/adult stacking
                            other.isBaby == entity.isBaby)         // Or require matching baby status
        }.filterIsInstance<LivingEntity>()              // Ensure we only return LivingEntities
    }

    private fun safelyMergeEntityGroup(
        target: LivingEntity,
        nearbyEntities: List<LivingEntity>,
        processed: MutableSet<UUID>
    ) {
        (target as? StackDataProvider)?.takeIf { isValidForMerge(target) }?.let { stackTarget ->
            try {
                entityTracker[target.uuid] = EntityStatus.PROCESSING
                var totalStack = stackTarget.getStackSizeCompat()
                val maxSize = config.maxStackSize

                // Convert to sequence for early termination without break
                nearbyEntities.asSequence()
                    .takeWhile { totalStack < maxSize }
                    .forEach { other ->
                        (other as? StackDataProvider)?.takeIf { isValidForMerge(other) }?.let { stackOther ->
                            val otherSize = stackOther.getStackSizeCompat()
                            val potentialTotal = totalStack + otherSize

                            when {
                                potentialTotal <= maxSize -> {
                                    // Can merge entire stack
                                    entityTracker[other.uuid] = EntityStatus.PROCESSING
                                    totalStack = potentialTotal
                                    other.discard()
                                    processed.add(other.uuid)
                                    entityTracker.remove(other.uuid)
                                }

                                totalStack < maxSize -> {
                                    // Can only merge part of the stack
                                    entityTracker[other.uuid] = EntityStatus.PROCESSING
                                    val spaceLeft = maxSize - totalStack
                                    val remainingStack = otherSize - spaceLeft

                                    // Update the target stack by adding only the available space
                                    totalStack += spaceLeft  // <-- Fixed: Add spaceLeft instead of setting to maxSize

                                    // Update the other stack with remaining entities
                                    stackOther.setStackSizeCompat(remainingStack)
                                    updateEntityDisplay(other)

                                    processed.add(other.uuid)
                                    entityTracker.remove(other.uuid)
                                }
                            }
                        }
                    }

                stackTarget.setStackSizeCompat(totalStack)
                stackTarget.setStackedCompat(true)
                updateEntityDisplay(target)
                entityTracker[target.uuid] = EntityStatus.STACKED
                processed.add(target.uuid)
            } finally {
                entityTracker.remove(target.uuid)
            }
        }
    }

    private fun updateEntityDisplay(entity: Entity, stackSize: Int? = null) {
        // If nametags are disabled, remove any existing nametags and return
        if (!config.enableNameTags) {
            entity.apply {
                customName = null
                isCustomNameVisible = false
                setCustomNameVisible(false)
            }
            return
        }

        (entity as? StackDataProvider)?.let { stackEntity ->
            val size = stackSize ?: stackEntity.getStackSizeCompat()

            if (size <= 1) {
                // Remove custom name when stack size is 1
                entity.apply {
                    customName = null
                    isCustomNameVisible = false
                    setCustomNameVisible(false)
                }
                return
            }

            val format = config.stackNameFormat
                .replace("<entityname>", entity.type.name.string)
                .replace("<stacksize>", size.toString())

            try {
                entity.apply {
                    customName = MiniMessageHelper.parse(format)
                    isCustomNameVisible = true
                    setCustomNameVisible(true)
                }
            } catch (e: Exception) {
                entity.apply {
                    customName = Text.literal("${entity.type.name.string} x$size")
                    isCustomNameVisible = true
                    setCustomNameVisible(true)
                }
            }
        }
    }

    private fun cleanupTracker(worlds: Iterable<ServerWorld>) {
        val existingUuids = worlds
            .flatMap { it.iterateEntities() }
            .map { it.uuid }
            .toSet()

        entityTracker.keys.removeIf { !existingUuids.contains(it) }
    }

    fun handleStackSplit(
        entity: Entity,
        currentStackSize: Int,
        name: Text,
        player: PlayerEntity
    ) {
        if (!config.enabled || currentStackSize <= 1 ||
            entityTracker[entity.uuid] == EntityStatus.PROCESSING ||
            isEntityExcluded(entity)) return

        try {
            entityTracker[entity.uuid] = EntityStatus.PROCESSING
            val world = entity.world as? ServerWorld ?: return

            // Create new entity with stack size of 1
            val spawnParams = SpawnParameters(
                world,
                entity.type,
                entity.pos.add(0.0, SPAWN_HEIGHT_OFFSET, 0.0),
                entity.yaw,
                entity.pitch,
                1
            ).apply {
                if (entity is LivingEntity) {
                    // Copy equipment
                    equipmentToCopy = EquipmentSlot.values().associateWith { slot ->
                        entity.getEquippedStack(slot).copy()
                    }
                    // Store baby state
                    isBaby = entity.isBaby
                }
            }

            // Spawn the new named entity
            world.server.execute {
                spawnNamedEntity(spawnParams, name)
            }

            // Update original stack size
            (entity as? StackDataProvider)?.let { provider ->
                provider.setStackSizeCompat(currentStackSize - 1)
                updateEntityDisplay(entity)
            }
        } finally {
            entityTracker.remove(entity.uuid)
        }
    }


    private fun spawnNamedEntity(params: SpawnParameters, name: Text) {
        try {
            // Create a completely fresh entity without any stack data
            val newEntity = params.entityType.create(params.world)?.apply {
                setPosition(params.position)
                setYaw(params.yaw)
                setPitch(params.pitch)

                // Only set the custom name if nametags are enabled
                if (config.enableNameTags) {
                    customName = name
                    isCustomNameVisible = true
                }

                // Apply baby state if entity is living
                if (this is LivingEntity) {
                    if (params.isBaby) {
                        when (this) {
                            is PassiveEntity -> this.breedingAge = -24000  // Set as baby
                            // Add other specific entity types if needed
                        }
                    }

                    // Copy equipment if needed
                    params.equipmentToCopy?.let { equipment ->
                        equipment.forEach { (slot, stack) ->
                            this.equipStack(slot, stack)
                        }
                        // Set full health
                        this.health = this.maxHealth
                    }
                }
            } ?: return

            // Spawn the entity
            params.world.spawnEntity(newEntity)

        } catch (e: Exception) {
            logDebug("[ReduceAllTheLag] Named spawn error: ${e.message}")
        }
    }

}