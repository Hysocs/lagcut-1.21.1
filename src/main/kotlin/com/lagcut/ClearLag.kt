package com.lagcut


import com.lagcut.utils.LagCutConfig
import com.lagcut.utils.LagCutConfig.logDebug
import com.lagcut.utils.MiniMessageHelper
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.minecraft.entity.ItemEntity
import net.minecraft.entity.mob.MobEntity
import net.minecraft.entity.decoration.ArmorStandEntity
import net.minecraft.registry.Registries
import net.minecraft.server.MinecraftServer
import net.minecraft.server.world.ServerWorld
import net.minecraft.sound.SoundCategory
import net.minecraft.sound.SoundEvent
import net.minecraft.util.Identifier
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

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
    private var clearLagTickCounter = 0
    private var lastBroadcastSecond = -1
    private val blocklist = ConcurrentHashMap.newKeySet<String>()
    private val config get() = LagCutConfig.config.clearLag
    private val nameCache = ConcurrentHashMap<Any, String>(100)

    fun initialize() {
        if (!config.enabled) {
            logDebug("[RATL] ClearLag disabled")
            return
        }
        updateBlocklist()
        ServerTickEvents.END_SERVER_TICK.register { server ->
            if (config.enabled) handleClearLag(server)
        }
        initialized = true
        logDebug("[RATL] ClearLag initialized")
    }

    fun reinitialize() {
        updateBlocklist()
        if (!initialized) initialize()
        nameCache.clear()
        logDebug("[RATL] ClearLag reinitialized")
    }

    private data class PokemonMetadata(
        val aspects: List<String> = emptyList(),
        val nbtData: Map<String, Any> = emptyMap(),
        val specialFlags: Map<String, Boolean> = emptyMap()
    )

    private fun shouldExcludeEntity(entity: net.minecraft.entity.Entity): Boolean {
        val entityType = entity.type.toString()

        // First check if the entity type is excluded
        if (config.excludedEntityTypes.any { it.equals(entityType, ignoreCase = true) }) {
            logDebug("[DEBUG] Entity type $entityType is in excludedEntityTypes")
            return true
        }

        // Then check specific entity exclusions
        return isEntityInBlocklist(entityType)
    }

    private fun shouldPreservePokemon(pokemon: Any, pokemonInstance: Any, pokemonName: String): Boolean {
        if (isEntityInBlocklist(pokemonName, true)) {
            logDebug("[DEBUG] Pokemon $pokemonName is in blocklist")
            return true
        }

        try {
            // Check for matching labels using the hasLabels method
            ReflectionCache.hasLabelsMethod?.let { method ->
                val matchingLabels = config.excludedLabels.filter { label ->
                    method.invoke(pokemonInstance, arrayOf(label)) as? Boolean == true
                }
                if (matchingLabels.isNotEmpty()) {
                    logDebug("[DEBUG] Preserving Pokemon due to labels: $matchingLabels")
                    return true
                }
            }

            // Check special conditions from NBT data
            val nbt = net.minecraft.nbt.NbtCompound()
            (pokemon as? net.minecraft.entity.Entity)?.writeNbt(nbt)
            val pokemonNbt = nbt.getCompound("Pokemon")

            if (pokemonNbt != null) {
                // Check NBT patterns with exact matching
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
                    logDebug("[DEBUG] Preserving Pokemon due to NBT patterns: $matchingNbtPatterns")
                    return true
                }
            }

            // Check other special conditions
            if (ReflectionCache.methods["isBattling"]?.invoke(pokemon) as? Boolean == true) {
                logDebug("[DEBUG] Preserving battling Pokemon")
                return true
            }

            return false
        } catch (e: Exception) {
            logDebug("[DEBUG] Error checking Pokemon preservation status: ${e.message}")
            return false // Default to not preserving if we encounter an error
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
                logDebug("[DEBUG] Error getting Pokemon name: ${e.message}")
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
                type.removePrefix("entity.minecraft.")
                    .removePrefix("entity.cobblemon.")
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

    // Rest of the implementation remains the same...
    private fun clearPokemonEntities(world: ServerWorld, bypassChecks: Boolean = false): Int {
        if (ReflectionCache.pokemonEntityClass == null || ReflectionCache.methods["getPokemon"] == null) return 0

        val entities = world.iterateEntities()
            .filter { ReflectionCache.pokemonEntityClass.isInstance(it) }
            .filter { pokemon ->
                try {
                    // First check entity type exclusions
                    if (shouldExcludeEntity(pokemon as net.minecraft.entity.Entity)) {
                        return@filter false
                    }

                    if (bypassChecks) return@filter true

                    val pokemonInstance = ReflectionCache.methods["getPokemon"]!!.invoke(pokemon)
                    val pokemonName = getPokemonName(pokemonInstance)
                    !shouldPreservePokemon(pokemon, pokemonInstance, pokemonName)
                } catch (e: Exception) {
                    logDebug("[DEBUG] Error processing Pokemon: ${e.message}")
                    false
                }
            }
            .toList()

        entities.forEach { it.discard() }
        return entities.size
    }

    private fun clearMobEntities(world: ServerWorld): Int {
        return world.iterateEntities()
            .filter { it is MobEntity || it is ArmorStandEntity }
            .filter { entity ->
                when {
                    ReflectionCache.pokemonEntityClass?.isInstance(entity) == true -> false
                    config.preservePersistentEntities && entity is MobEntity && entity.isPersistent -> false
                    shouldExcludeEntity(entity) -> false
                    else -> true
                }
            }
            .onEach { it.discard() }
            .count()
    }

    private fun clearItemEntities(world: ServerWorld): Int {
        return world.iterateEntities()
            .filterIsInstance<ItemEntity>()
            .filter { !shouldExcludeEntity(it) }
            .onEach { it.discard() }
            .count()
    }

    private var lastBroadcastSoundSecond = -1

    private fun handleClearLag(server: MinecraftServer) {
        clearLagTickCounter++
        val ticksRemaining = config.cleanupIntervalTicks - clearLagTickCounter
        val secondsRemaining = (ticksRemaining / 20).coerceAtLeast(0)

        if (config.broadcastMessages.containsKey(secondsRemaining) &&
            secondsRemaining != lastBroadcastSecond &&
            secondsRemaining > 0
        ) {
            // DEBUG ADD: print out the raw message from config
            val rawMessage = config.broadcastMessages[secondsRemaining]!!
            logDebug("[DEBUG] handleClearLag: broadcast message for $secondsRemaining seconds is '$rawMessage'")
            broadcast(server, rawMessage)
            lastBroadcastSecond = secondsRemaining
        }

        // Play sound once per second
        if (config.broadcastsounds.containsKey(secondsRemaining) &&
            secondsRemaining != lastBroadcastSoundSecond &&
            secondsRemaining > 0
        ) {
            val soundSetting = config.broadcastsounds[secondsRemaining]!!

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
            // Mark that we have played the sound at this second
            lastBroadcastSoundSecond = secondsRemaining
        }

// The existing broadcast check remains unchanged, e.g.:
        if (config.broadcastMessages.containsKey(secondsRemaining) &&
            secondsRemaining != lastBroadcastSecond &&
            secondsRemaining > 0
        ) {
            val rawMessage = config.broadcastMessages[secondsRemaining]!!
            broadcast(server, rawMessage)
            lastBroadcastSecond = secondsRemaining
        }

        if (clearLagTickCounter >= config.cleanupIntervalTicks) {
            clearLagTickCounter = 0
            lastBroadcastSecond = -1
            clearEntities(server)
        }
    }

    fun clearEntities(server: MinecraftServer) {
        val stats = server.worlds
            .filter { world ->
                val dimensionId = world.registryKey.value.toString()
                !config.excludedDimensions.any { it.equals(dimensionId, ignoreCase = true) }
            }
            .fold(Triple(0, 0, 0)) { acc, world ->
                Triple(
                    acc.first + if (Lagcut.isCobblemonPresent && config.clearCobblemonEntities)
                        clearPokemonEntities(world) else 0,
                    acc.second + if (config.clearMojangEntities) clearMobEntities(world) else 0,
                    acc.third + if (config.clearItemEntities) clearItemEntities(world) else 0
                )
            }

        logDebug("[RATL] Cleared - Pokemon: ${stats.first}, Mobs: ${stats.second}, Items: ${stats.third}")
        broadcast(server, config.broadcastMessages[0]?.replace("<entityamount>",
            (stats.first + stats.second + stats.third).toString()) ?: "Entities cleared.")
    }

    private fun updateBlocklist() {
        blocklist.clear()
        blocklist.addAll(config.excludedEntities)
    }

    private fun broadcast(server: MinecraftServer, message: String) {
        val formatted = MiniMessageHelper.parse(message)
        server.playerManager.playerList.forEach { it.sendMessage(formatted, false) }
    }

    // Public utility methods
    fun clearAll(server: MinecraftServer) = server.worlds.sumOf { world ->
        (if (Lagcut.isCobblemonPresent) clearPokemonEntities(world, true) else 0) +
                clearMobEntities(world) + clearItemEntities(world)
    }

    fun clearMobs(server: MinecraftServer) = server.worlds.sumOf { clearMobEntities(it) }
    fun clearItems(server: MinecraftServer) = server.worlds.sumOf { clearItemEntities(it) }
    fun clearCobblemonMobs(server: MinecraftServer) =
        if (!Lagcut.isCobblemonPresent) 0
        else server.worlds.sumOf { clearPokemonEntities(it) }
}