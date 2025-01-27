package com.lagcut

import com.lagcut.utils.CommandRegistrar
import com.lagcut.utils.LagCutConfig

import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.minecraft.server.MinecraftServer
import org.slf4j.LoggerFactory
import java.util.concurrent.RejectedExecutionException

object Lagcut : ModInitializer {
	private val logger = LoggerFactory.getLogger("lagcut")
	private var server: MinecraftServer? = null
	var isCobblemonPresent = false
		private set

	override fun onInitialize() {
		LagCutConfig.initializeAndLoad()
		CommandRegistrar.registerCommands()
		EntityStackManager.initialize()
		ItemStackingManager.initialize()
		// Detect Cobblemon presence
		detectCobblemon()

		logger.info("Lagcut Mod Initialized!")

		ServerLifecycleEvents.SERVER_STARTING.register { serverInstance ->
			server = serverInstance
			safeInitializeTasks()
		}

		ServerLifecycleEvents.SERVER_STOPPING.register {
			server = null
		}
	}

	private fun detectCobblemon() {
		try {
			Class.forName("com.cobblemon.mod.common.entity.pokemon.PokemonEntity")
			isCobblemonPresent = true
			logger.info("Cobblemon detected! Enabling Cobblemon features.")
		} catch (e: ClassNotFoundException) {
			logger.warn("Cobblemon not detected. Cobblemon-specific features will be disabled.")
			isCobblemonPresent = false
		}
	}

	private fun safeInitializeTasks() {
		try {
			server?.let { server ->
				if (!server.isStopping && !server.isStopped) {
					server.execute {
						AIModification.initialize()
						ClearLag.initialize()

					}
				} else {
					logger.warn("Server is stopping or stopped. Skipping initialization tasks.")
				}
			}
		} catch (e: RejectedExecutionException) {
			logger.warn("Initialization task rejected. Server might be shutting down.")
		}
	}
}
