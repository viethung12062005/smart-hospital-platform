package org.shp.clinical.app;

import java.util.List;
import java.util.Optional;
import org.shp.clinical.domain.CareRelationship;

/** Storage of {@code care_relationships} (03 §2.2). Called inside the inbox transaction. */
public interface CareRelationships {

  Optional<CareRelationship> find(String practitionerId, String patientId);

  /** Every relationship of the patient that has a source with this type and id. */
  List<CareRelationship> findBySource(String patientId, String type, String sourceId);

  /** Replaces the whole document (upsert by {@code _id}) and its {@code validUntil}. */
  void save(CareRelationship relationship);
}
