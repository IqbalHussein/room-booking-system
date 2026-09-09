package com.kshitij.roombooking.model;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** A start/end time-of-day pair, e.g. 8:30am to 10:30am. */
public record BookingWindow(LocalTime start, LocalTime end) {

    private static final DateTimeFormatter DISPLAY = DateTimeFormatter.ofPattern("h:mma", Locale.ENGLISH);

    public BookingWindow {
        if (!end.isAfter(start)) {
            throw new IllegalArgumentException("end (" + end + ") must be after start (" + start + ")");
        }
    }

    public static BookingWindow of(String start, String end) {
        return new BookingWindow(LocalTime.parse(start), LocalTime.parse(end));
    }

    public int durationMinutes() {
        return (int) java.time.Duration.between(start, end).toMinutes();
    }

    public static String format(LocalTime time) {
        return DISPLAY.format(time).toLowerCase(Locale.ENGLISH);
    }

    @Override
    public String toString() {
        return format(start) + "-" + format(end);
    }
}
