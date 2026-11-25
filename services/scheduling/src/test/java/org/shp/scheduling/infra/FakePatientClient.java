package org.shp.scheduling.infra;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.shp.scheduling.domain.PatientClient;

/**
 * In-memory test double for {@link PatientClient}: every patient exists unless it was marked
 * missing.
 */
public class FakePatientClient implements PatientClient {

  private final Map<UUID, Boolean> missing = new ConcurrentHashMap<>();

  public void markMissing(UUID patientId) {
    missing.put(patientId, Boolean.TRUE);
  }

  public void clear() {
    missing.clear();
  }

  @Override
  public boolean exists(UUID patientId) {
    return !missing.containsKey(patientId);
  }
}
