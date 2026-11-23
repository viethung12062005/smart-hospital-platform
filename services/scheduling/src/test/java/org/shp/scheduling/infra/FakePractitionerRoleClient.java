package org.shp.scheduling.infra;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.shp.scheduling.domain.PractitionerRoleClient;

/**
 * In-memory test double for {@link PractitionerRoleClient} used by scheduling API integration tests
 * that do not exercise the clinical HTTP contract directly.
 */
public class FakePractitionerRoleClient implements PractitionerRoleClient {

  private final Map<UUID, Set<String>> specialtiesByPractitioner = new ConcurrentHashMap<>();

  public void register(UUID practitionerId, String... specialties) {
    specialtiesByPractitioner.put(practitionerId, Set.of(specialties));
  }

  public void clear() {
    specialtiesByPractitioner.clear();
  }

  @Override
  public List<String> findActiveSpecialties(UUID practitionerId) {
    var set = specialtiesByPractitioner.get(practitionerId);
    return set != null ? List.copyOf(set) : List.of();
  }
}
