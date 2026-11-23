package com.kshitij.roombooking.model;

/** A booking outcome attributed to the profile it was made for. */
public record ProfileResult(String profileId, String profileName, BookingResult result) {

    public String render() {
        return result.status() + ": [" + profileName + "] " + result.summary()
                + (result.detail() == null || result.detail().isBlank() ? "" : " | " + result.detail());
    }
}
