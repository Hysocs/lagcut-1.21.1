package com.lagcut

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.minecraft.entity.Entity
import net.minecraft.entity.mob.MobEntity
import net.minecraft.network.packet.s2c.play.ParticleS2CPacket
import net.minecraft.particle.ParticleEffect
import net.minecraft.particle.ParticleTypes
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import com.lagcut.utils.LagCutConfig
import kotlin.math.*

object AIModification {
    private val config = LagCutConfig.config.aiThrottling
    private val layerRanges = listOf(
        config.layer1End * config.layer1End,
        config.layer2End * config.layer2End,
        config.layer3End * config.layer3End
    )

    private var tickCounter = 0
    private var playerIndex = 0
    private var debug = false
    // Spatial grid constants and storage
    private val CHUNK_SIZE = 16.0
    private val entityChunkMap = mutableMapOf<Long, MutableSet<Entity>>()

    // Cache the chunk radius for each layer
    private val layerChunkRadii = layerRanges.map { range ->
        ceil(sqrt(range) / CHUNK_SIZE).toInt()
    }

    enum class Layer { LAYER1, LAYER2, LAYER3 }

    private data class ChunkCoord(val x: Int, val z: Int) {
        fun toKey(): Long = (x.toLong() shl 32) or (z.toLong() and 0xFFFFFFFF)

        companion object {
            fun fromEntity(entity: Entity): ChunkCoord {
                return ChunkCoord(
                    floor(entity.x / 16.0).toInt(),
                    floor(entity.z / 16.0).toInt()
                )
            }
        }
    }

    fun initialize() {
        ServerTickEvents.END_SERVER_TICK.register { server ->
            if (config.enabled && tickCounter++ % 20 == 0) {
                // Clear previous chunk mapping
                entityChunkMap.clear()

                server.worlds.forEach { world ->
                    val players = world.players.filterIsInstance<ServerPlayerEntity>()
                    if (players.isNotEmpty()) {
                        // Update spatial grid
                        updateSpatialGrid(world)

                        players.getOrNull(playerIndex)?.let { player ->
                            processEntitiesInRange(world, player)
                            if (debug) renderDebugParticles(player)
                        }
                        playerIndex = (playerIndex + 1) % players.size
                    }
                }
            }
        }
    }

    private fun updateSpatialGrid(world: ServerWorld) {
        world.iterateEntities()
            // Skip any player entities
            .filter { it !is ServerPlayerEntity }
            .filterIsInstance<MobEntity>()
            // Only throttle if it matches your "shouldThrottle" logic
            .filter { shouldThrottle(it) }
            .forEach { entity ->
                val chunk = ChunkCoord.fromEntity(entity)
                entityChunkMap
                    .getOrPut(chunk.toKey()) { mutableSetOf() }
                    .add(entity)
            }
    }


    private fun getRelevantChunks(player: ServerPlayerEntity): Set<ChunkCoord> {
        val playerChunk = ChunkCoord.fromEntity(player)
        val layer3Radius = layerChunkRadii[2] // Get chunk radius for layer 3

        val chunks = mutableSetOf<ChunkCoord>()

        // Calculate the square bounds for chunk checking
        for (dx in -layer3Radius..layer3Radius) {
            for (dz in -layer3Radius..layer3Radius) {
                // Calculate actual distance from player chunk center to this chunk center
                val chunkDistSq = (dx * dx + dz * dz).toDouble()
                // Only include if within layer 3 radius (in chunks) plus 1 for safety
                if (chunkDistSq <= (layer3Radius + 1) * (layer3Radius + 1)) {
                    chunks.add(ChunkCoord(playerChunk.x + dx, playerChunk.z + dz))
                }
            }
        }
        return chunks
    }

    private fun processEntitiesInRange(world: ServerWorld, player: ServerPlayerEntity) {
        val relevantChunks = getRelevantChunks(player)

        relevantChunks.forEach { chunk ->
            entityChunkMap[chunk.toKey()]?.forEach { entity ->
                val distSq = entity.squaredDistanceTo(player)
                val layer = when {
                    distSq <= layerRanges[0] -> Layer.LAYER1
                    distSq <= layerRanges[1] -> Layer.LAYER2
                    else -> Layer.LAYER3
                }
                // Apply AI modifications based on layer
            }
        }
    }

    @JvmStatic
    fun getEntityLayer(entity: Entity): Layer =
        (entity.world as? ServerWorld)?.players
            ?.filterIsInstance<ServerPlayerEntity>()
            ?.minOfOrNull { player ->
                val distSq = entity.squaredDistanceTo(player)
                when {
                    distSq <= layerRanges[0] -> Layer.LAYER1
                    distSq <= layerRanges[1] -> Layer.LAYER2
                    else -> Layer.LAYER3
                }
            } ?: Layer.LAYER3

    private fun renderDebugParticles(player: ServerPlayerEntity) {
        layerRanges.forEachIndexed { index, range ->
            renderSphere(
                player,
                sqrt(range),
                when (index) {
                    0 -> ParticleTypes.FLAME
                    1 -> ParticleTypes.END_ROD
                    else -> ParticleTypes.SMOKE
                }
            )
        }
    }

    private fun renderSphere(player: ServerPlayerEntity, radius: Double, particle: ParticleEffect) {
        if (!debug) return

        val particlePackets = mutableListOf<ParticleS2CPacket>()
        val verticalSteps = (0..5)
        val angleSteps = (0 until 80)

        verticalSteps.forEach { j ->
            val theta = j * Math.PI / 5
            val ringRadius = radius * sin(theta)
            val yOffset = radius * cos(theta)

            angleSteps.forEach { i ->
                val angle = i * 2 * Math.PI / 80
                particlePackets.add(ParticleS2CPacket(
                    particle,
                    true,
                    player.x + ringRadius * cos(angle),
                    player.y + 1.0 + yOffset,
                    player.z + ringRadius * sin(angle),
                    0f, 0f, 0f,
                    0.05f,
                    3
                ))
            }
        }

        // Batch send particles
        particlePackets.forEach { player.networkHandler.sendPacket(it) }
    }

    private fun shouldThrottle(entity: MobEntity): Boolean =
        (config.throttleCobblemonEntities && isPokemonEntity(entity)) ||
                (config.throttleMojangEntities && !isPokemonEntity(entity))

    @JvmStatic
    fun isPokemonEntity(entity: Entity): Boolean =
        entity::class.java.name == "com.cobblemon.mod.common.entity.pokemon.PokemonEntity"
}