package com.lagcut.api;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import java.util.Arrays;

public class TPSTracker {
    private static final int TPS_SAMPLE_INTERVAL = 100;  // Sample every 5 seconds (100 ticks)
    private static final int TARGET_TPS = 20;
    private static double currentTPS = 20.0;
    private static double tpsMultiplier = 1.0;
    private static final double[] tpsHistory = new double[TPS_SAMPLE_INTERVAL];
    private static int historyIndex = 0;
    private static long lastTickTime = System.nanoTime();
    private static long lastMeanTickTime = 0L;

    public static void collectServerTPS(MinecraftServer server) {
        if (server == null) return;

        long currentTime = System.nanoTime();
        long tickDuration = currentTime - lastTickTime;
        lastTickTime = currentTime;

        lastMeanTickTime = mean(server.getTickTimes());
        // Convert nanoseconds to milliseconds and calculate TPS
        double instantTPS = Math.min(1000.0 / (lastMeanTickTime / 1_000_000.0), TARGET_TPS);

        // Add to rolling average
        tpsHistory[historyIndex] = instantTPS;
        historyIndex = (historyIndex + 1) % TPS_SAMPLE_INTERVAL;

        // Calculate average TPS
        currentTPS = Arrays.stream(tpsHistory)
                .filter(tps -> tps > 0)
                .average()
                .orElse(TARGET_TPS);

        // Update throttle multiplier
        updateThrottleMultiplier();
    }

    private static void updateThrottleMultiplier() {
        if (currentTPS < TARGET_TPS) {
            double tpsLoss = TARGET_TPS - currentTPS;
            tpsMultiplier = 1.0 + (tpsLoss * 10);
        } else {
            tpsMultiplier = 1.0;
        }
    }

    public static long mean(long[] values) {
        if (values == null || values.length == 0) return 0L;

        long sum = 0L;
        int count = 0;

        for (long value : values) {
            if (value > 0) {
                sum += value;
                count++;
            }
        }

        return count > 0 ? sum / count : 0L;
    }


    // New getter methods for various metrics
    public static double getCurrentTPS() {
        return currentTPS;
    }

    public static double getThrottleMultiplier() {
        return tpsMultiplier;
    }

    public static double getMeanTickTime() {
        return lastMeanTickTime / 1_000_000.0; // Convert to milliseconds
    }

    public static double getTickTimeVariance(MinecraftServer server) {
        long[] tickTimes = server.getTickTimes();
        double mean = getMeanTickTime();
        double variance = 0.0;
        int count = 0;

        for (long tickTime : tickTimes) {
            if (tickTime > 0) {
                double ms = tickTime / 1_000_000.0;
                variance += Math.pow(ms - mean, 2);
                count++;
            }
        }

        return count > 0 ? Math.sqrt(variance / count) : 0.0;
    }

    public static int getTotalLoadedChunks(MinecraftServer server) {
        int totalChunks = 0;
        for (ServerWorld world : server.getWorlds()) {
            totalChunks += world.getChunkManager().getLoadedChunkCount();
        }
        return totalChunks;
    }

    public static long getUsedMemory() {
        Runtime runtime = Runtime.getRuntime();
        return (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024; // Convert to MB
    }

    public static long getMaxMemory() {
        return Runtime.getRuntime().maxMemory() / 1024 / 1024; // Convert to MB
    }

    public static String getMetricsString() {
        return String.format(
                "TPS: %.2f | MSPT: %.2f | Throttle: %.2f | Memory: %d/%d MB",
                currentTPS,
                getMeanTickTime(),
                tpsMultiplier,
                getUsedMemory(),
                getMaxMemory()
        );
    }
}