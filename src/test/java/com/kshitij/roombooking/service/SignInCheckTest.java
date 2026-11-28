package com.kshitij.roombooking.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SignInCheckTest {

    @Test
    void recognisesLibCalByHostOnly() {
        assertTrue(BookingService.onLibCal("https://carletonu.libcal.com/spaces/auth?returnUrl=x"));
        // The sign-in page's URL carries a LibCal return link; that must not count as "back on LibCal".
        assertFalse(BookingService.onLibCal(
                "https://adfs.carleton.ca/adfs/ls/?wa=wsignin1.0&wreply=https%3a%2f%2fcarletonu.libcal.com%2fspaces"));
        assertFalse(BookingService.onLibCal("https://evil-libcal.com.example.org/"));
        assertFalse(BookingService.onLibCal("not a url"));
        assertFalse(BookingService.onLibCal(null));
    }
}
