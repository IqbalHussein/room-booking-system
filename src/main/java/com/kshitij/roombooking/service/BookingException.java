package com.kshitij.roombooking.service;

import com.kshitij.roombooking.model.BookingStage;

/** A failure tagged with the pipeline stage that produced it. */
public class BookingException extends Exception {

    private final BookingStage stage;
    private final String detail;

    public BookingException(BookingStage stage, String message) {
        this(stage, message, null, null);
    }

    public BookingException(BookingStage stage, String message, String detail) {
        this(stage, message, detail, null);
    }

    public BookingException(BookingStage stage, String message, String detail, Throwable cause) {
        super(message, cause);
        this.stage = stage;
        this.detail = detail;
    }

    public BookingStage stage() {
        return stage;
    }

    public String detail() {
        return detail;
    }
}
