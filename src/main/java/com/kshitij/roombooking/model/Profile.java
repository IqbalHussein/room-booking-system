package com.kshitij.roombooking.model;

import java.time.DayOfWeek;
import java.util.Map;
import java.util.Optional;

/**
 * One person's booking setup: their Carleton login and their weekly plan.
 *
 * <p>
 * {@link #problem()} is set when this profile's schedule couldn't be read; it
 * then fails on its own without affecting anyone else's bookings.
 *
 * <p>
 * {@link #toString()} is overridden so the password can never leak into a log
 * line by accident.
 */
public record Profile(String id, String name, String username, String password,
                      Map<DayOfWeek, DayPlan> schedule, String problem) {

    public Profile {
        schedule = Map.copyOf(schedule);
    }

    public Profile(String id, String name, String username, String password, Map<DayOfWeek, DayPlan> schedule) {
        this(id, name, username, password, schedule, null);
    }

    public Optional<DayPlan> planFor(DayOfWeek day) {
        return Optional.ofNullable(schedule.get(day));
    }

    public boolean hasCredentials() {
        return username != null && !username.isBlank() && password != null && !password.isBlank();
    }

    @Override
    public String toString() {
        return "Profile[" + name + " (" + id + "), " + schedule.size() + " days]";
    }
}
