package org.shp.notification.domain;

import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Validated notification preferences used by the notification domain.
 *
 * <p>quietStart and quietEnd must either both be provided or both be absent.
 */
public record NotificationPreferences(
    List<String> channels, String locale, String quietStart, String quietEnd) {
  private static final Set<String> ALLOWED_CHANNELS = Set.of("push", "sms", "email");

  public NotificationPreferences {
    if (channels == null || channels.isEmpty()) {
      throw new IllegalArgumentException("At least one notification channel is required");
    }

    if (channels.stream()
        .anyMatch(channel -> channel == null || !ALLOWED_CHANNELS.contains(channel))) {
      throw new IllegalArgumentException("Channels must be selected from push, sms, email");
    }

    channels = List.copyOf(channels);

    if (new HashSet<>(channels).size() != channels.size()) {
      throw new IllegalArgumentException("Notification channels must not contain duplicates");
    }

    if (!"vi".equals(locale) && !"en".equals(locale)) {
      throw new IllegalArgumentException("Locale must be either vi or en");
    }

    boolean hasStart = quietStart != null;
    boolean hasEnd = quietEnd != null;

    if (hasStart != hasEnd) {
      throw new IllegalArgumentException("quietStart and quietEnd must be provided together");
    }

    if (hasStart) {
      LocalTime start = parseQuietTime(quietStart, "quietStart");
      LocalTime end = parseQuietTime(quietEnd, "quietEnd");

      if (start.equals(end)) {
        throw new IllegalArgumentException("quietStart and quietEnd must be different");
      }
    }
  }

  public boolean hasQuietHours() {
    return quietStart != null;
  }

  public LocalTime quietStartTime() {
    return hasQuietHours() ? LocalTime.parse(quietStart) : null;
  }

  public LocalTime quietEndTime() {
    return hasQuietHours() ? LocalTime.parse(quietEnd) : null;
  }

  private static LocalTime parseQuietTime(String value, String fieldName) {
    if (value == null || !value.matches("^([01][0-9]|2[0-3]):[0-5][0-9]$")) {
      throw new IllegalArgumentException(fieldName + " must use HH:mm format");
    }

    try {
      return LocalTime.parse(value);
    } catch (DateTimeParseException exception) {
      throw new IllegalArgumentException(fieldName + " must use HH:mm format", exception);
    }
  }
}
