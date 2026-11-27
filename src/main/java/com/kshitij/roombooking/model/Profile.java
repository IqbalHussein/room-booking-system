package com.kshitij.roombooking.model;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * One person's booking setup: their Carleton login and their weekly plan.
 *
 * <p>
 * {@link #bookedDates()} lists dates an earlier run already booked, so a rerun
 * (or a second trigger on the same night) doesn't book a second room.
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
                      Map<DayOfWeek, DayPlan> schedule, String problem, Set<LocalDate> bookedDates) {

    public Profile {
        schedule = Map.copyOf(schedule);
        bookedDates = bookedDates == null ? Set.of() : Set.copyOf(bookedDates);
    }

    public Profile(String id, String name, String username, String password, Map<DayOfWeek, DayPlan> schedule) {
        this(id, name, username, password, schedule, null, Set.of());
    }

    public Profile(String id, String name, String username, String password,
                   Map<DayOfWeek, DayPlan> schedule, String problem) {
        this(id, name, username, password, schedule, problem, Set.of());
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
