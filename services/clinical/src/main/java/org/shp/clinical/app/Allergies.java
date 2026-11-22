package org.shp.clinical.app;

import java.util.List;
import java.util.Optional;
import org.bson.Document;

/** Storage of {@code allergy_intolerances} (03 §2.2) through the resource store. */
public interface Allergies {

  record Stored(String id, String patientId, int version, Document resource, Document search) {}

  Stored create(String id, String patientId, Document resource, Document search, String changedBy);

  Optional<Stored> find(String id);

  /** Compare-and-set on {@code expectedVersion} (stale → 412, from the store). */
  Stored update(Stored current, Document resource, Document search, int expectedVersion, String by);

  /** The patient's allergies, most recently recorded first. */
  List<Stored> byPatient(String patientId, int limit);
}
