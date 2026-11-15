package org.shp.notification.domain;

/** Reminder milestones stored in the {@code reminders} collection (04-db-notification-ml §1.2). */
public enum ReminderKind {
  BOOKING_CONFIRMATION("booking-confirmation"),
  T_24H("t-24h"),
  T_6H_RISK("t-6h-risk"),
  T_2H("t-2h"),
  CANCELLATION("cancellation"),
  RESULT_AVAILABLE("result-available");

  private final String value;

  ReminderKind(String value) {
    this.value = value;
  }

  /** Hyphenated value used by the collection validator and the unique index. */
  public String value() {
    return value;
  }
}
