package com.kshitij.roombooking.service;

import com.kshitij.roombooking.model.BookingResult;
import com.kshitij.roombooking.model.BookingStage;
import com.kshitij.roombooking.model.BookingWindow;
import com.kshitij.roombooking.model.DayPlan;
import com.kshitij.roombooking.model.Profile;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BookingScheduleTest {

    @Test
    void defaultScheduleKeepsTheOriginal507Plan() throws Exception {
        Map<DayOfWeek, DayPlan> schedule = BookingSchedule.defaultSchedule();
        for (DayOfWeek day : new DayOfWeek[] { DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY }) {
            DayPlan plan = schedule.get(day);
            assertEquals("8:30am-10:30am", plan.window().toString(), day.toString());
            assertEquals(List.of("507"), plan.rooms());
        }
        for (DayOfWeek day : new DayOfWeek[] { DayOfWeek.THURSDAY, DayOfWeek.FRIDAY }) {
            assertEquals(180, schedule.get(day).window().durationMinutes(), day.toString());
        }
        for (DayOfWeek day : new DayOfWeek[] { DayOfWeek.TUESDAY, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY }) {
            assertFalse(schedule.containsKey(day), day.toString());
        }
    }

    @Test
    void parsesExportWithBackupRoomsAndDropsDisabledDays() throws Exception {
        String json = """
                {"profiles":[
                  {"id":"a1","name":"Iqbal","username":"u1","password":"p1","schedule":{
                     "MONDAY":{"enabled":true,"start":"09:00","end":"11:00","rooms":["507"," 324B ",""]},
                     "TUESDAY":{"enabled":false,"start":"09:00","end":"11:00","rooms":["507"]},
                     "FRIDAY":{"enabled":true,"start":"13:00","end":"14:00","rooms":[]}}},
                  {"id":"b2","name":"Sam","username":"u2","password":"p2","schedule":{}}
                ]}""";
        List<Profile> profiles = BookingSchedule.parseProfiles(json);
        assertEquals(2, profiles.size());

        Profile iqbal = profiles.get(0);
        DayPlan monday = iqbal.planFor(DayOfWeek.MONDAY).orElseThrow();
        assertEquals(List.of("507", "324B"), monday.rooms());
        assertEquals("9:00am-11:00am", monday.window().toString());
        assertTrue(iqbal.planFor(DayOfWeek.TUESDAY).isEmpty(), "disabled day");
        assertTrue(iqbal.planFor(DayOfWeek.FRIDAY).isEmpty(), "day with no rooms");
        assertTrue(iqbal.hasCredentials());
        assertFalse(iqbal.toString().contains("p1"), "password must not appear in toString");

        assertTrue(profiles.get(1).planFor(DayOfWeek.MONDAY).isEmpty());
    }

    @Test
    void rejectsMalformedExport() {
        BookingException e = assertThrows(BookingException.class,
                () -> BookingSchedule.parseProfiles("{\"oops\":true}"));
        assertEquals(BookingStage.CONFIG, e.stage());

        BookingException bad = assertThrows(BookingException.class, () -> BookingSchedule.parseProfiles(
                "{\"profiles\":[{\"id\":\"x\",\"schedule\":{\"MONDAY\":{\"start\":\"11:00\",\"end\":\"10:00\",\"rooms\":[\"507\"]}}}]}"));
        assertEquals(BookingStage.CONFIG, bad.stage());
    }

    @Test
    void overlappingWindowsAreDetected() {
        BookingWindow morning = BookingWindow.of("08:30", "10:30");
        assertTrue(BookingService.overlaps(morning, BookingWindow.of("10:00", "11:00")));
        assertFalse(BookingService.overlaps(morning, BookingWindow.of("10:30", "12:00")), "touching is fine");
    }

    @Test
    void exportUrlIsBuiltFromTheBase() {
        assertEquals("https://x.vercel.app/api/export", ScheduleSource.exportUrl("https://x.vercel.app/"));
        assertEquals("https://x.vercel.app/api/export", ScheduleSource.exportUrl(" https://x.vercel.app "));
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
