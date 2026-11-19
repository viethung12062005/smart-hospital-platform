package org.shp.clinical.app;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.bson.Document;

/** Storage of {@code observations} (03 §2.2) through the resource store (resource + history). */
public interface Observations {

  record Stored(String id, String patientId, int version, Document resource, Document search) {}

  /** Inserts version 1 with its history entry in one transaction. */
  Stored create(String id, String patientId, Document resource, Document search, String changedBy);

  Optional<Stored> find(String id);

  /**
   * The patient's Observations, newest {@code effective} first.
   *
   * @param encounterIds when not null, only Observations of these Encounters
   * @param category, code null = any
   */
  List<Stored> byPatient(
      String patientId, Collection<String> encounterIds, String category, String code, int limit);
}
