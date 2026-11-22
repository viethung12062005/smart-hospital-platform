package org.shp.scheduling.domain;

/**
 * Outcome of {@code $book}: {@code replayed=false} is a new appointment (201), {@code
 * replayed=true} is the appointment of an earlier request with the same key and body (200).
 */
public record BookingResult(AppointmentRecord appointment, boolean replayed) {}
