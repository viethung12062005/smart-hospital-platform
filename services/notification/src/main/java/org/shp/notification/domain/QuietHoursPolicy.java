package org.shp.notification.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Objects;

/**
 * Applies the quiet-hours rule to notification reminder due times.
 *
 * <p>All reminder timestamps are stored and returned as UTC Instants. Quiet hours are evaluated in
 * Asia/Ho_Chi_Minh.
 */
public final class QuietHoursPolicy {

  private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

  private static final String T_24H = "t-24h";
  private static final String T_2H = "t-2h";

  /**
   * Moves a t-24h reminder to the end of quiet hours when necessary. A t-2h reminder bypasses quiet
   * hours. Other reminder kinds are not modified by this policy.
   */
  public Instant adjustDueAt(
      Instant dueAt, String reminderKind, NotificationPreferences preferences) {
    Objects.requireNonNull(dueAt, "dueAt must not be null");
    Objects.requireNonNull(reminderKind, "reminderKind must not be null");
    Objects.requireNonNull(preferences, "preferences must not be null");

    if (T_2H.equals(reminderKind) || !T_24H.equals(reminderKind) || !preferences.hasQuietHours()) {
      return dueAt;
    }

    ZonedDateTime localDueAt = dueAt.atZone(BUSINESS_ZONE);
    LocalTime localTime = localDueAt.toLocalTime();

    LocalTime quietStart = preferences.quietStartTime();
    LocalTime quietEnd = preferences.quietEndTime();

    if (!isWithinQuietHours(localTime, quietStart, quietEnd)) {
      return dueAt;
    }

    LocalDate endDate =
        determineQuietHoursEndDate(localDueAt.toLocalDate(), localTime, quietStart, quietEnd);

    return endDate.atTime(quietEnd).atZone(BUSINESS_ZONE).toInstant();
  }

  private boolean isWithinQuietHours(LocalTime time, LocalTime start, LocalTime end) {
    if (start.isBefore(end)) {
      // Quiet hours do not cross midnight, e.g. 08:00-17:00.
      return !time.isBefore(start) && time.isBefore(end);
    }

    // Quiet hours cross midnight, e.g. 22:00-07:00.
    return !time.isBefore(start) || time.isBefore(end);
  }

  private LocalDate determineQuietHoursEndDate(
      LocalDate currentDate, LocalTime currentTime, LocalTime start, LocalTime end) {
    boolean crossesMidnight = start.isAfter(end);

    if (crossesMidnight && !currentTime.isBefore(start)) {
      return currentDate.plusDays(1);
    }

    return currentDate;
  }
}
