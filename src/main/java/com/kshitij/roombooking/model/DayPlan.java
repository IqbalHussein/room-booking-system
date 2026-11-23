package com.kshitij.roombooking.model;

import java.util.List;

/**
 * What one profile wants on one weekday: a time window and the rooms to try,
 * in priority order. The first room that can take the whole window is booked.
 */
public record DayPlan(BookingWindow window, List<String> rooms) {

    public DayPlan {
        if (rooms == null || rooms.isEmpty()) {
            throw new IllegalArgumentException("a day plan needs at least one room");
        }
        rooms = List.copyOf(rooms);
    }
}
