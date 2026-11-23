package com.kshitij.roombooking.service;

import java.util.Locale;

/** Matching typed room ids (e.g. "324B") against LibCal's link text (e.g. "324B (Capacity 6)"). */
public final class RoomNames {

    private RoomNames() {
    }

    /** Normalised form used to compare rooms across profiles: trimmed, upper-case. */
    public static String key(String room) {
        return room == null ? "" : room.trim().toUpperCase(Locale.ROOT);
    }

    /**
     * True when the link text starts with the room id and the id isn't merely a
     * prefix of a longer one — "50" must not match "507", nor "324" match "324B".
     */
    public static boolean matches(String linkText, String room) {
        String text = key(linkText);
        String id = key(room);
        if (id.isEmpty() || !text.startsWith(id)) {
            return false;
        }
        return text.length() == id.length() || !Character.isLetterOrDigit(text.charAt(id.length()));
    }
}
