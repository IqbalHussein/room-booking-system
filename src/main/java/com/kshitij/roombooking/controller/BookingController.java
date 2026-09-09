package com.kshitij.roombooking.controller;

import com.kshitij.roombooking.model.BookingResult;
import com.kshitij.roombooking.service.BookingService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class BookingController {

    private final BookingService bookingService;

    @Autowired
    public BookingController(BookingService bookingService) {
        this.bookingService = bookingService;
    }

    /**
     * Returns 200 for outcomes that are expected (booked, nothing scheduled,
     * room already taken) and 500 only when something actually broke, so CI can
     * tell a busy room apart from a broken scraper.
     */
    @PostMapping("/book")
    public ResponseEntity<String> triggerBooking() {
        BookingResult result = bookingService.bookRoom();
        return result.isBenign()
                ? ResponseEntity.ok(result.render())
                : ResponseEntity.internalServerError().body(result.render());
    }
}
