package com.lagcut.api;

public interface StackDataProvider {

    void reduceallthelag_1_21_1$setStackSize(int size);
    int reduceallthelag_1_21_1$getStackSize();
    void reduceallthelag_1_21_1$setStacked(boolean stacked);
    boolean reduceallthelag_1_21_1$isStacked();

    // Kotlin-compatible method names
    default void setStackSizeCompat(int size) {
        reduceallthelag_1_21_1$setStackSize(size);
    }

    default int getStackSizeCompat() {
        return reduceallthelag_1_21_1$getStackSize();
    }

    default void setStackedCompat(boolean stacked) {
        reduceallthelag_1_21_1$setStacked(stacked);
    }

    default boolean isStackedCompat() {
        return reduceallthelag_1_21_1$isStacked();
    }
}
