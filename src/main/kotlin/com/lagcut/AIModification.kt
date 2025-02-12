package com.lagcut

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.minecraft.entity.Entity
import net.minecraft.entity.mob.MobEntity
import net.minecraft.network.packet.s2c.play.ParticleS2CPacket
import net.minecraft.particle.ParticleEffect
import net.minecraft.particle.ParticleTypes
import net.minecraft.server.MinecraftServer
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import com.lagcut.utils.LagCutConfig
import kotlin.math.floor
import kotlin.math.abs
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object AIModification {
    // Config now only has an "enabled" checkbox.
    private val config = LagCutConfig.config.aiThrottling
    private var debug = true

    // Define the size of a chunk in blocks
    private const val CHUNK_SIZE = 16.0

    // Mapping of chunk keys to the set of entities within that chunk.
    // Each set is built on top of a WeakHashMap so that removed entities aren’t kept in memory.
    private val entityChunkMap = mutableMapOf<Long, MutableSet<Entity>>()

    // Data class to hold chunk coordinates
    data class ChunkCoord(val x: Int, val z: Int) {
        fun toKey(): Long = (x.toLong() shl 32) or (z.toLong() and 0xFFFFFFFF)

        companion object {
            // Create a chunk coordinate from an entity's position
            fun fromEntity(entity: Entity): ChunkCoord {
                return ChunkCoord(
                    floor(entity.x / CHUNK_SIZE).toInt(),
                    floor(entity.z / CHUNK_SIZE).toInt()
                )
            }

            // Create a chunk coordinate from a player's position
            fun fromPlayer(player: ServerPlayerEntity): ChunkCoord {
                return ChunkCoord(
                    floor(player.x / CHUNK_SIZE).toInt(),
                    floor(player.z / CHUNK_SIZE).toInt()
                )
            }
        }
    }

    // A scheduler that will trigger our periodic work.
    private val scheduler = Executors.newSingleThreadScheduledExecutor()

    /**
     * Call this method from your mod initialization.
     * It registers a SERVER_STARTED event so we can get the MinecraftServer instance
     * and schedule our repeating task.
     */
    fun initialize() {
        ServerLifecycleEvents.SERVER_STARTED.register { server: MinecraftServer ->
            // Schedule our task to run every second (roughly 20 ticks).
            scheduler.scheduleAtFixedRate({
                // We must run all world and entity code on the main server thread:
                server.execute {
                    if (config.enabled) {
                        processServer(server)
                    }
                }
            }, 0, 1, TimeUnit.SECONDS)
        }
    }

    /**
     * Process all worlds on the server.
     * For each world, we rebuild the spatial grid, process all relevant entities,
     * and (if debug is enabled) render debug particles.
     */
    private fun processServer(server: MinecraftServer) {
        // Clear the spatial grid for the current cycle.
        entityChunkMap.clear()

        server.worlds.forEach { world ->
            val players = world.players.filterIsInstance<ServerPlayerEntity>()
            if (players.isNotEmpty()) {
                updateSpatialGrid(world)
                processEntitiesInUnionChunks(world, players)
                if (debug) {
                    renderDebugParticlesForWorld(world, players)
                }
            }
        }
    }

    /**
     * Update the spatial grid for the world.
     * Each mob entity is placed into its chunk’s set.
     */
    private fun updateSpatialGrid(world: ServerWorld) {
        world.iterateEntities()
            .filter { it !is ServerPlayerEntity }
            .filterIsInstance<MobEntity>()
            .forEach { entity ->
                val chunk = ChunkCoord.fromEntity(entity)
                // Use a weak–reference set so that entities that are removed don’t stay in memory.
                val set = entityChunkMap.getOrPut(chunk.toKey()) {
                    Collections.newSetFromMap(WeakHashMap())
                }
                set.add(entity)
            }
    }

    /**
     * Returns the set of chunk coordinates in a 3×3 grid centered around the player's current chunk.
     */
    private fun get3x3Chunks(player: ServerPlayerEntity): Set<ChunkCoord> {
        val center = ChunkCoord.fromPlayer(player)
        val chunks = mutableSetOf<ChunkCoord>()
        for (dx in -1..1) {
            for (dz in -1..1) {
                chunks.add(ChunkCoord(center.x + dx, center.z + dz))
            }
        }
        return chunks
    }

    /**
     * Process all entities that are in any of the chunks in the union of
     * all players’ 3×3 grids. This avoids processing the same chunk multiple times
     * when players overlap.
     */
    private fun processEntitiesInUnionChunks(world: ServerWorld, players: List<ServerPlayerEntity>) {
        val unionChunks = mutableSetOf<ChunkCoord>()
        players.forEach { player ->
            unionChunks.addAll(get3x3Chunks(player))
        }
        unionChunks.forEach { chunk ->
            entityChunkMap[chunk.toKey()]?.forEach { entity ->
                // Place your AI activation/throttling logic here.
                // For example: entity.activateAI() or similar.
            }
        }
    }

    /**
     * Render debug particles outlining the border of each active chunk.
     * This uses the union of all players’ 3×3 grids so that overlapping chunks aren’t rendered multiple times.
     */
    private fun renderDebugParticlesForWorld(world: ServerWorld, players: List<ServerPlayerEntity>) {
        if (players.isEmpty()) return

        // Calculate the union of all 3x3 chunk grids from every player.
        val unionChunks = mutableSetOf<ChunkCoord>()
        players.forEach { player ->
            unionChunks.addAll(get3x3Chunks(player))
        }

        // Define the particle effect.
        val particle: ParticleEffect = ParticleTypes.FLAME

        // Number of particles along each edge of the chunk.
        val ringSteps = 20
        // Fixed Y coordinate for debugging.
        val y = 70.0

        unionChunks.forEach { chunk ->
            // World coordinate of the chunk's origin (lower corner)
            val chunkOriginX = chunk.x * CHUNK_SIZE
            val chunkOriginZ = chunk.z * CHUNK_SIZE

            // Top edge: from left to right.
            for (i in 0 until ringSteps) {
                val t = i.toDouble() / (ringSteps - 1)
                val x = chunkOriginX + t * CHUNK_SIZE
                val z = chunkOriginZ
                players.forEach { player ->
                    player.networkHandler.sendPacket(
                        ParticleS2CPacket(
                            particle,
                            true,
                            x, y, z,
                            0f, 0f, 0f,
                            0.05f,
                            1
                        )
                    )
                }
            }

            // Bottom edge: from left to right.
            for (i in 0 until ringSteps) {
                val t = i.toDouble() / (ringSteps - 1)
                val x = chunkOriginX + t * CHUNK_SIZE
                val z = chunkOriginZ + CHUNK_SIZE
                players.forEach { player ->
                    player.networkHandler.sendPacket(
                        ParticleS2CPacket(
                            particle,
                            true,
                            x, y, z,
                            0f, 0f, 0f,
                            0.05f,
                            1
                        )
                    )
                }
            }

            // Left edge: from top to bottom (excluding corners to avoid duplicates).
            for (i in 1 until ringSteps - 1) {
                val t = i.toDouble() / (ringSteps - 1)
                val x = chunkOriginX
                val z = chunkOriginZ + t * CHUNK_SIZE
                players.forEach { player ->
                    player.networkHandler.sendPacket(
                        ParticleS2CPacket(
                            particle,
                            true,
                            x, y, z,
                            0f, 0f, 0f,
                            0.05f,
                            1
                        )
                    )
                }
            }

            // Right edge: from top to bottom (excluding corners to avoid duplicates).
            for (i in 1 until ringSteps - 1) {
                val t = i.toDouble() / (ringSteps - 1)
                val x = chunkOriginX + CHUNK_SIZE
                val z = chunkOriginZ + t * CHUNK_SIZE
                players.forEach { player ->
                    player.networkHandler.sendPacket(
                        ParticleS2CPacket(
                            particle,
                            true,
                            x, y, z,
                            0f, 0f, 0f,
                            0.05f,
                            1
                        )
                    )
                }
            }
        }
    }

    /**
     * Utility: Determine whether an entity is in an “active” chunk.
     * An active chunk is defined as any chunk within a 3×3 grid around at least one player.
     */
    @JvmStatic
    fun isEntityInActiveChunk(entity: Entity): Boolean {
        val world = entity.world
        if (world !is ServerWorld) return false
        val entityChunk = ChunkCoord.fromEntity(entity)
        for (player in world.players.filterIsInstance<ServerPlayerEntity>()) {
            val playerChunk = ChunkCoord.fromPlayer(player)
            if (abs(entityChunk.x - playerChunk.x) <= 1 && abs(entityChunk.z - playerChunk.z) <= 1) {
                return true
            }
        }
        return false
    }
}
