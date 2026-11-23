package com.kshitij.roombooking.model;

import java.util.List;

/** One booking within a day: a time window and the rooms to try, in priority order. */
public record TimeBlock(BookingWindow window, List<String> rooms) {

    public TimeBlock {
        if (rooms == null || rooms.isEmpty()) {
            throw new IllegalArgumentException("a block needs at least one room");
        }
        rooms = List.copyOf(rooms);
    }
}
