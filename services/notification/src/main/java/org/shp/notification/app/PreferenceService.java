package org.shp.notification.app;

import java.time.Clock;
import java.util.List;
import org.shp.notification.domain.NotificationPreferences;
import org.shp.notification.infra.MongoPreferenceStore;
import org.shp.web.errors.ShpException;
import org.springframework.stereotype.Service;

/** View and change a patient's notification preferences (N3, N4). */
@Service
public class PreferenceService {
  private final MongoPreferenceStore store;
  private final Clock clock;

  public PreferenceService(MongoPreferenceStore store, Clock clock) {
    this.store = store;
    this.clock = clock;
  }

  public NotificationPreferences get(String patientId) {
    return store.find(patientId).orElseThrow(() -> new ShpException("not-found"));
  }

  public NotificationPreferences change(
      String patientId, List<String> channels, String locale, String quietStart, String quietEnd) {
    NotificationPreferences preferences;
    try {
      preferences = new NotificationPreferences(channels, locale, quietStart, quietEnd);
    } catch (IllegalArgumentException e) {
      // The domain messages never echo submitted values.
      throw new ShpException("validation-failed", e.getMessage());
    }
    store.save(patientId, preferences, clock.instant());
    return preferences;
  }
}
