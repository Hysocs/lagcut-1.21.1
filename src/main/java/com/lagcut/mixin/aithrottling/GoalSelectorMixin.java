package com.lagcut.mixin.aithrottling;

import com.lagcut.utils.LagCutConfig;
import com.lagcut.api.TPSTracker;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.ai.goal.GoalSelector;
import net.minecraft.entity.ai.goal.PrioritizedGoal;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Set;
import java.util.WeakHashMap;

@Mixin(GoalSelector.class)
public class GoalSelectorMixin {
    @Shadow @Final
    private Set<PrioritizedGoal> goals;

    @Unique
    private static final WeakHashMap<Goal, Long> LAST_GOAL_EXECUTION = new WeakHashMap<>();

    @Unique
    private boolean getConfigEnabled() {
        return LagCutConfig.INSTANCE.getConfig().getAiThrottling().getEnabled();
    }

    @Unique
    private boolean getConfigThrottleAIEnabled() {
        return LagCutConfig.INSTANCE.getConfig().getAiThrottling().getThrottleGeneralAI();
    }

    @Unique
    private int[] getBaseTickCycles() {
        var aiThrottling = LagCutConfig.INSTANCE.getConfig().getAiThrottling();
        return new int[] {
                aiThrottling.getLayer1TickCycle(),
                aiThrottling.getLayer2TickCycle(),
                aiThrottling.getLayer3TickCycle()
        };
    }

    @Unique
    private int[] getBaseTicksBlocked() {
        var aiThrottling = LagCutConfig.INSTANCE.getConfig().getAiThrottling();
        return new int[] {
                aiThrottling.getLayer1TicksBlocked(),
                aiThrottling.getLayer2TicksBlocked(),
                aiThrottling.getLayer3TicksBlocked()
        };
    }

    @Unique
    private static final int FORCED_TICK_FACTOR = 2;

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void throttleGoalTick(CallbackInfo ci) {
        if (!getConfigEnabled() || !getConfigThrottleAIEnabled() || goals == null) {
            return;
        }

        // Check if any active goals should be throttled
        for (PrioritizedGoal prioritizedGoal : goals) {
            if (prioritizedGoal != null && prioritizedGoal.isRunning()) {
                Goal goal = prioritizedGoal.getGoal();
                if (goal != null && shouldThrottleAI(goal)) {
                    ci.cancel();
                    return;
                }
            }
        }
    }

    @Unique
    private boolean shouldThrottleAI(Goal goal) {
        // Use layer 0 (most aggressive throttling) as default
        int baseTickCycle = getBaseTickCycles()[0];
        int baseTicksBlocked = getBaseTicksBlocked()[0];

        long currentTime = System.currentTimeMillis();
        long lastExecution = LAST_GOAL_EXECUTION.getOrDefault(goal, 0L);

        // Adjust blocking time based on TPS
        int adjustedTicksBlocked = (int)(baseTicksBlocked * TPSTracker.getThrottleMultiplier());
        adjustedTicksBlocked = Math.min(adjustedTicksBlocked, baseTickCycle);

        long timeDiff = currentTime - lastExecution;

        // Force AI execution if it hasn't run for too long
        if (timeDiff >= (baseTickCycle * FORCED_TICK_FACTOR)) {
            LAST_GOAL_EXECUTION.put(goal, currentTime);
            return false;
        }

        // Allow AI if we've passed the cycle time
        if (timeDiff >= baseTickCycle) {
            LAST_GOAL_EXECUTION.put(goal, currentTime);
            return false;
        }

        // Throttle if we're within the blocked period
        boolean throttle = timeDiff < adjustedTicksBlocked;
        if (!throttle) {
            LAST_GOAL_EXECUTION.put(goal, currentTime);
        }

        return throttle;
    }
}