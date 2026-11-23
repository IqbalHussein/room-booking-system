package com.kshitij.roombooking.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoomNamesTest {

    @Test
    void matchesExactIdFollowedByDetails() {
        assertTrue(RoomNames.matches("507 (Capacity 6)", "507"));
        assertTrue(RoomNames.matches("507", "507"));
        assertTrue(RoomNames.matches("324b - Group Study", "324B"));
    }

    @Test
    void doesNotMatchAPrefixOfALongerId() {
        assertFalse(RoomNames.matches("507 (Capacity 6)", "50"));
        assertFalse(RoomNames.matches("324B (Capacity 4)", "324"));
        assertFalse(RoomNames.matches("Room 507", "507"));
        assertFalse(RoomNames.matches("507", ""));
    }
}
