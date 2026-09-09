package com.kshitij.roombooking.model;

/**
 * Where in the pipeline something went wrong.
 *
 * <p>
 * Every failure is tagged with a stage so a log line names the step that broke
 * instead of leaving you to infer it. {@link #GRID_MISMATCH} in particular is a
 * tripwire for "LibCal changed their markup again" — the exact class of failure
 * that previously surfaced as a misleading "no availability".
 */
public enum BookingStage {

    /** Credentials missing or malformed. Checked before the browser starts. */
    CONFIG,
    /** Resolving the room number to a LibCal space URL. */
    ROOM_LOOKUP,
    /** Moving the grid to the target date. */
    DATE_NAV,
    /** Requesting the availability JSON. */
    GRID_FETCH,
    /** Reading the availability JSON. */
    GRID_PARSE,
    /** JSON and DOM disagree — strong signal that LibCal's markup changed. */
    GRID_MISMATCH,
    /** Clicking the starting cell. */
    SLOT_CLICK,
    /** Choosing the end time. */
    END_TIME,
    /** Submitting the chosen times. */
    SUBMIT_TIMES,
    /** Signing in through ADFS. */
    LOGIN,
    /** Final booking confirmation. */
    CONFIRM
}
