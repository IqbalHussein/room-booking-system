package com.kshitij.roombooking.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kshitij.roombooking.model.BookingStage;
import com.kshitij.roombooking.model.BookingWindow;
import com.kshitij.roombooking.model.DayPlan;
import com.kshitij.roombooking.model.Profile;
import com.kshitij.roombooking.model.TimeBlock;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Turns schedule JSON into {@link Profile}s.
 *
 * <p>
 * Two shapes are accepted:
 * <ul>
 * <li>the scheduler's export: {@code {"profiles":[{"id","name","username","password","schedule":{...}}]}}</li>
 * <li>a bare schedule (the bundled default): {@code {"schedule":{...}}}</li>
 * </ul>
 * A schedule maps weekday names to {@code {"enabled", "blocks":[{"start":"HH:mm","end":"HH:mm","rooms":[...]}]}}
 * (the older single-window form {@code {"start","end","rooms"}} is still accepted).
 * Disabled days, and blocks without rooms, are dropped.
 */
public final class BookingSchedule {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    public static final String DEFAULT_RESOURCE = "/default-schedule.json";

    private BookingSchedule() {
    }

    /** Parses the scheduler export into profiles. */
    public static List<Profile> parseProfiles(String json) throws BookingException {
        try {
            JsonNode root = MAPPER.readTree(json);
            JsonNode profiles = root.path("profiles");
            if (!profiles.isArray()) {
                throw new BookingException(BookingStage.CONFIG,
                        "Schedule export had no 'profiles' array.", snippet(json));
            }
            List<Profile> parsed = new ArrayList<>();
            for (JsonNode p : profiles) {
                String id = p.path("id").asText();
                String name = p.path("name").asText(id);
                String username = p.path("username").asText(null);
                String password = p.path("password").asText(null);
                Set<LocalDate> booked = new HashSet<>();
                for (JsonNode d : p.path("bookedDates")) {
                    try {
                        booked.add(LocalDate.parse(d.asText()));
                    } catch (Exception ignored) {
                        // A malformed date only weakens the duplicate guard; never fail the run over it.
                    }
                }
                try {
                    parsed.add(new Profile(id, name, username, password, parseSchedule(p.path("schedule")), null, booked));
                } catch (BookingException e) {
                    // One bad schedule must not stop everyone else's bookings.
                    parsed.add(new Profile(id, name, username, password, Map.of(), e.getMessage(), booked));
                }
            }
            return parsed;
        } catch (BookingException e) {
            throw e;
        } catch (Exception e) {
            throw new BookingException(BookingStage.CONFIG,
                    "Could not read the schedule export: " + e.getMessage(), snippet(json), e);
        }
    }

    /** Parses one {@code schedule} object (weekday name -> day settings). */
    public static Map<DayOfWeek, DayPlan> parseSchedule(JsonNode schedule) throws BookingException {
        Map<DayOfWeek, DayPlan> plans = new EnumMap<>(DayOfWeek.class);
        if (schedule == null || !schedule.isObject()) {
            return plans;
        }
        Iterator<Map.Entry<String, JsonNode>> days = schedule.fields();
        while (days.hasNext()) {
            Map.Entry<String, JsonNode> entry = days.next();
            JsonNode d = entry.getValue();
            if (!d.path("enabled").asBoolean(true)) {
                continue;
            }
            try {
                DayOfWeek day = DayOfWeek.valueOf(entry.getKey().trim().toUpperCase(Locale.ROOT));
                // Current shape: {"blocks":[{start,end,rooms}]}; older shape: {start,end,rooms}.
                List<TimeBlock> blocks = new ArrayList<>();
                if (d.has("blocks")) {
                    for (JsonNode b : d.path("blocks")) {
                        parseBlock(b).ifPresent(blocks::add);
                    }
                } else {
                    parseBlock(d).ifPresent(blocks::add);
                }
                if (!blocks.isEmpty()) {
                    plans.put(day, new DayPlan(blocks));
                }
            } catch (Exception e) {
                throw new BookingException(BookingStage.CONFIG,
                        "Invalid schedule entry for '" + entry.getKey() + "': " + e.getMessage(), d.toString(), e);
            }
        }
        return plans;
    }

    /** A block without rooms is dropped rather than treated as an error. */
    private static Optional<TimeBlock> parseBlock(JsonNode b) {
        List<String> rooms = new ArrayList<>();
        b.path("rooms").forEach(r -> {
            String room = r.asText().trim();
            if (!room.isEmpty()) {
                rooms.add(room);
            }
        });
        if (rooms.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new TimeBlock(BookingWindow.of(b.path("start").asText(), b.path("end").asText()), rooms));
    }

    /** The bundled schedule used when no scheduler URL is configured. */
    public static Map<DayOfWeek, DayPlan> defaultSchedule() throws BookingException {
        try (InputStream in = BookingSchedule.class.getResourceAsStream(DEFAULT_RESOURCE)) {
            if (in == null) {
                throw new BookingException(BookingStage.CONFIG, DEFAULT_RESOURCE + " is missing from the jar.");
            }
            String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return parseSchedule(MAPPER.readTree(json).path("schedule"));
        } catch (IOException e) {
            throw new BookingException(BookingStage.CONFIG, "Could not read " + DEFAULT_RESOURCE, null, e);
        }
    }

    private static String snippet(String json) {
        if (json == null) {
            return "empty response";
        }
        return "response started with: " + json.substring(0, Math.min(200, json.length()));
    }
}
