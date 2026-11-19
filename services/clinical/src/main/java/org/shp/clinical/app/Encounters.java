package org.shp.clinical.app;

import java.util.List;
import java.util.Optional;
import org.bson.Document;

/** Storage of {@code encounters} (03 §2.2) through the resource store (resource + history). */
public interface Encounters {

  /** A stored, non-deleted Encounter. */
  record Stored(String id, String patientId, int version, Document resource, Document search) {}

  /** Whether an Encounter already exists for the appointment ({@code search.appointmentId}). */
  boolean existsForAppointment(String appointmentId);

  /**
   * Inserts a new Encounter at version 1 with its history entry, inside the caller's transaction.
   *
   * @param resource the FHIR Encounter as a document
   * @param search the 03 §2.2 search fields
   */
  void create(String id, String patientId, Document resource, Document search, String changedBy);

  Optional<Stored> find(String id);

  /**
   * Compare-and-set on {@code expectedVersion} (stale → 412, missing → 404, from the store); the
   * history entry and, when given, the outbox event are written in the same transaction.
   */
  Stored update(
      Stored current,
      Document resource,
      Document search,
      int expectedVersion,
      String changedBy,
      org.shp.events.CloudEvent event);

  /** The patient's Encounters, newest {@code periodStart} first; {@code status} null = any. */
  List<Stored> byPatient(String patientId, String status, int limit);
}
