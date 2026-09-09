package com.kshitij.roombooking.service;

import com.kshitij.roombooking.model.BookingResult;
import com.kshitij.roombooking.model.BookingStage;
import com.kshitij.roombooking.model.BookingWindow;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BookingScheduleTest {

    @Test
    void morningsOnMondayAndWednesday() {
        for (DayOfWeek day : new DayOfWeek[] { DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY }) {
            BookingWindow window = BookingSchedule.windowFor(day).orElseThrow();
            assertEquals("8:30am-10:30am", window.toString(), day.toString());
            assertEquals(120, window.durationMinutes(), day.toString());
        }
    }

    @Test
    void afternoonsOnThursdayAndFriday() {
        for (DayOfWeek day : new DayOfWeek[] { DayOfWeek.THURSDAY, DayOfWeek.FRIDAY }) {
            BookingWindow window = BookingSchedule.windowFor(day).orElseThrow();
            assertEquals("2:30pm-5:30pm", window.toString(), day.toString());
            assertEquals(180, window.durationMinutes(), day.toString());
        }
    }

    @Test
    void nothingOnTuesdayOrTheWeekend() {
        for (DayOfWeek day : new DayOfWeek[] { DayOfWeek.TUESDAY, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY }) {
            assertTrue(BookingSchedule.windowFor(day).isEmpty(), day.toString());
        }
    }

    @Test
    void unavailableIsNotAFailure() {
        assertTrue(BookingResult.unavailable("taken", "detail").isBenign());
        assertTrue(BookingResult.skipped("not scheduled").isBenign());
        assertTrue(BookingResult.success("booked").isBenign());
        assertFalse(BookingResult.failed(BookingStage.GRID_MISMATCH, "broken", null).isBenign());
    }

    @Test
    void renderedOutputCarriesTheStatusToken() {
        assertTrue(BookingResult.unavailable("taken", "free: 9am-10am").render()
                .startsWith("UNAVAILABLE: taken | free: 9am-10am"));
        assertTrue(BookingResult.failed(BookingStage.END_TIME, "nope", null).render()
                .startsWith("FAILED: [END_TIME] nope"));
    }
}
