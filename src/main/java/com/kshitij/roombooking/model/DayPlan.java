package com.kshitij.roombooking.model;

import java.util.Comparator;
import java.util.List;

/**
 * What one profile wants on one weekday: one or more time blocks (e.g. two
 * 90-minute sessions), in chronological order.
 *
 * <p>
 * LibCal lets one account book at most {@link #DAILY_MAX_MINUTES} per day, so
 * a plan over that — or with overlapping blocks — is rejected up front rather
 * than half-booked.
 */
public record DayPlan(List<TimeBlock> blocks) {

    public static final int DAILY_MAX_MINUTES = 180;

    public DayPlan {
        if (blocks == null || blocks.isEmpty()) {
            throw new IllegalArgumentException("a day plan needs at least one block");
        }
        blocks = blocks.stream().sorted(Comparator.comparing(b -> b.window().start())).toList();
        for (int i = 1; i < blocks.size(); i++) {
            if (blocks.get(i).window().start().isBefore(blocks.get(i - 1).window().end())) {
                throw new IllegalArgumentException("blocks " + blocks.get(i - 1).window() + " and "
                        + blocks.get(i).window() + " overlap");
            }
        }
        int total = blocks.stream().mapToInt(b -> b.window().durationMinutes()).sum();
        if (total > DAILY_MAX_MINUTES) {
            throw new IllegalArgumentException(total + " minutes scheduled, but the daily limit is "
                    + DAILY_MAX_MINUTES);
        }
    }

    public static DayPlan single(BookingWindow window, List<String> rooms) {
        return new DayPlan(List.of(new TimeBlock(window, rooms)));
    }

    public int totalMinutes() {
        return blocks.stream().mapToInt(b -> b.window().durationMinutes()).sum();
    }
}
