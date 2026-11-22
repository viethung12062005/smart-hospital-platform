package org.shp.clinical.app;

import java.util.List;
import java.util.Optional;
import org.bson.Document;

/** Storage of {@code conditions} (03 §2.2) through the resource store (resource + history). */
public interface Conditions {

  record Stored(String id, String patientId, int version, Document resource, Document search) {}

  Stored create(String id, String patientId, Document resource, Document search, String changedBy);

  Optional<Stored> find(String id);

  /** Compare-and-set on {@code expectedVersion} (stale → 412, from the store). */
  Stored update(Stored current, Document resource, Document search, int expectedVersion, String by);

  /** The patient's Conditions, newest {@code recordedDate} first. */
  List<Stored> byPatient(String patientId, int limit);

  /** The Conditions recorded for an Encounter (any status). */
  List<Stored> byEncounter(String encounterId);
}
