package com.lagcut.utils

import com.blanketutils.config.ConfigData
import com.blanketutils.config.ConfigManager
import com.blanketutils.config.ConfigMetadata
import com.blanketutils.utils.LogDebug
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
    var entityStacking: EntityStackingSettings = EntityStackingSettings(),
    var itemStacking: ItemStackingSettings = ItemStackingSettings()
) : ConfigData

data class EntityStackingSettings(
    var enabled: Boolean = true,
    var enableNameTags: Boolean = true,
    val adjustEntityListForStackSize: Boolean = false,
    val hideNametagsThroughBlocks: Boolean = false,
    val stackPlayerNamedEntity: Boolean = false,
    val canStackedEntityPickUpItems: Boolean = true,
    var stackBabyWithAdult: Boolean = false,
    var deleteEntireStackOnKill: Boolean = false,
    var preserveOriginalEntityOnDeath: Boolean = false,
    var clearStacksOnServerStop: Boolean = true,
    var detectionRadius: Double = 20.0,
    var stackingFrequencyTicks: Int = 60,
    var maxStackSize: Int = 64,
    var stackNameFormat: String = "<entityname> <stacksize><bold>x</bold>",
    var excludedEntities: List<String> = listOf("minecraft:armor_stand", "minecraft:chest_minecart"),
    // Added new exclusion types matching ClearLagSettings
    var excludedEntityTypes: List<String> = listOf(
        "entity.cobblemon.pokemon",
        "entity.minecraft.armor_stand",
        "entity.minecraft.trader_llama",
        "entity.taterzens.npc"
    ),
    var nbtExclusionPatterns: List<String> = listOf("Level=100"),
    var excludedDimensions: List<String> = listOf(
        "minecraft:the_end",
        "minecraft:the_nether"
    )
)

data class ItemStackingSettings(
    var enabled: Boolean = true,
    var enableNameTags: Boolean = true,
    var hideNametagsThroughBlocks: Boolean = true,
    var maxStackSize: Int = 99,
    var detectionRadius: Double = 1.5,
    var stackNameFormat: String = "<itemname>: <itemamount><bold>x</bold>",
    var showNametagsOnExcluded: Boolean = true,
    var excludedItems: List<String> = listOf("minecraft:diamond_sword", "minecraft:apple"),
    var nbtExclusionPatterns: List<String> = listOf("Cow Spawn Egg"),
    var excludedDimensions: List<String> = listOf(
        "minecraft:the_end",
        "minecraft:the_nether"
    )
)

data class SoundSettings(
    val sound: String,
    val volume: Double,
    val pitch: Double
)

data class ClearLagSettings(
    var enabled: Boolean = false,
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
    var nbtExclusionPatterns: List<String> = listOf("Plush", "tethered"),
    var excludedDimensions: List<String> = listOf(
        "minecraft:the_end",
        "minecraft:the_nether"
    )
) {
    companion object {
        private val defaultBroadcastMessages = mapOf(
            10 to "<gradient:#ff5555:#55ff55><bold>LC</bold></gradient> Entities will clear in <bold>10</bold> seconds",
            5 to "<gradient:#ff5555:#55ff55><bold>LC</bold></gradient> Entities will clear in <bold>5</bold> seconds",
            4 to "<gradient:#ff5555:#55ff55><bold>LC</bold></gradient> Entities will clear in <bold>4</bold> seconds",
            3 to "<gradient:#ff5555:#55ff55><bold>LC</bold></gradient> Entities will clear in <bold>3</bold> seconds",
            2 to "<gradient:#ff5555:#55ff55><bold>LC</bold></gradient> Entities will clear in <bold>2</bold> seconds",
            1 to "<gradient:#ff5555:#55ff55><bold>LC</bold></gradient> Entities will clear in <bold>1</bold> second",
            0 to "<gradient:#ff5555:#55ff55><bold>LC</bold></gradient> <entityamount> entities have been cleared"
        )

        private val defaultSoundSettings = mapOf(
            5 to SoundSettings("minecraft:block.note_block.pling", 0.3, 1.0),
            4 to SoundSettings("minecraft:block.note_block.pling", 0.3, 1.0),
            3 to SoundSettings("minecraft:block.note_block.pling", 0.3, 1.0),
            2 to SoundSettings("minecraft:block.note_block.pling", 0.3, 1.2),
            1 to SoundSettings("minecraft:block.note_block.pling", 0.3, 1.2),
            0 to SoundSettings("minecraft:entity.wither.death", 0.5, 1.0)
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
    var renderDebugParticles: Boolean = false,
    var disableWaterPlatforms: Boolean = true,
    var chunkGridSize: Int = 3, // 3 = 3x3, 5 = 5x5, 7 = 7x7, etc.
) {
    // Validate that the grid size is odd and within reasonable bounds
    init {
        require(chunkGridSize % 2 == 1) { "Grid size must be odd to have a center chunk" }
        require(chunkGridSize in 1..11) { "Grid size must be between 1 and 11" }
    }

    // Calculate the radius (number of chunks from center to edge)
    val chunkRadius: Int
        get() = (chunkGridSize - 1) / 2
}

object LagCutConfig {
    private val logger = LoggerFactory.getLogger("LagCut")
    private const val MOD_ID = "lagcut"  // Add this constant
    private const val CURRENT_VERSION = "1.0.0"
    private lateinit var configManager: ConfigManager<LagReductionConfig>
    private var isInitialized = false

    private val configMetadata = ConfigMetadata(
        headerComments = listOf(
            "LagCut Configuration File",
            "",
            "This file lets you control how LagCut works to improve your server's performance.",
            "Each section below controls different features of the mod.",
            "",
            "Debug Settings:",
            "- debugEnabled: Turn this on if you want to see detailed information about what the mod is doing",
            "",
            "AI Throttling Settings:",
            "These settings control how mobs behave when they're far from players to save server resources",
            "- enabled: Turn AI throttling on/off",
            "- throttleMovementTicks: If true, mobs move less often when far from players",
            "- throttleGeneralAI: If true, mobs think less often when far from players",
            "- throttleMojangEntities: Apply throttling to vanilla Minecraft mobs",
            "- throttleCobblemonEntities: Apply throttling to Pokémon",
            "- maxRadius: How far away (in blocks) before mobs start being throttled",
            "",
            "Entity Stacking Settings:",
            "These settings control how nearby similar mobs combine into stacks",
            "- enabled: Turn mob stacking on/off",
            "- adjustEntityListForStackSize: Makes the game count stacked mobs correctly (experimental)",
            "- hideNametagsThroughBlocks: Hide stack names when blocks are in the way",
            "- stackPlayerNamedEntity: Allow stacking of mobs that players have named",
            "- canStackedEntityPickUpItems: Let stacked mobs pick up items",
            "- stackBabyWithAdult: Allow baby mobs to stack with adult mobs",
            "- deleteEntireStackOnKill: When you kill a stack, kill all mobs in it",
            "- preserveOriginalEntityOnDeath: Keep special properties when mobs die",
            "- clearStacksOnServerStop: Remove stacks when server stops",
            "- detectionRadius: How close mobs need to be to stack (in blocks)",
            "- stackingFrequencyTicks: How often the mod checks for stackable mobs (20 ticks = 1 second)",
            "- maxStackSize: Maximum number of mobs in one stack",
            "- stackNameFormat: How to show the stack size above mobs (<entityname> and <stacksize> will be replaced)",
            "",
            "Entity Stacking List Settings:",
            "- excludedEntities: List of exact mob IDs that won't stack",
            "  Example format:",
            "  excludedEntities:",
            "    - minecraft:armor_stand",
            "    - minecraft:chest_minecart",
            "    - minecraft:villager",
            "",
            "- excludedEntityTypes: Another way to specify mobs using their type names",
            "  Example format:",
            "  excludedEntityTypes:",
            "    - entity.minecraft.armor_stand",
            "    - entity.minecraft.chest_minecart",
            "    - entity.cobblemon.pokemon",
            "",
            "- nbtExclusionPatterns: Special properties in mob data that prevent stacking",
            "  Example format:",
            "  nbtExclusionPatterns:",
            "    - Level=100",
            "    - Health:20",
            "    - CustomName",
            "",
            "- excludedDimensions: Which Minecraft worlds where stacking won't happen",
            "  Example format:",
            "  excludedDimensions:",
            "    - minecraft:the_end",
            "    - minecraft:the_nether",
            "    - minecraft:overworld",
            "",
            "Item Stacking Settings:",
            "These settings control how dropped items combine on the ground",
            "- enabled: Turn item stacking on/off",
            "- hideNametagsThroughBlocks: Hide stack names when blocks are in the way",
            "- maxStackSize: Maximum items in one stack (limited to 99 for compatibility)",
            "- detectionRadius: How close items need to be to stack (in blocks)",
            "- stackNameFormat: How to show the stack size above items (<itemname> and <itemamount> will be replaced)",
            "- showNametagsOnExcluded: Show names for items that can't stack",
            "",
            "Item Stacking List Settings:",
            "- excludedItems: List of exact item IDs that won't stack",
            "  Example format:",
            "  excludedItems:",
            "    - minecraft:diamond_sword",
            "    - minecraft:netherite_sword",
            "    - minecraft:enchanted_golden_apple",
            "",
            "- excludedItemTypes: Another way to specify items using their type names",
            "  Example format:",
            "  excludedItemTypes:",
            "    - minecraft:diamond_sword",
            "    - minecraft:netherite_sword",
            "    - minecraft:enchanted_golden_apple",
            "",
            "- nbtExclusionPatterns: Special properties in item data that prevent stacking",
            "  Example format:",
            "  nbtExclusionPatterns:",
            "    - Enchantments",
            "    - display",
            "    - Cow Spawn Egg",
            "",
            "- excludedDimensions: Which Minecraft worlds where item stacking won't happen",
            "  Example format:",
            "  excludedDimensions:",
            "    - minecraft:the_end",
            "    - minecraft:the_nether",
            "    - minecraft:overworld",
            "",
            "Clear Lag Settings:",
            "These settings control automatic cleanup of mobs and items",
            "- enabled: Turn automatic cleanup on/off",
            "- cleanupIntervalTicks: How often cleanup happens (20 ticks = 1 second)",
            "- clearCobblemonEntities: Clean up Pokémon",
            "- clearMojangEntities: Clean up vanilla Minecraft mobs",
            "- clearItemEntities: Clean up dropped items",
            "- preservePersistentEntities: Don't clean up special mobs (like named ones)",
            "",
            "Clear Lag List Settings:",
            "- excludedEntities: List of exact entity IDs that won't be cleaned up",
            "  Example format:",
            "  excludedEntities:",
            "    - minecraft:armor_stand",
            "    - minecraft:chest_minecart",
            "    - cobblemon:pikachu",
            "",
            "- excludedEntityTypes: Another way to specify entities that won't be cleaned",
            "  Example format:",
            "  excludedEntityTypes:",
            "    - entity.minecraft.armor_stand",
            "    - entity.minecraft.chest_minecart",
            "    - entity.taterzens.npc",
            "",
            "- nbtExclusionPatterns: Special properties that prevent cleanup",
            "  Example format:",
            "  nbtExclusionPatterns:",
            "    - Level=100",
            "    - CustomName",
            "    - Shiny",
            "",
            "- excludedDimensions: Which Minecraft worlds where cleanup won't happen",
            "  Example format:",
            "  excludedDimensions:",
            "    - minecraft:the_end",
            "    - minecraft:the_nether",
            "    - minecraft:overworld",
            "",
            "Message Formatting:",
            "- broadcastMessages: Messages shown before cleanup (seconds -> message)",
            "  Example format:",
            "  broadcastMessages:",
            "    10: \"<bold>Cleanup in 10 seconds</bold>\"",
            "    5: \"<bold>Cleanup in 5 seconds</bold>\"",
            "    0: \"<bold>Cleaned up <entityamount> entities</bold>\"",
            "",
            "Sound Settings:",
            "- broadcastsounds: Sounds to play before cleanup (seconds -> sound)",
            "  Example format:",
            "  broadcastsounds:",
            "    5:",
            "      sound: minecraft:block.note_block.hat",
            "      volume: 0.2",
            "      pitch: 0.5",
            "",
            "Quick Tips:",
            "1. Use '/lc reload' after changing settings",
            "2. Start with small changes and test",
            "3. If something goes wrong, you can always reset to defaults",
            "4. Use '/lc inspectnearest' to get exact names of mobs and items"
        ),
        footerComments = listOf(
            "End of LagCut Configuration",
            "For more information, visit: https://github.com/Hysocs/lagcut-1.21.1"
        ),
        sectionComments = mapOf(
            "version" to "WARNING: Do not edit this value - doing so may corrupt your configuration",
            "configId" to "WARNING: Do not edit this value - changing this will create a new configuration file",
            "clearLag.excludedEntityTypes" to "Use '/lc inspectnearest' to identify entity types that you want to exclude from cleanup",
            "clearLag.excludedLabels" to "Labels can be found at https://gitlab.com/cable-mc/cobblemon/-/blob/main/common/src/main/kotlin/com/cobblemon/mod/common/api/pokemon/labels/CobblemonPokemonLabels.kt",
            "entityStacking.adjustEntityListForStackSize" to "This experimental feature adjusts the entity list to reflect stacked entities. While functional in testing, it may cause compatibility issues with custom entities and other mods.",
            "itemStacking.maxStackSize" to "The stack size is limited to 99 to maintain compatibility across mods. While I could implement larger stacks, many mods that read NBT data or handle item stacks would break due to changes introduced in Minecraft 1.20. This limitation ensures broad compatibility with other mods.",
            "aiThrottling.chunkGridSize" to "Sets the chunk grid size for AI processing around players. Valid values: 3 (3x3 grid, default), 5 (5x5 grid), 7 (7x7 grid), 9 (9x9 grid), or 11 (11x11 grid). Larger grids process more chunks but may impact performance."
        ),
        includeTimestamp = true,
        includeVersion = true
    )

    fun initializeAndLoad() {
        if (!isInitialized) {
            // First initialize LogDebug with default state (disabled)
            LogDebug.init(MOD_ID, false)

            // Then initialize and load config
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
        println("[DEBUG-$MOD_ID] Loading configuration...")
        configManager.reloadConfig()
        println("[DEBUG-$MOD_ID] Configuration loaded, updating debug state...")
        updateDebugState()
        println("[DEBUG-$MOD_ID] Debug state updated")
    }

    fun reloadBlocking() {
        println("[DEBUG-$MOD_ID] Starting config reload...")
        runBlocking {
            configManager.reloadConfig()
            println("[DEBUG-$MOD_ID] Config reloaded, updating debug state...")
            updateDebugState()
            println("[DEBUG-$MOD_ID] Reload complete")
        }
        // Add a test debug message to verify debug state
        LogDebug.debug("Config reload completed - this message should appear if debug is enabled", MOD_ID)
    }

    private fun updateDebugState() {
        val currentConfig = configManager.getCurrentConfig()
        val debugEnabled = currentConfig.debugEnabled
        println("[DEBUG-$MOD_ID] Setting debug state to: $debugEnabled")
        LogDebug.setDebugEnabledForMod(MOD_ID, debugEnabled)
        // Add a test debug message
        LogDebug.debug("Debug state updated - this message should appear if debug is enabled", MOD_ID)
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