package com.lagcut.utils

import com.blanketutils.command.CommandManager
import com.blanketutils.utils.logDebug
import com.lagcut.ClearLag
import com.lagcut.EntityStackManager
import com.lagcut.ItemStackingManager
import com.lagcut.api.TPSTracker
import com.mojang.brigadier.context.CommandContext
import net.minecraft.server.command.ServerCommandSource
import net.minecraft.text.Text
import net.minecraft.nbt.NbtCompound
import org.slf4j.LoggerFactory
import java.util.function.Supplier

object CommandRegistrar {
    private val logger = LoggerFactory.getLogger("CommandRegistrar")
    private val manager = CommandManager("lagcut")

    fun registerCommands() {
        manager.command("lagcut", aliases = listOf("lc")) {
            // Reload command
            subcommand("reload", permission = "lagcut.reload") {
                executes { context -> executeReloadCommand(context) }
            }

            subcommand("gui", permission = "lagcut.gui") {
                executes { context -> executeGuiCommand(context) }
            }

            // Ping command
            subcommand("ping", permission = "lagcut.ping") {
                executes { context -> executePingCommand(context) }
            }

            // TPS command
            subcommand("tps", permission = "lagcut.tps") {
                executes { context -> executeTPSCommand(context) }
            }

            // Clear command with subcommands
            subcommand("clear", permission = "lagcut.clear") {
                executes { context -> executeConfiguredClearCommand(context) }

                subcommand("mobs", permission = "lagcut.clear.mobs") {
                    executes { context -> executeClearCommand(context, "mobs") }
                }

                subcommand("cobblemonmobs", permission = "lagcut.clear.cobblemonmobs") {
                    executes { context -> executeClearCommand(context, "cobblemonmobs") }
                }

                subcommand("items", permission = "lagcut.clear.items") {
                    executes { context -> executeClearCommand(context, "items") }
                }
            }

            // Inspect nearest command
            subcommand("inspectnearest", permission = "lagcut.inspect") {
                executes { context -> executeInspectNearestCommand(context) }
            }
        }

        // Register all commands
        manager.register()
    }

    private fun executeConfiguredClearCommand(context: CommandContext<ServerCommandSource>): Int {
        val source = context.source
        ClearLag.forceClear(source.server)
        return 1
    }


    private fun executeReloadCommand(context: CommandContext<ServerCommandSource>): Int {
        val source = context.source
        try {
            val previousConfig = LagCutConfig.config.copy()
            LagCutConfig.reloadBlocking()

            if (LagCutConfig.config.version.isNotBlank()) {
                EntityStackManager.reinitialize()
                ClearLag.reinitialize()
                ItemStackingManager.reinitialize()

                CommandManager.sendSuccess(source, "§aLagCut configuration successfully reloaded!", true)
                logDebug("Configuration reloaded successfully.", "lagcut")
                return 1
            } else {
                CommandManager.sendError(source, "§cFailed to reload configuration: Invalid config state")
                logDebug("Failed to reload configuration: Invalid config state", "lagcut")
                return 0
            }
        } catch (e: Exception) {
            CommandManager.sendError(source, "§cFailed to reload configuration: ${e.message}")
            logDebug("Error reloading configuration: ${e.message}", "lagcut")
            e.printStackTrace()
            return 0
        }
    }

    private fun executeGuiCommand(context: CommandContext<ServerCommandSource>): Int {
        val source = context.source
        val player = source.player ?: run {
            CommandManager.sendError(source, "This command must be run by a player")
            return 0
        }

        // Open the GUI for the player
        //LagCutGuiManager.openGui(player)
        return 1
    }

    private fun executeInspectNearestCommand(context: CommandContext<ServerCommandSource>): Int {
        val source = context.source
        val player = source.player ?: run {
            CommandManager.sendError(source, "This command must be run by a player")
            return 0
        }

        val nearestEntity = player.world.getOtherEntities(
            player,
            player.boundingBox.expand(10.0),
            { true }
        ).minByOrNull { it.squaredDistanceTo(player) }

        if (nearestEntity == null) {
            CommandManager.sendError(source, "No entities found within 10 blocks")
            return 0
        }

        val nbtData = NbtCompound()
        nearestEntity.writeNbt(nbtData)

        logger.info("NBT data for entity ${nearestEntity.type.toString()} at ${nearestEntity.pos}:")
        logger.info(nbtData.toString())

        CommandManager.sendSuccess(
            source,
            "§aNBT data for nearest entity (${nearestEntity.type.toString()}) has been logged to console",
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
                CommandManager.sendError(source, "Invalid clear type: $type")
                return 0
            }
        }

        CommandManager.sendSuccess(
            source,
            "[LagCut] Cleared $result entities for type: $type",
            true
        )
        return 1
    }

    private fun executeTPSCommand(context: CommandContext<ServerCommandSource>): Int {
        val source = context.source
        val server = source.server

        val currentTPS = TPSTracker.getCurrentTPS()
        val throttleMultiplier = TPSTracker.getThrottleMultiplier()
        val meanTickTime = TPSTracker.getMeanTickTime()
        val tickTimeVariance = TPSTracker.getTickTimeVariance(server)
        val totalLoadedChunks = TPSTracker.getTotalLoadedChunks(server)
        val usedMemory = TPSTracker.getUsedMemory()
        val maxMemory = TPSTracker.getMaxMemory()

        val message = Text.literal("[LagCut] ")
            .append(
                CommandManager.formatColoredMessage(
                    String.format("TPS: %.2f", currentTPS),
                    if (currentTPS >= 19.5) 0x55FF55 else if (currentTPS >= 15.0) 0xFFFF55 else 0xFF5555
                )
            )
            .append(Text.literal(" | "))
            .append(
                CommandManager.formatColoredMessage(
                    String.format("MSPT: %.2f", meanTickTime),
                    if (meanTickTime <= 40.0) 0x55FF55 else if (meanTickTime <= 50.0) 0xFFFF55 else 0xFF5555
                )
            )
            .append(Text.literal(" | "))
            .append(
                CommandManager.formatColoredMessage(
                    String.format("Memory: %d/%d MB", usedMemory, maxMemory),
                    if (usedMemory < (maxMemory * 0.75)) 0x55FF55
                    else if (usedMemory < (maxMemory * 0.9)) 0xFFFF55
                    else 0xFF5555
                )
            )

        source.sendFeedback(Supplier { message }, false)

        logger.info(
            "TPS command executed. TPS: $currentTPS, MSPT: $meanTickTime, Throttle: $throttleMultiplier, " +
                    "Variance: $tickTimeVariance, Chunks: $totalLoadedChunks, Memory: $usedMemory/$maxMemory MB"
        )

        return 1
    }

    private fun executePingCommand(context: CommandContext<ServerCommandSource>): Int {
        val source = context.source
        val players = source.server.playerManager.playerList

        if (players.isEmpty()) {
            CommandManager.sendSuccess(source, "No players online.", false)
            return 0
        }

        val totalPing = players.sumOf { it.networkHandler.latency }
        val averagePing = (totalPing.toDouble() / players.size).toInt()

        val message = Text.literal("[LagCut] Average Player Ping: ")
            .append(CommandManager.formatColoredMessage("$averagePing ms", 0x55FF55))

        source.sendFeedback(Supplier { message }, false)
        logger.info("Ping command executed: Average Ping: ${averagePing}ms")

        return 1
    }
}