package com.kshitij.roombooking.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kshitij.roombooking.model.BookingStage;
import com.kshitij.roombooking.model.BookingWindow;
import com.kshitij.roombooking.model.Slot;
import com.kshitij.roombooking.model.SlotPlan;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Reads LibCal's availability JSON and decides which cell to click.
 *
 * <p>
 * Deliberately free of Selenium so the matching rules can be unit-tested
 * against fixed payloads. The browser layer only supplies the JSON string and
 * then clicks the index this class returns.
 */
public final class AvailabilityGrid {

    /** LibCal serialises grid timestamps as {@code yyyy-MM-dd HH:mm:ss}. */
    public static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AvailabilityGrid() {
    }

    /** Parses the {@code slots} array. Throws with context rather than an opaque NPE. */
    public static List<Slot> parse(String json) throws BookingException {
        if (json == null || json.isBlank()) {
            throw new BookingException(BookingStage.GRID_PARSE, "Availability response was empty.");
        }
        try {
            JsonNode slots = MAPPER.readTree(json).path("slots");
            if (!slots.isArray()) {
                throw new BookingException(BookingStage.GRID_PARSE,
                        "Availability response had no 'slots' array — LibCal's API shape may have changed.",
                        "response started with: " + json.substring(0, Math.min(300, json.length())));
            }
            List<Slot> parsed = new ArrayList<>();
            for (JsonNode node : slots) {
                JsonNode className = node.get("className");
                parsed.add(new Slot(
                        LocalDateTime.parse(node.path("start").asText(), TIMESTAMP),
                        LocalDateTime.parse(node.path("end").asText(), TIMESTAMP),
                        node.path("itemId").asLong(),
                        className == null || className.isNull() ? null : className.asText()));
            }
            return parsed;
        } catch (BookingException e) {
            throw e;
        } catch (Exception e) {
            throw new BookingException(BookingStage.GRID_PARSE,
                    "Could not read the availability response: " + e.getMessage(),
                    "response started with: " + json.substring(0, Math.min(300, json.length())), e);
        }
    }

    /**
     * The free cells for one room, chronologically. This ordering is what the
     * page renders as clickable cells, so positions here map to DOM positions.
     */
    public static List<Slot> freeSlotsFor(List<Slot> all, long itemId) {
        return all.stream()
                .filter(s -> s.itemId() == itemId)
                .filter(Slot::isFree)
                .sorted(Comparator.comparing(Slot::start))
                .toList();
    }

    /** Every cell for one room, free or not — used only for diagnostics. */
    public static List<Slot> allSlotsFor(List<Slot> all, long itemId) {
        return all.stream()
                .filter(s -> s.itemId() == itemId)
                .sorted(Comparator.comparing(Slot::start))
                .toList();
    }

    /**
     * Decides whether {@code window} on {@code date} can be booked as an
     * unbroken run of free cells, and if so where that run starts.
     */
    public static SlotPlan plan(List<Slot> freeSlots, LocalDate date, BookingWindow window) {
        LocalDateTime wantStart = date.atTime(window.start());
        LocalDateTime wantEnd = date.atTime(window.end());

        int startIndex = -1;
        for (int i = 0; i < freeSlots.size(); i++) {
            if (freeSlots.get(i).start().equals(wantStart)) {
                startIndex = i;
                break;
            }
        }
        if (startIndex < 0) {
            return SlotPlan.unavailable(
                    BookingWindow.format(window.start()) + " is not free. Free blocks: " + describe(freeSlots));
        }

        // walk forward while cells remain contiguous
        LocalDateTime reached = freeSlots.get(startIndex).end();
        int i = startIndex + 1;
        while (reached.isBefore(wantEnd) && i < freeSlots.size()
                && freeSlots.get(i).start().equals(reached)) {
            reached = freeSlots.get(i).end();
            i++;
        }

        if (reached.isBefore(wantEnd)) {
            return SlotPlan.unavailable(
                    BookingWindow.format(window.start()) + " is free but the run ends at "
                            + BookingWindow.format(reached.toLocalTime()) + ", short of "
                            + BookingWindow.format(window.end()) + ". Free blocks: " + describe(freeSlots));
        }

        return SlotPlan.bookable(startIndex, wantStart, wantEnd);
    }

    /**
     * Collapses free cells into human-readable runs, e.g.
     * {@code 7:30am-8:30am, 11:30am-1:00pm}. This is the line that makes a
     * failed run self-explanatory.
     */
    public static String describe(List<Slot> freeSlots) {
        if (freeSlots.isEmpty()) {
            return "(none)";
        }
        List<String> blocks = new ArrayList<>();
        LocalDateTime blockStart = freeSlots.get(0).start();
        LocalDateTime blockEnd = freeSlots.get(0).end();
        for (Slot slot : freeSlots.subList(1, freeSlots.size())) {
            if (slot.start().equals(blockEnd)) {
                blockEnd = slot.end();
            } else {
                blocks.add(render(blockStart, blockEnd));
                blockStart = slot.start();
                blockEnd = slot.end();
            }
        }
        blocks.add(render(blockStart, blockEnd));
        return String.join(", ", blocks);
    }

    private static String render(LocalDateTime start, LocalDateTime end) {
        return BookingWindow.format(start.toLocalTime()) + "-" + BookingWindow.format(end.toLocalTime());
    }
}
