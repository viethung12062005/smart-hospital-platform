package org.shp.notification.app;

import java.util.Optional;
import java.util.UUID;

/**
 * Port to the clinical service's internal contact lookup (09 §4). Notification never stores the
 * result: callers fetch it at send time and drop it afterwards, so no phone number or email lands
 * in this service's database or logs.
 */
public interface ContactClient {

  /** Contact details of one patient, empty when clinical has none (404). */
  Optional<Contact> findContact(UUID patientId);

  /**
   * Phone, email and locale as returned by clinical. {@link #toString()} is redacted so the record
   * cannot leak into a log line by accident.
   */
  record Contact(String phone, String email, String locale) {
    @Override
    public String toString() {
      return "Contact[redacted]";
    }
  }
}
