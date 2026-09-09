package com.kshitij.roombooking.model;

import java.time.LocalDateTime;

/**
 * The outcome of matching a wanted window against the grid.
 *
 * <p>
 * When bookable, {@link #freeSlotIndex()} is the position of the starting cell
 * <em>among the free cells only</em> — which is what the DOM renders, so it is
 * directly clickable. When not bookable, {@link #reason()} explains why in
 * terms a human can act on.
 */
public record SlotPlan(boolean bookable,
                       int freeSlotIndex,
                       LocalDateTime start,
                       LocalDateTime end,
                       String reason) {

    public static SlotPlan bookable(int freeSlotIndex, LocalDateTime start, LocalDateTime end) {
        return new SlotPlan(true, freeSlotIndex, start, end, null);
    }

    public static SlotPlan unavailable(String reason) {
        return new SlotPlan(false, -1, null, null, reason);
    }
}
