package org.shp.clinical.api;

/** 201 body of C1: the new record id and its MRN. */
public record RegistrationResult(String patientId, String mrn) {}
