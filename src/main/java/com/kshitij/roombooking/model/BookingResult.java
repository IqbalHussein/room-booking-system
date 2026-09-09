package com.kshitij.roombooking.model;

/**
 * The result of a booking attempt.
 *
 * <p>
 * The distinction that matters operationally is {@link Status#UNAVAILABLE}
 * versus {@link Status#FAILED}. A room that is simply already booked is a
 * normal outcome and must not look like a broken scraper; only FAILED means
 * something needs a human.
 */
public record BookingResult(Status status, String summary, String detail) {

    public enum Status {
        /** Booked. */
        SUCCESS,
        /** Nothing scheduled for this day — not an error. */
        SKIPPED,
        /** The wanted window is genuinely taken — not an error. */
        UNAVAILABLE,
        /** Something broke and needs attention. */
        FAILED
    }

    public static BookingResult success(String summary) {
        return new BookingResult(Status.SUCCESS, summary, null);
    }

    public static BookingResult skipped(String summary) {
        return new BookingResult(Status.SKIPPED, summary, null);
    }

    public static BookingResult unavailable(String summary, String detail) {
        return new BookingResult(Status.UNAVAILABLE, summary, detail);
    }

    public static BookingResult failed(BookingStage stage, String summary, String detail) {
        return new BookingResult(Status.FAILED, "[" + stage + "] " + summary, detail);
    }

    /** True when this outcome is expected and should not fail the build. */
    public boolean isBenign() {
        return status != Status.FAILED;
    }

    /** Single-line form, prefixed with the status token the CI workflow greps for. */
    public String render() {
        return status + ": " + summary + (detail == null || detail.isBlank() ? "" : " | " + detail);
    }
}
