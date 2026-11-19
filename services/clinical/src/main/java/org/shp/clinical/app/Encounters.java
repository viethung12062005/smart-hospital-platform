package org.shp.clinical.app;

import org.bson.Document;

/** Storage of {@code encounters} (03 §2.2) through the resource store (resource + history). */
public interface Encounters {

  /** Whether an Encounter already exists for the appointment ({@code search.appointmentId}). */
  boolean existsForAppointment(String appointmentId);

  /**
   * Inserts a new Encounter at version 1 with its history entry, inside the caller's transaction.
   *
   * @param resource the FHIR Encounter as a document
   * @param search the 03 §2.2 search fields
   */
  void create(String id, String patientId, Document resource, Document search, String changedBy);
}
