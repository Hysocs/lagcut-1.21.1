package com.lagcut.utils

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.context.CommandContext
import com.lagcut.ClearLag
import com.lagcut.EntityStackManager
import com.lagcut.ItemStackingManager
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.minecraft.server.command.CommandManager.literal
import net.minecraft.server.command.ServerCommandSource
import net.minecraft.text.Text
import me.lucko.fabric.api.permissions.v0.Permissions
import org.slf4j.LoggerFactory
import java.util.function.Supplier
import com.lagcut.api.TPSTracker
import net.minecraft.item.Items
import net.minecraft.nbt.NbtCompound

object CommandRegistrar {

    private val logger = LoggerFactory.getLogger("CommandRegistrar")

    fun registerCommands() {
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            logger.info("Registering commands for LagCut.")
            registerLagCutCommand(dispatcher)
            registerAliasCommand(dispatcher)
        }
    }

    private fun registerLagCutCommand(dispatcher: CommandDispatcher<ServerCommandSource>) {
        dispatcher.register(
            literal("lagcut")
                .requires { source -> hasPermission(source, "lagcut.base", 2) }
                .then(
                    literal("reload")
                        .requires { source -> hasPermission(source, "lagcut.reload", 2) }
                        .executes { context -> executeReloadCommand(context) }
                )
                .then(
                    literal("ping")
                        .requires { source -> hasPermission(source, "lagcut.ping", 2) }
                        .executes { context -> executePingCommand(context) }
                )
                .then(
                    literal("tps")
                        .requires { source -> hasPermission(source, "lagcut.tps", 2) }
                        .executes { context -> executeTPSCommand(context) }
                )
                .then(
                    literal("clear")
                        .executes { context -> executeConfiguredClearCommand(context) }
                        .then(literal("mobs").executes { context -> executeClearCommand(context, "mobs") })
                        .then(literal("cobblemonmobs").executes { context -> executeClearCommand(context, "cobblemonmobs") })
                        .then(literal("items").executes { context -> executeClearCommand(context, "items") })
                )
                .then(
                    literal("inspectnearest")
                        .executes { context -> executeInspectNearestCommand(context) }
                )
        )
    }

    private fun registerAliasCommand(dispatcher: CommandDispatcher<ServerCommandSource>) {
        dispatcher.register(
            literal("lc")
                .requires { source -> hasPermission(source, "lagcut.base", 2) }
                .redirect(dispatcher.root.getChild("lagcut"))
        )
    }

    private fun executeConfiguredClearCommand(context: CommandContext<ServerCommandSource>): Int {
        val source = context.source
        ClearLag.clearEntities(source.server)
        return 1
    }

    private fun executeReloadCommand(context: CommandContext<ServerCommandSource>): Int {
        val source = context.source
        try {
            val previousConfig = LagCutConfig.config.copy()

            // Use the new blocking reload function
            LagCutConfig.reloadBlocking()

            // Verify the config was loaded properly
            if (LagCutConfig.config.version.isNotBlank()) {
                // Reinitialize entity stacking
                EntityStackManager.reinitialize()
                ClearLag.reinitialize()
                ItemStackingManager.reinitialize()

                source.sendFeedback({ Text.literal("§aLagCut configuration successfully reloaded!") }, true)
                LagCutConfig.logDebug("Configuration reloaded successfully.")
                return 1
            } else {
                // In case of invalid config, revert to previous state
                // Note: We'll need to add a way to set config in LagCutConfig if needed
                source.sendError(Text.literal("§cFailed to reload configuration: Invalid config state"))
                LagCutConfig.logDebug("Failed to reload configuration: Invalid config state")
                return 0
            }
        } catch (e: Exception) {
            source.sendError(Text.literal("§cFailed to reload configuration: ${e.message}"))
            LagCutConfig.logDebug("Error reloading configuration: ${e.message}")
            e.printStackTrace()
            return 0
        }
    }

    private fun executeInspectNearestCommand(context: CommandContext<ServerCommandSource>): Int {
        val source = context.source
        val player = source.player ?: run {
            source.sendError(Text.literal("This command must be run by a player"))
            return 0
        }

        // Get the nearest entity within 10 blocks
        val nearestEntity = player.world.getOtherEntities(
            player,
            player.boundingBox.expand(10.0),
            { true }
        ).minByOrNull { it.squaredDistanceTo(player) }

        if (nearestEntity == null) {
            source.sendError(Text.literal("No entities found within 10 blocks"))
            return 0
        }


        // Create NBT data
        val nbtData = NbtCompound()
        nearestEntity.writeNbt(nbtData)

        // Log to console
        logger.info("NBT data for entity ${nearestEntity.type.toString()} at ${nearestEntity.pos}:")
        logger.info(nbtData.toString())

        // Send feedback to player with Taterzen status
        source.sendFeedback(
            { Text.literal("§aNBT data for nearest entity (${nearestEntity.type.toString()}) has been logged to console") },
            false
        )

        return 1
    }
    private fun executeClearCommand(context: CommandContext<ServerCommandSource>, type: String): Int {
        val source = context.source
        val server = source.server
        val result = when (type) {
            "mobs" -> ClearLag.clearMobs(server)
            "cobblemonmobs" -> ClearLag.clearCobblemonMobs(server)
            "items" -> ClearLag.clearItems(server)
            else -> {
                source.sendError(Text.literal("Invalid clear type: $type"))
                return 0
            }
        }

        source.sendFeedback(
            { Text.literal("[LagCut] Cleared $result entities for type: $type") },
            true
        )
        return 1
    }

    private fun executeTPSCommand(context: CommandContext<ServerCommandSource>): Int {
        val source = context.source
        val server = source.server

        // Gather data from TPSTracker
        val currentTPS = TPSTracker.getCurrentTPS()
        val throttleMultiplier = TPSTracker.getThrottleMultiplier()
        val meanTickTime = TPSTracker.getMeanTickTime()
        val tickTimeVariance = TPSTracker.getTickTimeVariance(server)
        val totalLoadedChunks = TPSTracker.getTotalLoadedChunks(server)
        val usedMemory = TPSTracker.getUsedMemory()
        val maxMemory = TPSTracker.getMaxMemory()

        val message = Text.literal("[LagCut] ")
            .append(
                Text.literal(String.format("TPS: %.2f", currentTPS))
                    .styled { it.withColor(if (currentTPS >= 19.5) 0x55FF55 else if (currentTPS >= 15.0) 0xFFFF55 else 0xFF5555) }
            )
            .append(Text.literal(" | "))
            .append(
                Text.literal(String.format("MSPT: %.2f", meanTickTime))
                    .styled { it.withColor(if (meanTickTime <= 40.0) 0x55FF55 else if (meanTickTime <= 50.0) 0xFFFF55 else 0xFF5555) }
            )
            .append(Text.literal(" | "))
            .append(
                Text.literal(String.format("Throttle: %.2f", throttleMultiplier))
                    .styled { it.withColor(if (throttleMultiplier <= 1.1) 0x55FF55 else if (throttleMultiplier <= 1.5) 0xFFFF55 else 0xFF5555) }
            )
            .append(Text.literal(" | "))
            .append(
                Text.literal(String.format("Variance: %.2f", tickTimeVariance))
                    .styled { it.withColor(if (tickTimeVariance <= 5.0) 0x55FF55 else if (tickTimeVariance <= 10.0) 0xFFFF55 else 0xFF5555) }
            )
            .append(Text.literal(" | "))
            .append(
                Text.literal(String.format("Loaded Chunks: %d", totalLoadedChunks))
                    .styled { it.withColor(0x55FFFF) }
            )
            .append(Text.literal(" | "))
            .append(
                Text.literal(String.format("Memory: %d/%d MB", usedMemory, maxMemory))
                    .styled {
                        it.withColor(
                            if (usedMemory < (maxMemory * 0.75)) 0x55FF55
                            else if (usedMemory < (maxMemory * 0.9)) 0xFFFF55
                            else 0xFF5555
                        )
                    }
            )

        source.sendFeedback(Supplier { message }, false)

        logger.info(
            "TPS command executed. TPS: $currentTPS, MSPT: $meanTickTime, Throttle: $throttleMultiplier, " +
                    "Variance: $tickTimeVariance, Chunks: $totalLoadedChunks, UsedMemory: $usedMemory, MaxMemory: $maxMemory"
        )

        return 1
    }


    private fun executePingCommand(context: CommandContext<ServerCommandSource>): Int {
        val source = context.source
        val players = source.server.playerManager.playerList

        if (players.isEmpty()) {
            source.sendFeedback({ Text.literal("No players online.") }, false)
            return 0
        }

        var totalPing = 0
        players.forEach { player ->
            val playerPing = player.networkHandler.latency
            totalPing += playerPing
        }

        val averagePing = (totalPing.toDouble() / players.size).toInt()

        val message = Text.literal("[LagCut] Average Player Ping: ")
            .append(Text.literal("$averagePing ms").styled { it.withColor(0x55FF55) })

        source.sendFeedback(Supplier { message }, false)
        logger.info("Ping command executed: Average Ping: ${averagePing}ms")

        return 1
    }



    private fun hasPermission(source: ServerCommandSource, permission: String, level: Int): Boolean {
        val player = source.player
        return if (player != null) {
            try {
                Permissions.check(player, permission, level)
            } catch (e: NoClassDefFoundError) {
                player.hasPermissionLevel(level)
            }
        } else {
            source.hasPermissionLevel(level)
        }
    }
}
