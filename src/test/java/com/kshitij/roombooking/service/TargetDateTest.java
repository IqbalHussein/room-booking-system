package com.kshitij.roombooking.service;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Bookings land exactly 7 days after the library's (Ottawa) date, whatever the server clock says. */
class TargetDateTest {

    private static LocalDate targetAt(String instant) {
        return BookingService.targetDate(Clock.fixed(Instant.parse(instant), ZoneOffset.UTC));
    }

    @Test
    void scheduledRunBooksOneWeekAhead() {
        // Nightly cron: 05:01 UTC = 12:01am EST on Mon Nov 23 -> Mon Nov 30.
        assertEquals(LocalDate.of(2026, 11, 30), targetAt("2026-11-23T05:01:00Z"));
        // Summer: 05:01 UTC = 1:01am EDT, same calendar day in Ottawa.
        assertEquals(LocalDate.of(2026, 7, 13), targetAt("2026-07-06T05:01:00Z"));
    }

    @Test
    void eveningManualRunUsesOttawaDateNotUtc() {
        // 9pm EST Mon Nov 23 is already Tue Nov 24 in UTC; must still book Mon Nov 30.
        assertEquals(LocalDate.of(2026, 11, 30), targetAt("2026-11-24T02:00:00Z"));
    }

    @Test
    void crossesMonthAndYearBoundaries() {
        assertEquals(LocalDate.of(2027, 1, 3), targetAt("2026-12-27T05:01:00Z"));
        assertEquals(LocalDate.of(2026, 12, 3), targetAt("2026-11-26T05:01:00Z"));
    }
}
