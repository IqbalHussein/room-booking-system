package com.kshitij.roombooking.service;

import com.kshitij.roombooking.model.BookingWindow;

import java.time.DayOfWeek;
import java.util.Map;
import java.util.Optional;

/** The weekly booking schedule: Mon/Wed 8:30-10:30am, Thu/Fri 2:30-5:30pm. */
public final class BookingSchedule {

    private static final Map<DayOfWeek, BookingWindow> SCHEDULE = Map.of(
            DayOfWeek.MONDAY, BookingWindow.of("08:30", "10:30"),
            DayOfWeek.WEDNESDAY, BookingWindow.of("08:30", "10:30"),
            DayOfWeek.THURSDAY, BookingWindow.of("14:30", "17:30"),
            DayOfWeek.FRIDAY, BookingWindow.of("14:30", "17:30"));

    private BookingSchedule() {
    }

    public static Optional<BookingWindow> windowFor(DayOfWeek day) {
        return Optional.ofNullable(SCHEDULE.get(day));
    }
}
