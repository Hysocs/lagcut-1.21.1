package com.lagcut

import com.blanketutils.colors.KyoriHelper
import com.lagcut.utils.LagCutConfig
import com.blanketutils.utils.logDebug
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.minecraft.entity.ItemEntity
import net.minecraft.entity.mob.MobEntity
import net.minecraft.entity.decoration.ArmorStandEntity
import net.minecraft.registry.Registries
import net.minecraft.server.MinecraftServer
import net.minecraft.server.world.ServerWorld
import net.minecraft.sound.SoundCategory
import net.minecraft.util.Identifier
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private object ReflectionCache {
    val pokemonEntityClass = try {
        Class.forName("com.cobblemon.mod.common.entity.pokemon.PokemonEntity")
    } catch (e: ClassNotFoundException) { null }

    val pokemonClass = try {
        Class.forName("com.cobblemon.mod.common.pokemon.Pokemon")
    } catch (e: ClassNotFoundException) { null }

    val speciesClass = try {
        Class.forName("com.cobblemon.mod.common.pokemon.PokemonSpecies")
    } catch (e: ClassNotFoundException) { null }

    val formDataClass = try {
        Class.forName("com.cobblemon.mod.common.pokemon.FormData")
    } catch (e: ClassNotFoundException) { null }

    val methods = mapOf(
        "getPokemon" to pokemonEntityClass?.getMethod("getPokemon"),
        "getSpecies" to pokemonClass?.getMethod("getSpecies"),
        "getSpeciesName" to speciesClass?.getDeclaredMethod("getName"),
        "getForm" to pokemonClass?.getMethod("getForm"),
        "getAspects" to formDataClass?.getMethod("getAspects"),
        "getFormName" to formDataClass?.getMethod("getName"),
        "isShiny" to pokemonClass?.getMethod("getShiny"),
        "getOwner" to pokemonClass?.getMethod("getOwnerPlayer"),
        "isBattling" to pokemonEntityClass?.getMethod("isBattling"),
        "getDisplayName" to pokemonClass?.getDeclaredMethod("getDisplayName")
    )
    val hasLabelsMethod = pokemonClass?.getMethod("hasLabels", Array<String>::class.java)
}

object ClearLag {
    private val logger = LoggerFactory.getLogger("ClearLagHandler")
    private var initialized = false
    // Instead of lastClearTime we now use nextClearTime.
    private var nextClearTime = 0L
    private var lastBroadcastSecond = -1
    private var lastBroadcastSoundSecond = -1
    private val blocklist = ConcurrentHashMap.newKeySet<String>()
    private val config get() = LagCutConfig.config.clearLag
    private val nameCache = ConcurrentHashMap<Any, String>(100)

    val scheduler = Executors.newSingleThreadScheduledExecutor()
    private const val CHUNK_SIZE = 50
    // Delay between processing worlds in the heavy clear task (in milliseconds)
    private const val CHUNK_DELAY_MS = 100L

    fun initialize() {
        if (!config.enabled) {
            logDebug("[DEBUG] ClearLag disabled", "lagcut")
            return
        }
        updateBlocklist()
        ServerLifecycleEvents.SERVER_STARTED.register { server: MinecraftServer ->
            scheduler.scheduleAtFixedRate({
                server.executeSync {
                    handleClearLag(server)
                }
            }, 0, 1, TimeUnit.SECONDS)
        }
        initialized = true
        logDebug("[DEBUG] ClearLag initialized", "lagcut")
    }

    fun reinitialize() {
        updateBlocklist()
        if (!initialized) initialize()
        nameCache.clear()
        logDebug("[DEBUG] ClearLag reinitialized", "lagcut")
    }

    fun shutdown() {
        // Shutdown the scheduler to prevent tasks from lingering after server stop.
        scheduler.shutdownNow()
        logDebug("[DEBUG] ClearLag scheduler shut down", "lagcut")
    }

    /**
     * Instead of basing our timing on time elapsed since the last clear,
     * we calculate a fixed nextClearTime. Every tick we compute the seconds remaining,
     * and if the current time has reached or passed nextClearTime, we clear entities and update nextClearTime.
     */
    private fun handleClearLag(server: MinecraftServer) {
        val now = System.currentTimeMillis()
        val intervalMs = (config.cleanupIntervalTicks * 50L).coerceAtLeast(1000L)

        // Initialize nextClearTime if it hasn't been set
        if (nextClearTime == 0L) {
            nextClearTime = now + intervalMs
            lastBroadcastSecond = -1
            lastBroadcastSoundSecond = -1
        }

        // Calculate remaining time
        val remainingMs = (nextClearTime - now).coerceAtLeast(0)
        val secondsRemaining = (remainingMs / 1000).toInt()

        // Handle broadcasts if we have a new second
        if (secondsRemaining != lastBroadcastSecond) {
            // Handle message broadcasts
            config.broadcastMessages[secondsRemaining]?.let { message ->
                // Skip the 0-second message as it's handled after clearing
                if (secondsRemaining > 0) {
                    val processedMessage = message.replace("<entityamount>", "entities")
                    broadcast(server, processedMessage)
                    logDebug("[DEBUG] handleClearLag: broadcast message for $secondsRemaining seconds: '$processedMessage'", "lagcut")
                }
            }
            lastBroadcastSecond = secondsRemaining

            // Handle sound broadcasts
            config.broadcastsounds[secondsRemaining]?.let { soundSetting ->
                val soundId = Identifier.tryParse(soundSetting.sound)
                if (soundId != null) {
                    val soundEvent = Registries.SOUND_EVENT.get(soundId)
                    if (soundEvent != null) {
                        server.playerManager.playerList.forEach { player ->
                            player.world.playSound(
                                null,
                                player.blockPos,
                                soundEvent,
                                SoundCategory.PLAYERS,
                                soundSetting.volume.toFloat(),
                                soundSetting.pitch.toFloat()
                            )
                        }
                    }
                }
                lastBroadcastSoundSecond = secondsRemaining
            }
        }

        // Check if it's time to clear
        if (now >= nextClearTime) {
            // Set next clear time
            nextClearTime = now + intervalMs
            lastBroadcastSecond = -1
            lastBroadcastSoundSecond = -1

            // Perform the clear
            clearEntitiesInChunks(server)
        }
    }

    /**
     * Schedules clearEntities for each world with a small delay between each,
     * so that the heavy processing is spread over time rather than in a single tick.
     */
    private fun clearEntitiesInChunks(server: MinecraftServer) {
        val worldsToClear = server.worlds.filter { world ->
            val dimensionId = world.registryKey.value.toString()
            !config.excludedDimensions.any { it.equals(dimensionId, ignoreCase = true) }
        }

        var totalEntitiesCleared = 0
        var worldsProcessed = 0

        worldsToClear.forEachIndexed { index, world ->
            // Schedule each world's clear operation with a delay
            scheduler.schedule({
                server.executeSync {
                    val pokemonCleared = if (Lagcut.isCobblemonPresent && config.clearCobblemonEntities)
                        clearPokemonEntities(world) else 0
                    val mobsCleared = if (config.clearMojangEntities) clearMobEntities(world) else 0
                    val itemsCleared = if (config.clearItemEntities) clearItemEntities(world) else 0

                    val worldTotal = pokemonCleared + mobsCleared + itemsCleared
                    totalEntitiesCleared += worldTotal
                    worldsProcessed++

                    logDebug("[DEBUG] Cleared in ${world.registryKey.value}: Pokemon: $pokemonCleared, Mobs: $mobsCleared, Items: $itemsCleared", "lagcut")

                    // If this was the last world, broadcast the final message
                    if (worldsProcessed == worldsToClear.size) {
                        val summary = config.broadcastMessages[0]?.replace("<entityamount>", totalEntitiesCleared.toString())
                            ?: "Cleared $totalEntitiesCleared entities."
                        broadcast(server, summary)
                    }
                }
            }, index * CHUNK_DELAY_MS, TimeUnit.MILLISECONDS)
        }
    }

    private fun updateBlocklist() {
        blocklist.clear()
        blocklist.addAll(config.excludedEntities)
    }

    private fun broadcast(server: MinecraftServer, message: String) {
        val formatted = KyoriHelper.parseToMinecraft(message)
        server.playerManager.playerList.forEach { player ->
            player.sendMessage(formatted)
        }
    }

    fun clearAll(server: MinecraftServer) = server.worlds.sumOf { world ->
        (if (Lagcut.isCobblemonPresent) clearPokemonEntities(world, true) else 0) +
                clearMobEntities(world) + clearItemEntities(world)
    }

    fun clearMobs(server: MinecraftServer) = server.worlds.sumOf { clearMobEntities(it) }
    fun clearItems(server: MinecraftServer) = server.worlds.sumOf { clearItemEntities(it) }
    fun clearCobblemonMobs(server: MinecraftServer) =
        if (!Lagcut.isCobblemonPresent) 0 else server.worlds.sumOf { clearPokemonEntities(it) }

    // --- Updated method: process Pokémon entities in chunks ---
    private fun clearPokemonEntities(world: ServerWorld, bypassChecks: Boolean = false): Int {
        if (ReflectionCache.pokemonEntityClass == null || ReflectionCache.methods["getPokemon"] == null) return 0
        var count = 0

        // Convert entities to a list and filter for Pokémon entities
        val pokemonEntities = world.iterateEntities().toList().filter { ReflectionCache.pokemonEntityClass.isInstance(it) }
        pokemonEntities.chunked(CHUNK_SIZE).forEach { chunk ->
            chunk.forEach { entity ->
                try {
                    if (entity.isRemoved) return@forEach
                    if (shouldExcludeEntity(entity)) return@forEach
                    if (bypassChecks) {
                        entity.discard()
                        count++
                        return@forEach
                    }

                    val pokemonInstance = ReflectionCache.methods["getPokemon"]!!.invoke(entity)
                    val pokemonName = getPokemonName(pokemonInstance)
                    if (shouldPreservePokemon(entity, pokemonInstance, pokemonName)) return@forEach

                    entity.discard()
                    count++
                } catch (e: Exception) {
                    logDebug("[DEBUG] Error processing Pokemon: ${e.message}", "lagcut")
                }
            }
        }
        return count
    }

    // Snapshot entities first to avoid processing newly spawned entities during clearing
    private fun clearMobEntities(world: ServerWorld): Int {
        var count = 0
        val entities = world.iterateEntities().toList()
        entities.forEach { entity ->
            try {
                if (entity is MobEntity || entity is ArmorStandEntity) {
                    try {
                        if (entity.isRemoved) return@forEach
                        if (shouldExcludeEntity(entity)) return@forEach
                        if (config.preservePersistentEntities && entity is MobEntity && entity.isPersistent) return@forEach

                        entity.discard()
                        count++
                    } catch (e: Exception) {
                        logDebug("[DEBUG] Error processing Mob: ${e.message}", "lagcut")
                    }
                }
            } catch (e: Exception) {
                logDebug("[DEBUG] Error handling entity in clearMobEntities: ${e.message}", "lagcut")
            }
        }
        return count
    }

    // Snapshot entities first to avoid processing newly spawned entities during clearing
    private fun clearItemEntities(world: ServerWorld): Int {
        var count = 0
        val entities = world.iterateEntities().toList()
        entities.forEach { entity ->
            try {
                if (entity is ItemEntity) {
                    try {
                        if (entity.isRemoved) return@forEach
                        if (shouldExcludeEntity(entity)) return@forEach

                        entity.discard()
                        count++
                    } catch (e: Exception) {
                        logDebug("[DEBUG] Error processing ItemEntity: ${e.message}", "lagcut")
                    }
                }
            } catch (e: Exception) {
                logDebug("[DEBUG] Error handling entity in clearItemEntities: ${e.message}", "lagcut")
            }
        }
        return count
    }

    /**
     * Applies exclusions for every entity by checking:
     * - Excluded entity types and blocklist.
     * - NBT exclusion patterns.
     */
    private fun shouldExcludeEntity(entity: net.minecraft.entity.Entity): Boolean {
        val entityType = entity.type.toString()
        if (config.excludedEntityTypes.any { it.equals(entityType, ignoreCase = true) }) {
            logDebug("[DEBUG] Entity type $entityType is in excludedEntityTypes", "lagcut")
            return true
        }
        if (isEntityInBlocklist(entityType)) return true

        // Check NBT patterns for every entity
        if (config.nbtExclusionPatterns.isNotEmpty()) {
            val nbt = net.minecraft.nbt.NbtCompound()
            entity.writeNbt(nbt)
            val nbtString = nbt.toString()
            if (config.nbtExclusionPatterns.any { pattern -> nbtString.contains(pattern) }) {
                logDebug("[DEBUG] Entity $entityType matches NBT exclusion pattern in NBT: $nbtString", "lagcut")
                return true
            }
        }
        return false
    }

    private fun shouldPreservePokemon(pokemon: Any, pokemonInstance: Any, pokemonName: String): Boolean {
        if (isEntityInBlocklist(pokemonName, true)) {
            logDebug("[DEBUG] Pokemon $pokemonName is in blocklist", "lagcut")
            return true
        }
        try {
            ReflectionCache.hasLabelsMethod?.let { method ->
                val matchingLabels = config.excludedLabels.filter { label ->
                    method.invoke(pokemonInstance, arrayOf(label)) as? Boolean == true
                }
                if (matchingLabels.isNotEmpty()) {
                    logDebug("[DEBUG] Preserving Pokemon due to labels: $matchingLabels", "lagcut")
                    return true
                }
            }
            val nbt = net.minecraft.nbt.NbtCompound()
            (pokemon as? net.minecraft.entity.Entity)?.writeNbt(nbt)
            val pokemonNbt = nbt.getCompound("Pokemon")
            if (pokemonNbt != null) {
                val matchingNbtPatterns = config.nbtExclusionPatterns.filter { pattern ->
                    val (key, value) = pattern.split("=", limit = 2)
                    when {
                        pokemonNbt.contains(key, net.minecraft.nbt.NbtElement.STRING_TYPE.toInt()) ->
                            pokemonNbt.getString(key).equals(value, ignoreCase = true)
                        pokemonNbt.contains(key, net.minecraft.nbt.NbtElement.INT_TYPE.toInt()) ->
                            pokemonNbt.getInt(key).toString() == value
                        else -> false
                    }
                }
                if (matchingNbtPatterns.isNotEmpty()) {
                    logDebug("[DEBUG] Preserving Pokemon due to NBT patterns: $matchingNbtPatterns", "lagcut")
                    return true
                }
            }
            if (ReflectionCache.methods["isBattling"]?.invoke(pokemon) as? Boolean == true) {
                logDebug("[DEBUG] Preserving battling Pokemon", "lagcut")
                return true
            }
            return false
        } catch (e: Exception) {
            logDebug("[DEBUG] Error checking Pokemon preservation status: ${e.message}", "lagcut")
            return false
        }
    }

    private fun getPokemonName(pokemonInstance: Any?): String {
        if (pokemonInstance == null) return "Unknown Pokemon"
        return nameCache.getOrPut(pokemonInstance) {
            try {
                val species = ReflectionCache.methods["getSpecies"]?.invoke(pokemonInstance)
                val speciesName = ReflectionCache.methods["getSpeciesName"]?.invoke(species)?.toString()
                if (!speciesName.isNullOrEmpty()) {
                    val regex = """cobblemon\.species\.(.+?)\.name""".toRegex()
                    val match = regex.find(speciesName)
                    if (match != null) {
                        return@getOrPut match.groupValues[1]
                    }
                    return@getOrPut speciesName
                }
                val displayName = ReflectionCache.methods["getDisplayName"]?.invoke(pokemonInstance)?.toString()
                if (!displayName.isNullOrEmpty()) {
                    return@getOrPut displayName
                }
                pokemonInstance.javaClass.simpleName
            } catch (e: Exception) {
                logDebug("[DEBUG] Error getting Pokemon name: ${e.message}", "lagcut")
                "Unknown Pokemon"
            }
        }
    }

    private fun isEntityInBlocklist(entityType: String, isPokemon: Boolean = false): Boolean {
        val normalizedType = entityType.lowercase().let { type ->
            if (isPokemon) {
                val regex = """cobblemon\.species\.(.+?)\.name""".toRegex()
                regex.find(type)?.groupValues?.get(1) ?: type
            } else {
                type.removePrefix("entity.minecraft.").removePrefix("entity.cobblemon.")
            }
        }
        return blocklist.any { exclusion ->
            when {
                exclusion.startsWith("cobblemon:") -> normalizedType == exclusion.removePrefix("cobblemon:").lowercase()
                exclusion.startsWith("pokemon:") -> normalizedType == exclusion.removePrefix("pokemon:").lowercase()
                exclusion.startsWith("minecraft:") -> normalizedType == exclusion.removePrefix("minecraft:").lowercase()
                else -> normalizedType == exclusion.lowercase()
            }
        }
    }
    fun forceClear(server: MinecraftServer) {
        scheduler.execute {
            clearEntitiesInChunks(server)
        }
    }
}
