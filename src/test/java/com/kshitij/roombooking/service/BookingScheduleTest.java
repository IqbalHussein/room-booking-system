package com.kshitij.roombooking.service;

import com.kshitij.roombooking.model.BookingResult;
import com.kshitij.roombooking.model.BookingStage;
import com.kshitij.roombooking.model.BookingWindow;
import com.kshitij.roombooking.model.DayPlan;
import com.kshitij.roombooking.model.Profile;
import com.kshitij.roombooking.model.TimeBlock;
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
            assertEquals(1, plan.blocks().size());
            assertEquals("8:30am-10:30am", plan.blocks().get(0).window().toString(), day.toString());
            assertEquals(List.of("507"), plan.blocks().get(0).rooms());
        }
        for (DayOfWeek day : new DayOfWeek[] { DayOfWeek.THURSDAY, DayOfWeek.FRIDAY }) {
            assertEquals(180, schedule.get(day).totalMinutes(), day.toString());
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
        assertEquals(List.of("507", "324B"), monday.blocks().get(0).rooms());
        assertEquals("9:00am-11:00am", monday.blocks().get(0).window().toString());
        assertTrue(iqbal.planFor(DayOfWeek.TUESDAY).isEmpty(), "disabled day");
        assertTrue(iqbal.planFor(DayOfWeek.FRIDAY).isEmpty(), "day with no rooms");
        assertTrue(iqbal.hasCredentials());
        assertFalse(iqbal.toString().contains("p1"), "password must not appear in toString");

        assertTrue(profiles.get(1).planFor(DayOfWeek.MONDAY).isEmpty());
    }

    @Test
    void parsesSeveralBlocksPerDayInTimeOrder() throws Exception {
        String json = """
                {"profiles":[{"id":"a1","name":"Iqbal","username":"u","password":"p","schedule":{
                  "TUESDAY":{"enabled":true,"blocks":[
                    {"start":"14:00","end":"15:30","rooms":["507"]},
                    {"start":"09:00","end":"10:30","rooms":["324B","507"]},
                    {"start":"17:00","end":"18:00","rooms":[]}]}}}]}""";
        DayPlan tuesday = BookingSchedule.parseProfiles(json).get(0).planFor(DayOfWeek.TUESDAY).orElseThrow();
        assertEquals(2, tuesday.blocks().size(), "block without rooms is dropped");
        assertEquals("9:00am-10:30am", tuesday.blocks().get(0).window().toString());
        assertEquals(List.of("324B", "507"), tuesday.blocks().get(0).rooms());
        assertEquals(180, tuesday.totalMinutes());
    }

    @Test
    void overTheDailyLimitFailsOnlyThatProfile() throws Exception {
        String json = """
                {"profiles":[
                  {"id":"bad","name":"Greedy","username":"u","password":"p","schedule":{
                    "MONDAY":{"enabled":true,"blocks":[
                      {"start":"09:00","end":"11:00","rooms":["507"]},
                      {"start":"13:00","end":"14:30","rooms":["507"]}]}}},
                  {"id":"ok","name":"Fine","username":"u","password":"p","schedule":{
                    "MONDAY":{"enabled":true,"blocks":[{"start":"09:00","end":"12:00","rooms":["507"]}]}}}]}""";
        List<Profile> profiles = BookingSchedule.parseProfiles(json);
        assertTrue(profiles.get(0).problem().contains("daily limit"), profiles.get(0).problem());
        assertTrue(profiles.get(0).planFor(DayOfWeek.MONDAY).isEmpty());
        assertEquals(null, profiles.get(1).problem());
        assertEquals(180, profiles.get(1).planFor(DayOfWeek.MONDAY).orElseThrow().totalMinutes());
    }

    @Test
    void alreadyBookedDateIsSkippedWithoutTouchingTheBrowser() throws Exception {
        String json = """
                {"profiles":[{"id":"a1","name":"Iqbal","username":"u","password":"p",
                  "bookedDates":["2026-12-02","not-a-date"],
                  "schedule":{"WEDNESDAY":{"enabled":true,"blocks":[{"start":"11:30","end":"14:30","rooms":["324D"]}]}}}]}""";
        Profile p = BookingSchedule.parseProfiles(json).get(0);
        assertEquals(java.util.Set.of(java.time.LocalDate.of(2026, 12, 2)), p.bookedDates());
        BookingResult r = new BookingService(null).bookFor(p, java.time.LocalDate.of(2026, 12, 2), new java.util.HashMap<>());
        assertEquals(BookingResult.Status.SKIPPED, r.status());
        assertTrue(r.summary().contains("Already booked"));
    }

    @Test
    void overlappingBlocksAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new DayPlan(List.of(
                new TimeBlock(BookingWindow.of("09:00", "10:30"), List.of("507")),
                new TimeBlock(BookingWindow.of("10:00", "11:00"), List.of("507")))));
    }

    @Test
    void blockOutcomesCombineIntoOneProfileResult() {
        BookingResult partial = BookingService.combine(List.of(
                BookingResult.success("Booked 507"), BookingResult.unavailable("taken", "507: full")), 2);
        assertEquals(BookingResult.Status.SUCCESS, partial.status());
        assertEquals("Booked 1 of 2 blocks.", partial.summary());

        BookingResult none = BookingService.combine(List.of(
                BookingResult.unavailable("a", null), BookingResult.unavailable("b", null)), 2);
        assertEquals(BookingResult.Status.UNAVAILABLE, none.status());

        BookingResult broke = BookingService.combine(List.of(
                BookingResult.success("Booked 507"), BookingResult.failed(BookingStage.LOGIN, "bad login", null)), 2);
        assertEquals(BookingResult.Status.FAILED, broke.status());
        assertTrue(broke.summary().startsWith("Booked 1 of 2 blocks, then:"));
    }

    @Test
    void rejectsMalformedExport() throws Exception {
        BookingException e = assertThrows(BookingException.class,
                () -> BookingSchedule.parseProfiles("{\"oops\":true}"));
        assertEquals(BookingStage.CONFIG, e.stage());

        Profile bad = BookingSchedule.parseProfiles(
                "{\"profiles\":[{\"id\":\"x\",\"schedule\":{\"MONDAY\":{\"start\":\"11:00\",\"end\":\"10:00\",\"rooms\":[\"507\"]}}}]}").get(0);
        assertTrue(bad.problem() != null && bad.problem().contains("MONDAY"), String.valueOf(bad.problem()));
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
