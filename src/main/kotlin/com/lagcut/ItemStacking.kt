package com.lagcut

import net.minecraft.entity.ItemEntity
import net.minecraft.registry.Registries
import net.minecraft.component.DataComponentTypes
import com.lagcut.utils.LagCutConfig
import com.lagcut.utils.LagCutConfig.logDebug
import com.lagcut.utils.MiniMessageHelper
import net.minecraft.component.ComponentType
import net.minecraft.item.ItemStack
import net.minecraft.nbt.NbtCompound
import net.minecraft.server.world.ServerWorld
import net.minecraft.text.Text
import java.util.concurrent.ConcurrentHashMap
import java.util.UUID

object ItemStackingManager {
    private const val ABSOLUTE_MAX_STACK = 99  // New constant for maximum stack size
    private var initialized = false
    private var excludedItems: Set<String> = emptySet()
    private val itemTracker = ConcurrentHashMap<UUID, Boolean>()

    // Cache config values
    private val config get() = LagCutConfig.config.itemStacking

    // Add this property to get the configured stack size
    private val configuredStackSize: Int
        get() = config.maxStackSize.coerceIn(1, ABSOLUTE_MAX_STACK)


    // Cache component types to check
    private val componentsToCheck = listOf(
        DataComponentTypes.CUSTOM_DATA,
        DataComponentTypes.ENCHANTMENTS,
        DataComponentTypes.FIREWORKS,
        DataComponentTypes.TRIM,
        DataComponentTypes.ATTRIBUTE_MODIFIERS,
        DataComponentTypes.CUSTOM_MODEL_DATA,
        DataComponentTypes.DAMAGE,
        DataComponentTypes.CHARGED_PROJECTILES,
        DataComponentTypes.BUNDLE_CONTENTS,
        DataComponentTypes.POTION_CONTENTS,
        DataComponentTypes.SUSPICIOUS_STEW_EFFECTS,
        DataComponentTypes.WRITABLE_BOOK_CONTENT,
        DataComponentTypes.WRITTEN_BOOK_CONTENT,
        DataComponentTypes.LODESTONE_TRACKER,
        DataComponentTypes.PROFILE,
        DataComponentTypes.MAP_ID,
        DataComponentTypes.MAP_DECORATIONS,
        DataComponentTypes.BANNER_PATTERNS,
        DataComponentTypes.BASE_COLOR,
        DataComponentTypes.CONTAINER,
        DataComponentTypes.BLOCK_ENTITY_DATA,
        DataComponentTypes.STORED_ENCHANTMENTS,
        DataComponentTypes.LOCK
    )

    fun initialize() {
        if (!config.enabled) {
            logDebug("[ReduceAllTheLag] Item stacking is disabled")
            return
        }
        excludedItems = config.excludedItems.toSet()
        initialized = true
    }

    fun reinitialize() {
        excludedItems = config.excludedItems.toSet()
        if (!initialized) {
            initialize()
        }
    }

    fun tryMergeItemEntities(item: ItemEntity): Boolean {
        // Update display regardless of exclusion status
        if (!item.world.isClient) {
            updateItemDisplay(item)
        }

        if (!isValidForMerge(item)) return false

        val nearbyItems = findValidNearbyItems(item)
        if (nearbyItems.isEmpty()) {
            handleNametagVisibility(item)
            return false
        }

        return mergeWithNearbyItems(item, nearbyItems)
    }

    private fun isValidForMerge(item: ItemEntity): Boolean =
        !item.world.isClient &&
                !item.isRemoved &&
                !item.stack.isEmpty &&
                (item.isOnGround || isInWater(item)) &&  // Custom water check
                config.enabled &&
                !isItemExcluded(item)

    // Add this new function
    private fun isInWater(item: ItemEntity): Boolean {
        val pos = item.blockPos
        val fluid = item.world.getBlockState(pos).fluidState
        return !fluid.isEmpty
    }

    private fun isItemExcluded(item: ItemEntity): Boolean {
        val itemType = Registries.ITEM.getId(item.stack.item).toString()
        val config = LagCutConfig.config.itemStacking

        // Check basic item exclusions
        if (excludedItems.contains(itemType)) {
            logDebug("[DEBUG] Item $itemType is in excludedItems")
            return true
        }


        // Check dimension exclusions
        val dimensionId = (item.world as? ServerWorld)?.registryKey?.value?.toString()
        if (dimensionId != null && config.excludedDimensions.any { it.equals(dimensionId, ignoreCase = true) }) {
            logDebug("[DEBUG] Item in excluded dimension: $dimensionId")
            return true
        }

        // Check NBT patterns
        val nbt = NbtCompound()
        item.writeNbt(nbt)

        // Convert NBT to string for raw searching
        val nbtString = nbt.toString()

        // Check each NBT exclusion pattern
        val hasMatchingNbtPattern = config.nbtExclusionPatterns.any { pattern ->
            val matchFound = nbtString.contains(pattern)
            if (matchFound) {
                logDebug("[DEBUG] Found matching NBT value: $pattern in NBT: $nbtString")
            }
            matchFound
        }

        if (hasMatchingNbtPattern) {
            logDebug("[DEBUG] Item matches NBT exclusion pattern")
            return true
        }

        return false
    }

    private fun findValidNearbyItems(item: ItemEntity) =
        item.world.getEntitiesByClass(
            ItemEntity::class.java,
            item.boundingBox.expand(config.detectionRadius)
        ) { other ->
            other != item &&
                    other.stack.isOf(item.stack.item) &&
                    !other.isRemoved &&
                    (other.isOnGround || isInWater(other)) &&
                    !isItemExcluded(other) &&  // Add this check
                    areComponentsEqual(other.stack, item.stack)
        }

    private fun mergeWithNearbyItems(item: ItemEntity, nearbyItems: List<ItemEntity>): Boolean {
        var merged = false
        itemTracker[item.uuid] = true

        try {
            for (other in nearbyItems) {
                if (item.isRemoved || other.isRemoved) continue

                val totalCount = item.stack.count + other.stack.count

                when {
                    // Case 1: Total count fits within configured stack size
                    totalCount <= configuredStackSize -> {
                        item.stack.count = totalCount
                        other.discard()
                        merged = true
                    }
                    // Case 2: Current stack is already at or above configured stack size
                    item.stack.count >= configuredStackSize -> {
                        // Don't merge, maybe create a new stack
                        if (other.stack.count > configuredStackSize) {
                            splitOverflowStack(other)
                        }
                        continue
                    }
                    // Case 3: Merging would exceed configured stack size, fill up to limit and adjust other stack
                    else -> {
                        val transferAmount = configuredStackSize - item.stack.count
                        item.stack.count = configuredStackSize
                        other.stack.decrement(transferAmount)

                        // Check if the remaining stack needs splitting
                        if (other.stack.count > configuredStackSize) {
                            splitOverflowStack(other)
                        }

                        merged = true
                    }
                }

                updateItemDisplay(item)
                if (!other.isRemoved) {
                    updateItemDisplay(other)
                }
            }
        } finally {
            itemTracker.remove(item.uuid)
        }

        handleNametagVisibility(item)
        return merged
    }

    private fun splitOverflowStack(item: ItemEntity) {
        while (item.stack.count > configuredStackSize) {
            val newStack = ItemStack(item.stack.item, configuredStackSize)
            // Copy all components to ensure identical properties
            for (componentType in componentsToCheck) {
                val component = item.stack.get(componentType)
                if (component != null) {
                    @Suppress("UNCHECKED_CAST")
                    newStack.set(componentType as ComponentType<Any>, component)
                }
            }

            val newEntity = ItemEntity(
                item.world,
                item.x,
                item.y,
                item.z,
                newStack
            )

            // Adjust velocity slightly to prevent stacks from immediately remerging
            newEntity.setVelocity(
                item.velocity.x + (Math.random() - 0.5) * 0.1,
                item.velocity.y + (Math.random() - 0.5) * 0.1,
                item.velocity.z + (Math.random() - 0.5) * 0.1
            )

            item.stack.decrement(configuredStackSize)
            item.world.spawnEntity(newEntity)
            updateItemDisplay(newEntity)
        }
        updateItemDisplay(item)
    }
    private fun areComponentsEqual(stack1: ItemStack, stack2: ItemStack): Boolean =
        componentsToCheck.all { componentType ->
            stack1.get(componentType) == stack2.get(componentType)
        }

    fun updateItemDisplay(item: ItemEntity) {
        // First check if item stacking is enabled
        if (!config.enabled) {
            removeItemDisplay(item)
            return
        }

        // Check if nametags are enabled
        if (!config.enableNameTags) {
            removeItemDisplay(item)
            return
        }

        // Check if item is excluded and nametags should be hidden
        if (isItemExcluded(item) && !config.showNametagsOnExcluded) {
            removeItemDisplay(item)
            return
        }

        try {
            val itemName = formatItemName(item.stack.item)
            val format = config.stackNameFormat
                .replace("<itemname>", itemName)
                .replace("<itemamount>", item.stack.count.toString())

            item.customName = MiniMessageHelper.parse(format)
            item.isCustomNameVisible = true
        } catch (e: Exception) {
            // Fallback to simple display
            item.customName = Text.literal("${item.stack.item.name.string} x${item.stack.count}")
            item.isCustomNameVisible = true
        }
    }


    private fun formatItemName(item: net.minecraft.item.Item): String =
        Registries.ITEM.getId(item).path
            .replace('_', ' ')
            .split(" ")
            .joinToString(" ") { it.replaceFirstChar(Char::titlecase) }

    private fun removeItemDisplay(item: ItemEntity) {
        item.customName = null
        item.isCustomNameVisible = false
    }

    private fun handleNametagVisibility(item: ItemEntity) {
        if (!config.enabled ||
            !config.enableNameTags ||
            (isItemExcluded(item) && !config.showNametagsOnExcluded)) {
            removeItemDisplay(item)
        }
    }
}