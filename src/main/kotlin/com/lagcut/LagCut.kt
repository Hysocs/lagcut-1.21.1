package com.lagcut

import com.lagcut.utils.CommandRegistrar
import com.lagcut.utils.LagCutConfig
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.server.MinecraftServer
import org.slf4j.LoggerFactory
import java.util.concurrent.RejectedExecutionException
import kotlin.system.measureTimeMillis

object Lagcut : ModInitializer {
	private val logger = LoggerFactory.getLogger("lagcut")
	private var server: MinecraftServer? = null
	var isCobblemonPresent = false
		private set

	override fun onInitialize() {
		try {
			if (!waitForBlanketUtils()) {
				throw RuntimeException("BlanketUtils is required but not loaded after waiting!")
			}

			// Safe to initialize our mod now
			initializeMod()

		} catch (e: Exception) {
			logger.error("Failed to initialize Lagcut", e)
			throw e // Propagate the error to prevent partial initialization
		}
	}

	private fun waitForBlanketUtils(): Boolean {
		val fabricLoader = FabricLoader.getInstance()
		val maxWaitTime = 10000L // 10 seconds in milliseconds
		val startTime = System.currentTimeMillis()

		while (System.currentTimeMillis() - startTime < maxWaitTime) {
			if (fabricLoader.isModLoaded("blanketutils")) {
				logger.info("BlanketUtils found! Continuing initialization...")
				return true
			}

			// Log every second
			if ((System.currentTimeMillis() - startTime) % 1000 < 100) {
				logger.info("Waiting for BlanketUtils to load... (${(System.currentTimeMillis() - startTime) / 1000}s)")
			}

			Thread.sleep(100) // Check every 100ms
		}

		logger.error("Timeout waiting for BlanketUtils!")
		return false
	}

	private fun initializeMod() {
		logger.info("Starting Lagcut initialization...")

		LagCutConfig.initializeAndLoad()
		CommandRegistrar.registerCommands()
		EntityStackManager.initialize()
		ItemStackingManager.initialize()
		detectCobblemon()

		logger.info("Lagcut Mod Initialized!")

		ServerLifecycleEvents.SERVER_STARTING.register { serverInstance ->
			server = serverInstance
			safeInitializeTasks()
		}

		ServerLifecycleEvents.SERVER_STOPPING.register {
			server = null
			// Shutdown open executors from our mod
			ClearLag.shutdown()
			EntityStackManager.shutdown()
			ItemStackingManager.shutdown()
			AIModification.shutdown()
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
					server.executeSync {
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