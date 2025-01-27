package com.lagcut.utils

import com.blanketutils.config.ConfigData
import com.blanketutils.config.ConfigManager
import com.blanketutils.config.ConfigMetadata
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.StandardOpenOption

data class LagReductionConfig(
    override val version: String = "1.0.0",
    override val configId: String = "lagcut",
    var debugEnabled: Boolean = true,
    var aiThrottling: AIThrottlingSettings = AIThrottlingSettings(),
    var clearLag: ClearLagSettings = ClearLagSettings(),
    var entityBehavior: EntityBehaviorSettings = EntityBehaviorSettings(),
    var entityStacking: EntityStackingSettings = EntityStackingSettings(),
    var itemBehavior: ItemBehaviorSettings = ItemBehaviorSettings(),
    var itemStacking: ItemStackingSettings = ItemStackingSettings()
) : ConfigData

data class EntityBehaviorSettings(
    var allowItemPickup: Boolean = false,
    val hideNametagsThroughBlocks: Boolean = false
)

data class EntityStackingSettings(
    var enabled: Boolean = true,
    var stackBabyWithAdult: Boolean = false,
    var deleteEntireStackOnKill: Boolean = false,
    var preserveOriginalEntityOnDeath: Boolean = false,
    var clearStacksOnServerStop: Boolean = true,
    var detectionRadius: Double = 20.0,
    var stackingFrequencyTicks: Int = 60,
    var maxStackSize: Int = 64,
    var stackNameFormat: String = "<entityname> <stacksize><bold>x</bold>",
    var excludedEntities: List<String> = listOf("minecraft:armor_stand", "minecraft:chest_minecart")
)

data class ItemBehaviorSettings(
    var hideNametagsThroughBlocks: Boolean = true
)

data class ItemStackingSettings(
    var enabled: Boolean = true,
    var maxStackSize: Int = 99,
    var detectionRadius: Double = 1.5,
    var stackNameFormat: String = "<itemname>: <itemamount><bold>x</bold>",
    var showNametagsOnExcluded: Boolean = true,
    var excludedItems: List<String> = listOf("minecraft:diamond_sword", "minecraft:apple")
)

data class SoundSettings(
    val sound: String,
    val volume: Double,
    val pitch: Double
)

data class ClearLagSettings(
    var enabled: Boolean = true,
    var cleanupIntervalTicks: Int = 1200,
    var broadcastMessages: Map<Int, String> = defaultBroadcastMessages,
    var broadcastsounds: Map<Int, SoundSettings> = defaultSoundSettings,
    var clearCobblemonEntities: Boolean = true,
    var clearMojangEntities: Boolean = true,
    var clearItemEntities: Boolean = true,
    var preservePersistentEntities: Boolean = true,
    var excludedEntities: List<String> = listOf(
        "minecraft:armor_stand",
        "minecraft:chest_minecart",
        "cobblemon:pikachu"
    ),
    var excludedEntityTypes: List<String> = defaultExcludedEntityTypes,
    var excludedLabels: List<String> = defaultExcludedLabels,
    var nbtExclusionPatterns: List<String> = listOf("Level=100"),
    var excludedDimensions: List<String> = listOf(
        "minecraft:the_end",
        "minecraft:the_nether"
    )
) {
    companion object {
        private val defaultBroadcastMessages = mapOf(
            10 to "<hover:LagCut is clearing entities to improve server performance><gradient:#ff5555:#55ff55><bold>LC</bold></gradient></hover> Entities will clear in <bold>10</bold> seconds",
            5 to "<hover:LagCut is clearing entities to improve server performance><gradient:#ff5555:#55ff55><bold>LC</bold></gradient></hover> Entities will clear in <bold>5</bold> seconds",
            4 to "<hover:LagCut is clearing entities to improve server performance><gradient:#ff5555:#55ff55><bold>LC</bold></gradient></hover> Entities will clear in <bold>4</bold> seconds",
            3 to "<hover:LagCut is clearing entities to improve server performance><gradient:#ff5555:#55ff55><bold>LC</bold></gradient></hover> Entities will clear in <bold>3</bold> seconds",
            2 to "<hover:LagCut is clearing entities to improve server performance><gradient:#ff5555:#55ff55><bold>LC</bold></gradient></hover> Entities will clear in <bold>2</bold> seconds",
            1 to "<hover:LagCut is clearing entities to improve server performance><gradient:#ff5555:#55ff55><bold>LC</bold></gradient></hover> Entities will clear in <bold>1</bold> second",
            0 to "<hover:LagCut is clearing entities to improve server performance><gradient:#ff5555:#55ff55><bold>LC</bold></gradient></hover> <entityamount> entities have been cleared"
        )

        private val defaultSoundSettings = mapOf(
            5 to SoundSettings("minecraft:block.note_block.hat", 0.2, 0.5),
            4 to SoundSettings("minecraft:block.note_block.hat", 0.2, 0.5),
            3 to SoundSettings("minecraft:block.note_block.hat", 0.2, 0.5),
            2 to SoundSettings("minecraft:block.note_block.hat", 0.2, 0.5),
            1 to SoundSettings("minecraft:block.note_block.hat", 0.2, 0.5)
        )

        private val defaultExcludedEntityTypes = listOf(
            "entity.minecraft.armor_stand",
            "entity.minecraft.chest_minecart",
            "entity.taterzens.npc"
        )

        private val defaultExcludedLabels = listOf(
            "legendary",
            "mythical",
            "ultra_beast",
            "restricted",
            "powerhouse",
            "paradox",
            "customized_official",
            "custom"
        )
    }
}

data class AIThrottlingSettings(
    var enabled: Boolean = true,
    var throttleMovementTicks: Boolean = false,
    var throttleGeneralAI: Boolean = true,
    var throttleMojangEntities: Boolean = false,
    var throttleCobblemonEntities: Boolean = true,
    var maxRadius: Double = 50.0
) {
    val layer1End: Double get() = maxRadius * 0.3
    val layer2End: Double get() = maxRadius * 0.7
    val layer3End: Double get() = maxRadius

    val layer1TickCycle: Int get() = 1
    val layer1TicksBlocked: Int get() = 0

    val layer2TickCycle: Int get() = 4
    val layer2TicksBlocked: Int get() = 2

    val layer3TickCycle: Int get() = 7
    val layer3TicksBlocked: Int get() = 5
}

object LagCutConfig {
    private val logger = LoggerFactory.getLogger("LagCut")
    private const val CURRENT_VERSION = "1.0.0"
    private lateinit var configManager: ConfigManager<LagReductionConfig>
    private var isInitialized = false

    private val configMetadata = ConfigMetadata(
        headerComments = listOf(
            "LagCut Configuration File",
            "",
            "This configuration file controls various aspects of the LagCut mod.",
            "Each section is documented below with its purpose and available options.",
            "",
            "Debug Settings:",
            "- debugEnabled: Enable detailed debug logging",
            "",
            "AI Throttling Settings:",
            "Controls how entity AI behaves at different distances from players",
            "- enabled: Master switch for AI throttling",
            "- throttleMovementTicks: Whether to throttle entity movement",
            "- throttleGeneralAI: Whether to throttle general AI behaviors",
            "- maxRadius: Maximum distance for AI throttling (in blocks)",
            "",
            "Entity Stacking Settings:",
            "Controls how entities of the same type are combined",
            "- enabled: Master switch for entity stacking",
            "- stackBabyWithAdult: Whether baby mobs can stack with adults",
            "- maxStackSize: Maximum number of entities in a stack",
            "- detectionRadius: How close entities must be to stack",
            "- stackNameFormat: How stack size is displayed",
            "- excludedEntities: Entities that won't be stacked",
            "",
            "Clear Lag Settings:",
            "Controls automatic entity cleanup",
            "- enabled: Master switch for clear lag",
            "- cleanupIntervalTicks: How often cleanup runs (20 ticks = 1 second)",
            "- broadcastMessages: Messages shown before cleanup",
            "- excludedEntities: Entities that won't be cleared",
            "- preserveTarterzens: Whether to preserve NPC entities",
            "",
            "Item Behavior Settings:",
            "Controls how items behave",
            "- hideNametagsThroughBlocks: Hide item names through solid blocks",
            "",
            "Item Stacking Settings:",
            "Controls how dropped items combine",
            "- enabled: Master switch for item stacking",
            "- maxStackSize: Maximum items in a stack",
            "- detectionRadius: How close items must be to stack",
            "- excludedItems: Items that won't be stacked",
            "",
            "Entity Behavior Settings:",
            "Controls general entity behavior",
            "- allowItemPickup: Whether entities can pick up items",
            "- hideNametagsThroughBlocks: Hide entity names through blocks",
            "",
            "NBT Exclusion Patterns:",
            "Patterns to match against entity NBT data for exclusion",
            "Example: \"Level=100\" will prevent clearing entities with that NBT",
            "",
            "Excluded Aspects:",
            "Special characteristics that prevent entity clearing",
            "Default aspects: shiny, legendary, owned, battling, etc."
        ),
        footerComments = listOf(
            "End of LagCut Configuration",
            "For more information, visit: https://github.com/Hysocs/lagcut-1.21.1"
        ),
        sectionComments = mapOf(
            "version" to "WARNING: Do not edit this value - doing so may corrupt your configuration",
            "configId" to "WARNING: Do not edit this value - changing this will create a new configuration file",
            "clearLag.excludedEntityTypes" to "Use '/lc inspectnearest' to identify entity types that you want to exclude from cleanup",
            "clearLag.excludedLabels" to "Labels can be found at https://gitlab.com/cable-mc/cobblemon/-/blob/main/common/src/main/kotlin/com/cobblemon/mod/common/api/pokemon/labels/CobblemonPokemonLabels.kt"
        ),
        includeTimestamp = true,
        includeVersion = true
    )

    fun logDebug(message: String) {
        if (config.debugEnabled) {
            logger.debug(message)
        }
    }

    fun initializeAndLoad() {
        if (!isInitialized) {
            initialize()
            runBlocking { load() }
            isInitialized = true
        }
    }

    private fun initialize() {
        configManager = ConfigManager(
            currentVersion = CURRENT_VERSION,
            defaultConfig = LagReductionConfig(),
            configClass = LagReductionConfig::class,
            metadata = configMetadata
        )
    }

    private suspend fun load() {
        configManager.reloadConfig()
    }

    fun reloadBlocking() {
        runBlocking { configManager.reloadConfig() }
    }

    val config: LagReductionConfig
        get() = configManager.getCurrentConfig()

    fun cleanup() {
        if (isInitialized) {
            configManager.cleanup()
            isInitialized = false
        }
    }
}