package org.shp.notification.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuietHoursPolicyTest {

    private final QuietHoursPolicy policy = new QuietHoursPolicy();

    private NotificationPreferences preferences(
            String quietStart,
            String quietEnd
    ) {
        return new NotificationPreferences(
                List.of("push", "email", "sms"),
                "vi",
                quietStart,
                quietEnd
        );
    }

    @Test
    void acceptsPreferencesWithoutQuietHours() {
        NotificationPreferences preferences = preferences(null, null);

        assertFalse(preferences.hasQuietHours());
    }

    @Test
    void rejectsPreferencesWithOnlyQuietStart() {
        assertThrows(
                IllegalArgumentException.class,
                () -> preferences("22:00", null)
        );
    }

    @Test
    void rejectsPreferencesWithOnlyQuietEnd() {
        assertThrows(
                IllegalArgumentException.class,
                () -> preferences(null, "07:00")
        );
    }

    @Test
    void rejectsInvalidChannel() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new NotificationPreferences(
                        List.of("push", "telegram"),
                        "vi",
                        null,
                        null
                )
        );
    }

    @Test
    void rejectsDuplicateChannels() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new NotificationPreferences(
                        List.of("push", "push"),
                        "vi",
                        null,
                        null
                )
        );
    }

    @Test
    void rejectsInvalidLocale() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new NotificationPreferences(
                        List.of("push"),
                        "fr",
                        null,
                        null
                )
        );
    }

    @Test
    void rejectsInvalidQuietHoursFormat() {
        assertThrows(
                IllegalArgumentException.class,
                () -> preferences("22:0", "07:00")
        );
    }

    @Test
    void rejectsEqualQuietHours() {
        assertThrows(
                IllegalArgumentException.class,
                () -> preferences("22:00", "22:00")
        );
    }

    @Test
    void defersT24hReminderAcrossMidnightQuietHours() {
        // 2026-11-08 23:30 in Vietnam.
        Instant dueAt = Instant.parse("2026-11-08T16:30:00Z");

        Instant adjusted = policy.adjustDueAt(
                dueAt,
                "t-24h",
                preferences("22:00", "07:00")
        );

        // 2026-11-09 07:00 in Vietnam.
        assertEquals(
                Instant.parse("2026-11-09T00:00:00Z"),
                adjusted
        );
    }

    @Test
    void defersT24hReminderWhenItFallsAfterMidnightQuietHours() {
        // 2026-11-08 02:00 in Vietnam.
        Instant dueAt = Instant.parse("2026-11-07T19:00:00Z");

        Instant adjusted = policy.adjustDueAt(
                dueAt,
                "t-24h",
                preferences("22:00", "07:00")
        );

        // 2026-11-08 07:00 in Vietnam.
        assertEquals(
                Instant.parse("2026-11-08T00:00:00Z"),
                adjusted
        );
    }

    @Test
    void doesNotDeferT24hReminderOutsideQuietHours() {
        // 2026-11-08 10:00 in Vietnam.
        Instant dueAt = Instant.parse("2026-11-08T03:00:00Z");

        Instant adjusted = policy.adjustDueAt(
                dueAt,
                "t-24h",
                preferences("22:00", "07:00")
        );

        assertEquals(dueAt, adjusted);
    }

    @Test
    void doesNotDeferT24hReminderAtQuietHoursEnd() {
        // 2026-11-08 07:00 in Vietnam.
        Instant dueAt = Instant.parse("2026-11-08T00:00:00Z");

        Instant adjusted = policy.adjustDueAt(
                dueAt,
                "t-24h",
                preferences("22:00", "07:00")
        );

        assertEquals(dueAt, adjusted);
    }

    @Test
    void doesNotDeferT2hReminderDuringQuietHours() {
        // 2026-11-08 23:30 in Vietnam.
        Instant dueAt = Instant.parse("2026-11-08T16:30:00Z");

        Instant adjusted = policy.adjustDueAt(
                dueAt,
                "t-2h",
                preferences("22:00", "07:00")
        );

        assertEquals(dueAt, adjusted);
    }

    @Test
    void doesNotModifyOtherReminderKinds() {
        Instant dueAt = Instant.parse("2026-11-08T16:30:00Z");

        Instant adjusted = policy.adjustDueAt(
                dueAt,
                "booking-confirmation",
                preferences("22:00", "07:00")
        );

        assertEquals(dueAt, adjusted);
    }

    @Test
    void doesNotModifyDueTimeWhenQuietHoursAreDisabled() {
        Instant dueAt = Instant.parse("2026-11-08T16:30:00Z");

        Instant adjusted = policy.adjustDueAt(
                dueAt,
                "t-24h",
                preferences(null, null)
        );

        assertEquals(dueAt, adjusted);
    }

    @Test
    void supportsQuietHoursWithinSameDay() {
        // 2026-11-08 12:00 in Vietnam.
        Instant dueAt = Instant.parse("2026-11-08T05:00:00Z");

        Instant adjusted = policy.adjustDueAt(
                dueAt,
                "t-24h",
                preferences("08:00", "17:00")
        );

        // 2026-11-08 17:00 in Vietnam.
        assertEquals(
                Instant.parse("2026-11-08T10:00:00Z"),
                adjusted
        );
    }

    @Test
    void rejectsNullChannel() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new NotificationPreferences(
                        java.util.Arrays.asList("push", null),
                        "vi",
                        null,
                        null
                )
        );
    }
}