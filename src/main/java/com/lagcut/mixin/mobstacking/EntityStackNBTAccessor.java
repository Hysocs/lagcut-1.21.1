package com.lagcut.mixin.mobstacking;

import com.lagcut.api.StackDataProvider;
import net.minecraft.entity.Entity;
import net.minecraft.nbt.NbtCompound;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Entity.class)
public class EntityStackNBTAccessor implements StackDataProvider {

    //region Constants and Fields
    @Unique
    private static final String NBT_KEY = "EntityStackData";
    @Unique
    private static final String STACK_SIZE_KEY = "StackSize";
    @Unique
    private static final String IS_STACKED_KEY = "IsStacked";
    @Unique
    private static final int DEFAULT_STACK_SIZE = 1;
    @Unique
    private static final int MAX_SAFE_STACK_SIZE = 32767; // Maximum safe value for NBT integers

    @Unique
    private int stackSize = DEFAULT_STACK_SIZE;
    @Unique
    private boolean isStacked = false;
    //endregion

    //region NBT Handling
    @Inject(method = "writeNbt", at = @At("HEAD"))
    private void writeStackData(NbtCompound nbt, CallbackInfoReturnable<NbtCompound> cir) {
        try {
            if (stackSize != DEFAULT_STACK_SIZE || isStacked) {
                NbtCompound stackData = new NbtCompound();
                // Ensure stack size is within safe bounds
                stackData.putInt(STACK_SIZE_KEY, Math.min(stackSize, MAX_SAFE_STACK_SIZE));
                stackData.putBoolean(IS_STACKED_KEY, isStacked);
                nbt.put(NBT_KEY, stackData);
            }
        } catch (Exception e) {
            // Log error but don't crash
            System.err.println("Error writing stack data: " + e.getMessage());
            // Ensure we have valid fallback data
            resetToDefaultState();
        }
    }

    @Inject(method = "readNbt", at = @At("HEAD"))
    private void readStackData(NbtCompound nbt, CallbackInfo ci) {
        try {
            if (nbt != null && nbt.contains(NBT_KEY)) {
                NbtCompound stackData = nbt.getCompound(NBT_KEY);

                // Validate and read stack size
                if (stackData.contains(STACK_SIZE_KEY)) {
                    int savedStackSize = stackData.getInt(STACK_SIZE_KEY);
                    stackSize = validateStackSize(savedStackSize);
                }

                // Read stacked state with fallback
                isStacked = stackData.contains(IS_STACKED_KEY) &&
                        stackData.getBoolean(IS_STACKED_KEY);
            } else {
                resetToDefaultState();
            }
        } catch (Exception e) {
            System.err.println("Error reading stack data: " + e.getMessage());
            resetToDefaultState();
        }
    }
    //endregion

    //region Utility Methods
    @Unique
    private void resetToDefaultState() {
        stackSize = DEFAULT_STACK_SIZE;
        isStacked = false;
    }

    @Unique
    private int validateStackSize(int size) {
        if (size < DEFAULT_STACK_SIZE) {
            return DEFAULT_STACK_SIZE;
        }
        if (size > MAX_SAFE_STACK_SIZE) {
            return MAX_SAFE_STACK_SIZE;
        }
        return size;
    }
    //endregion

    //region Interface Implementation
    @Override
    public void reduceallthelag_1_21_1$setStackSize(int size) {
        this.stackSize = validateStackSize(size);
    }

    @Override
    public int reduceallthelag_1_21_1$getStackSize() {
        return this.stackSize;
    }

    @Override
    public void reduceallthelag_1_21_1$setStacked(boolean stacked) {
        this.isStacked = stacked;
    }

    @Override
    public boolean reduceallthelag_1_21_1$isStacked() {
        return this.isStacked;
    }
    //endregion
}