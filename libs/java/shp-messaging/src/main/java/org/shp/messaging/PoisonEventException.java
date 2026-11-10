package org.shp.messaging;

import java.util.List;

/**
 * Event cannot be read or has a bad schema: it can never be processed, so it goes to the DLQ after
 * the maximum delivery count. The message contains only the id, type and failing field locations,
 * not the {@code data} values.
 */
public final class PoisonEventException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  public PoisonEventException(String id, String type, List<String> locations) {
    super("Invalid event id=" + id + " type=" + type + " at " + locations);
  }

  public PoisonEventException(Throwable cause) {
    super("Event cannot be read (not CloudEvent JSON)", cause);
  }
}
