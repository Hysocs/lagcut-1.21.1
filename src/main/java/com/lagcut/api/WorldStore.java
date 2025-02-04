// WorldStore.java
package com.lagcut.api;

import net.minecraft.server.world.ServerWorld;

public class WorldStore {
    private static ServerWorld currentWorld;

    public static ServerWorld getCurrentWorld() {
        return currentWorld;
    }

    public static void setCurrentWorld(ServerWorld world) {
        currentWorld = world;
    }
}
