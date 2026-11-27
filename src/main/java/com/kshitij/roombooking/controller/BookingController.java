package com.kshitij.roombooking.controller;

import com.kshitij.roombooking.model.BookingResult;
import com.kshitij.roombooking.model.ProfileResult;
import com.kshitij.roombooking.service.BookingService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class BookingController {

    private final BookingService bookingService;

    public BookingController(BookingService bookingService) {
        this.bookingService = bookingService;
    }

    /** Readiness probe. Never triggers a booking. */
    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "UP");
    }

    /**
     * Books for every profile and reports one entry per profile. Returns 200
     * when every outcome is expected (booked, nothing scheduled, room taken) and
     * 500 when any profile actually broke, so CI can tell a busy room apart from
     * a broken scraper.
     */
    @PostMapping("/book")
    public ResponseEntity<Map<String, Object>> triggerBooking() {
        BookingService.Run run = bookingService.bookAll();
        List<ProfileResult> results = run.results();
        BookingResult.Status overall = overall(results);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("overall", overall.name());
        body.put("targetDate", run.targetDate() == null ? null : run.targetDate().toString());
        body.put("results", results.stream().map(r -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("profileId", r.profileId());
            row.put("profileName", r.profileName());
            row.put("status", r.result().status().name());
            row.put("summary", r.result().summary());
            row.put("detail", r.result().detail());
            row.put("line", r.render());
            return row;
        }).toList());

        return overall == BookingResult.Status.FAILED
                ? ResponseEntity.internalServerError().body(body)
                : ResponseEntity.ok(body);
    }

    /** FAILED beats SUCCESS beats UNAVAILABLE beats SKIPPED. */
    static BookingResult.Status overall(List<ProfileResult> results) {
        List<BookingResult.Status> rank = List.of(BookingResult.Status.FAILED, BookingResult.Status.SUCCESS,
                BookingResult.Status.UNAVAILABLE, BookingResult.Status.SKIPPED);
        return results.stream()
                .map(r -> r.result().status())
                .min(Comparator.comparingInt(rank::indexOf))
                .orElse(BookingResult.Status.SKIPPED);
    }
}
