package com.kshitij.roombooking.service;

import com.kshitij.roombooking.model.BookingWindow;
import com.kshitij.roombooking.model.Slot;
import com.kshitij.roombooking.model.SlotPlan;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AvailabilityGridTest {

    private static final LocalDate DATE = LocalDate.of(2026, 9, 16);
    private static final long ROOM_507 = 32585L;
    private static final long OTHER_ROOM = 26867L;

    private static String slot(String start, String end, long itemId, String className) {
        return "{\"start\":\"2026-09-16 " + start + ":00\",\"end\":\"2026-09-16 " + end
                + ":00\",\"itemId\":" + itemId + ",\"checksum\":\"abc\""
                + (className == null ? "" : ",\"className\":\"" + className + "\"") + "}";
    }

    private static String grid(String... slots) {
        return "{\"slots\":[" + String.join(",", slots) + "]}";
    }

    @Test
    void parsesFreeAndBookedCells() throws Exception {
        List<Slot> slots = AvailabilityGrid.parse(grid(
                slot("08:30", "09:00", ROOM_507, null),
                slot("09:00", "09:30", ROOM_507, "s-lc-eq-checkout")));

        assertEquals(2, slots.size());
        assertTrue(slots.get(0).isFree());
        assertFalse(slots.get(1).isFree(), "a cell carrying a className is booked");
        assertEquals(ROOM_507, slots.get(0).itemId());
    }

    @Test
    void filtersToTheRequestedRoom() throws Exception {
        List<Slot> all = AvailabilityGrid.parse(grid(
                slot("08:30", "09:00", ROOM_507, null),
                slot("08:30", "09:00", OTHER_ROOM, null),
                slot("09:00", "09:30", OTHER_ROOM, null)));

        assertEquals(1, AvailabilityGrid.freeSlotsFor(all, ROOM_507).size(),
                "other rooms in the same response must be ignored");
    }

    @Test
    void plansAContiguousWindow() throws Exception {
        List<Slot> free = AvailabilityGrid.freeSlotsFor(AvailabilityGrid.parse(grid(
                slot("08:30", "09:00", ROOM_507, null),
                slot("09:00", "09:30", ROOM_507, null),
                slot("09:30", "10:00", ROOM_507, null),
                slot("10:00", "10:30", ROOM_507, null))), ROOM_507);

        SlotPlan plan = AvailabilityGrid.plan(free, DATE, BookingWindow.of("08:30", "10:30"));

        assertTrue(plan.bookable());
        assertEquals(0, plan.freeSlotIndex());
        assertEquals(DATE.atTime(10, 30), plan.end());
    }

    @Test
    void indexIsPositionAmongFreeCellsOnly() throws Exception {
        // 8:00 booked, so the free list starts at 8:30 -- the DOM renders only free cells.
        List<Slot> free = AvailabilityGrid.freeSlotsFor(AvailabilityGrid.parse(grid(
                slot("07:30", "08:00", ROOM_507, null),
                slot("08:00", "08:30", ROOM_507, "s-lc-eq-checkout"),
                slot("08:30", "09:00", ROOM_507, null),
                slot("09:00", "09:30", ROOM_507, null),
                slot("09:30", "10:00", ROOM_507, null),
                slot("10:00", "10:30", ROOM_507, null))), ROOM_507);

        SlotPlan plan = AvailabilityGrid.plan(free, DATE, BookingWindow.of("08:30", "10:30"));

        assertTrue(plan.bookable());
        assertEquals(1, plan.freeSlotIndex(), "7:30 is free and comes first, so 8:30 sits at index 1");
    }

    @Test
    void rejectsWhenTheStartIsBooked() throws Exception {
        List<Slot> free = AvailabilityGrid.freeSlotsFor(AvailabilityGrid.parse(grid(
                slot("08:30", "09:00", ROOM_507, "s-lc-eq-checkout"),
                slot("09:00", "09:30", ROOM_507, null))), ROOM_507);

        SlotPlan plan = AvailabilityGrid.plan(free, DATE, BookingWindow.of("08:30", "10:30"));

        assertFalse(plan.bookable());
        assertTrue(plan.reason().contains("8:30am is not free"), plan.reason());
    }

    @Test
    void rejectsWhenTheRunIsTooShort() throws Exception {
        List<Slot> free = AvailabilityGrid.freeSlotsFor(AvailabilityGrid.parse(grid(
                slot("08:30", "09:00", ROOM_507, null),
                slot("09:00", "09:30", ROOM_507, null),
                slot("09:30", "10:00", ROOM_507, "s-lc-eq-checkout"),
                slot("10:00", "10:30", ROOM_507, null))), ROOM_507);

        SlotPlan plan = AvailabilityGrid.plan(free, DATE, BookingWindow.of("08:30", "10:30"));

        assertFalse(plan.bookable(), "a gap mid-window must not be treated as bookable");
        assertTrue(plan.reason().contains("run ends at 9:30am"), plan.reason());
    }

    @Test
    void describesFreeBlocksAsRanges() throws Exception {
        List<Slot> free = AvailabilityGrid.freeSlotsFor(AvailabilityGrid.parse(grid(
                slot("07:30", "08:00", ROOM_507, null),
                slot("08:00", "08:30", ROOM_507, null),
                slot("08:30", "09:00", ROOM_507, "s-lc-eq-checkout"),
                slot("11:30", "12:00", ROOM_507, null),
                slot("12:00", "12:30", ROOM_507, null))), ROOM_507);

        assertEquals("7:30am-8:30am, 11:30am-12:30pm", AvailabilityGrid.describe(free));
    }

    /**
     * The real Sep 16 payload for room 507: 8:30-10:30 was genuinely booked.
     * Guards against ever again reporting a busy room as a scraper failure.
     */
    @Test
    void realPayloadForSep16ShowsMorningTaken() throws Exception {
        List<Slot> free = AvailabilityGrid.freeSlotsFor(AvailabilityGrid.parse(grid(
                slot("07:30", "08:00", ROOM_507, null),
                slot("08:00", "08:30", ROOM_507, null),
                slot("08:30", "09:00", ROOM_507, "s-lc-eq-checkout"),
                slot("09:00", "09:30", ROOM_507, "s-lc-eq-checkout"),
                slot("09:30", "10:00", ROOM_507, "s-lc-eq-checkout"),
                slot("10:00", "10:30", ROOM_507, "s-lc-eq-checkout"),
                slot("11:30", "12:00", ROOM_507, null))), ROOM_507);

        SlotPlan plan = AvailabilityGrid.plan(free, DATE, BookingWindow.of("08:30", "10:30"));

        assertFalse(plan.bookable());
        assertTrue(plan.reason().contains("7:30am-8:30am"), plan.reason());
    }

    @Test
    void emptyResponseFailsLoudly() {
        BookingException e = assertThrows(BookingException.class, () -> AvailabilityGrid.parse(""));
        assertTrue(e.getMessage().contains("empty"));
    }

    @Test
    void responseWithoutSlotsArrayFailsLoudly() {
        BookingException e = assertThrows(BookingException.class,
                () -> AvailabilityGrid.parse("{\"unexpected\":true}"));
        assertTrue(e.getMessage().contains("no 'slots' array"), e.getMessage());
    }
}
