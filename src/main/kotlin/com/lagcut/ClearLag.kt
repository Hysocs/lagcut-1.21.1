package com.lagcut


import com.lagcut.utils.LagCutConfig
import com.lagcut.utils.LagCutConfig.logDebug
import com.lagcut.utils.MiniMessageHelper
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.minecraft.entity.ItemEntity
import net.minecraft.entity.mob.MobEntity
import net.minecraft.entity.decoration.ArmorStandEntity
import net.minecraft.server.MinecraftServer
import net.minecraft.server.world.ServerWorld
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

    private fun extractPokemonMetadata(pokemon: Any, pokemonInstance: Any): PokemonMetadata {
        val aspects = mutableListOf<String>()
        val nbtData = mutableMapOf<String, Any>()
        val specialFlags = mutableMapOf<String, Boolean>()

        try {
            // Extract form aspects and check for non-normal forms
            ReflectionCache.methods["getForm"]?.invoke(pokemonInstance)?.let { form ->
                val formName = ReflectionCache.methods["getFormName"]?.invoke(form)?.toString()?.lowercase() ?: ""
                if (formName !in listOf("", "normal")) {
                    aspects.add("hasform")
                }

                ReflectionCache.methods["getAspects"]?.invoke(form)?.let { formAspects ->
                    if (formAspects is Collection<*>) {
                        aspects.addAll(formAspects.filterNotNull().map { it.toString().lowercase() })
                    }
                }
            }

            // Extract NBT data
            if (pokemon is net.minecraft.entity.Entity) {
                val nbt = net.minecraft.nbt.NbtCompound()
                pokemon.writeNbt(nbt)
                val pokemonNbt = nbt.getCompound("Pokemon")

                if (pokemonNbt != null) {
                    // Add common value-based aspects
                    val level = pokemonNbt.getInt("Level")
                    when {
                        level == 100 -> aspects.add("maxlevel")
                        level >= 90 -> aspects.add("highlevel")
                        level <= 5 -> aspects.add("lowlevel")
                    }

                    if (pokemonNbt.getInt("Friendship") >= 160) {
                        aspects.add("highfriendship")
                    }

                    // Check IVs
                    val ivs = pokemonNbt.getCompound("IVs")
                    if (ivs != null) {
                        var perfectIVs = 0
                        listOf("HP", "Attack", "Defence", "SpecialAttack", "SpecialDefence", "Speed").forEach { stat ->
                            if (ivs.getInt(stat) == 31) perfectIVs++
                        }
                        when {
                            perfectIVs == 6 -> aspects.add("6iv")
                            perfectIVs >= 5 -> aspects.add("5iv")
                            perfectIVs >= 4 -> aspects.add("4iv")
                        }
                    }

                    // Check for specific valuable traits
                    pokemonNbt.getString("TeraType").let {
                        if (it.isNotEmpty()) aspects.add("hastera")
                    }

                    pokemonNbt.getString("Ability").let { ability ->
                        if (ability.contains("Hidden", ignoreCase = true)) {
                            aspects.add("hiddenability")
                        }
                    }

                    // Store NBT data for advanced pattern matching
                    listOf("Species", "FormId", "Level", "TeraType", "Ability", "Nature", "Gender").forEach { key ->
                        when {
                            pokemonNbt.contains(key, net.minecraft.nbt.NbtElement.STRING_TYPE.toInt()) ->
                                nbtData[key.lowercase()] = pokemonNbt.getString(key)
                            pokemonNbt.contains(key, net.minecraft.nbt.NbtElement.INT_TYPE.toInt()) ->
                                nbtData[key.lowercase()] = pokemonNbt.getInt(key)
                        }
                    }
                }
            }

            // Set special flags with consistent naming
            specialFlags["shiny"] = ReflectionCache.methods["isShiny"]?.invoke(pokemonInstance) as? Boolean == true
            specialFlags["owned"] = ReflectionCache.methods["getOwner"]?.invoke(pokemonInstance) != null
            specialFlags["battling"] = ReflectionCache.methods["isBattling"]?.invoke(pokemon) as? Boolean == true

            // Add label-based flags with consistent naming
            ReflectionCache.hasLabelsMethod?.let { method ->
                listOf("legendary", "mythical", "ultrabeast").forEach { label ->
                    specialFlags[label] = method.invoke(pokemonInstance, arrayOf(label)) as? Boolean == true
                }
            }

        } catch (e: Exception) {
            logDebug("[DEBUG] Error extracting Pokemon metadata: ${e.message}")
        }

        return PokemonMetadata(aspects, nbtData, specialFlags)
    }


    private fun shouldPreservePokemon(pokemon: Any, pokemonInstance: Any, pokemonName: String): Boolean {
        if (isEntityInBlocklist(pokemonName, true)) {
            logDebug("[DEBUG] Pokemon $pokemonName is in blocklist")
            return true
        }

        val metadata = extractPokemonMetadata(pokemon, pokemonInstance)

        // Check aspects first
        val matchingAspects = config.excludedAspects.filter { aspect ->
            metadata.aspects.any { it == aspect.lowercase() }
        }
        if (matchingAspects.isNotEmpty()) {
            logDebug("[DEBUG] Preserving Pokemon due to aspects: $matchingAspects")
            return true
        }

        // Check NBT patterns with exact matching
        val matchingNbtPatterns = config.nbtExclusionPatterns.filter { pattern ->
            val (key, value) = pattern.split("=", limit = 2)
            metadata.nbtData[key.lowercase()]?.toString()?.lowercase() == value.lowercase()
        }
        if (matchingNbtPatterns.isNotEmpty()) {
            logDebug("[DEBUG] Preserving Pokemon due to NBT patterns: $matchingNbtPatterns")
            return true
        }

        // Check special flags with simplified naming
        if (metadata.specialFlags.any { (flag, value) ->
                value && config.excludedAspects.contains(flag)
            }) {
            logDebug("[DEBUG] Preserving Pokemon due to special flags")
            return true
        }

        return false
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
                    entity.type.toString() == "entity.taterzens.npc" && config.preserveTarterzens -> false
                    else -> !isEntityInBlocklist(entity.type.toString())
                }
            }
            .onEach { it.discard() }
            .count()
    }

    private fun clearItemEntities(world: ServerWorld): Int {
        return world.iterateEntities()
            .filterIsInstance<ItemEntity>()
            .filter { !isEntityInBlocklist(it.type.toString()) }
            .onEach { it.discard() }
            .count()
    }

    private fun handleClearLag(server: MinecraftServer) {
        clearLagTickCounter++
        val ticksRemaining = config.cleanupIntervalTicks - clearLagTickCounter
        val secondsRemaining = (ticksRemaining / 20).coerceAtLeast(0)

        if (config.broadcastMessages.containsKey(secondsRemaining) &&
            secondsRemaining != lastBroadcastSecond &&
            secondsRemaining > 0
        ) {
            broadcast(server, config.broadcastMessages[secondsRemaining]!!)
            lastBroadcastSecond = secondsRemaining
        }

        if (clearLagTickCounter >= config.cleanupIntervalTicks) {
            clearLagTickCounter = 0
            lastBroadcastSecond = -1
            clearEntities(server)
        }
    }

    fun clearEntities(server: MinecraftServer) {
        val stats = server.worlds.fold(Triple(0, 0, 0)) { acc, world ->
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