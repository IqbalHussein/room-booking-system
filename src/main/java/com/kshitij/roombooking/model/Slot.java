package com.kshitij.roombooking.model;

import java.time.LocalDateTime;

/**
 * One half-hour cell from LibCal's availability grid.
 *
 * <p>
 * LibCal returns every cell for the day, free or not. A booked cell carries a
 * {@code className} (observed value: {@code s-lc-eq-checkout}); a free cell has
 * no className at all. That field is the only availability marker in the
 * payload, so it is the single source of truth here.
 */
public record Slot(LocalDateTime start, LocalDateTime end, long itemId, String className) {

    public boolean isFree() {
        return className == null || className.isBlank();
    }
}
