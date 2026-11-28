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
  private volatile boolean down;
  private final java.util.concurrent.atomic.AtomicInteger lookups =
      new java.util.concurrent.atomic.AtomicInteger();

  private final java.util.concurrent.atomic.AtomicInteger inTransaction =
      new java.util.concurrent.atomic.AtomicInteger();

  public void markMissing(UUID patientId) {
    missing.put(patientId, Boolean.TRUE);
  }

  /** While down, every lookup fails like an unreachable clinical service. */
  public void setDown(boolean down) {
    this.down = down;
  }

  /** Lookups made while a database transaction was active on the calling thread. */
  public int lookupsInTransaction() {
    return inTransaction.get();
  }

  public int lookups() {
    return lookups.get();
  }

  public void clear() {
    missing.clear();
    down = false;
    lookups.set(0);
    inTransaction.set(0);
  }

  @Override
  public boolean exists(UUID patientId) {
    lookups.incrementAndGet();
    if (org.springframework.transaction.support.TransactionSynchronizationManager
        .isActualTransactionActive()) {
      inTransaction.incrementAndGet();
    }
    if (down) {
      throw new org.shp.web.errors.ShpException("dependency-unavailable");
    }
    return !missing.containsKey(patientId);
  }
}
