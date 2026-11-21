package org.shp.clinical.infra;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import org.bson.Document;
import org.hl7.fhir.r4.model.ContactPoint.ContactPointSystem;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Patient;
import org.shp.clinical.app.ProfileValidator;
import org.shp.clinical.domain.PatientNormalizer;
import org.shp.data.mongo.MongoResourceStore;
import org.shp.data.mongo.MongoResourceStore.Entry;
import org.shp.data.mongo.MongoResourceStore.ResourceInput;
import org.shp.events.CloudEvent;
import org.shp.events.Uuids;
import org.shp.fhir.FhirJson;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

/**
 * Patient persistence (03 §2.2, §2.3). Builds the stored document: server-generated id, MRN, {@code
 * active} and {@code meta}, plus the normalized {@code search} fields. The profile (01 §4.1) is
 * validated before anything is written. Every write goes through {@link MongoResourceStore}, so the
 * resource, its history entry and the outbox event share one transaction.
 */
@Repository
public class PatientRepository {
  static final String TYPE = "Patient";
  static final String MRN_SYSTEM = "urn:shp:mrn";

  private final MongoTemplate mongo;
  private final MongoResourceStore store;
  private final MrnGenerator mrns;
  private final ProfileValidator validator;
  private final Clock clock;

  public PatientRepository(
      MongoTemplate mongo,
      MongoResourceStore store,
      MrnGenerator mrns,
      ProfileValidator validator,
      Clock clock) {
    this.mongo = mongo;
    this.store = store;
    this.mrns = mrns;
    this.validator = validator;
    this.clock = clock;
  }

  /**
   * Create a patient at version 1. The caller supplies the demographic elements; id, MRN and {@code
   * active=true} are generated here and any value the caller sent for them is replaced.
   *
   * @param event outbox event written in the same transaction, or null
   */
  public Entry create(Patient draft, String changedBy, CloudEvent event) {
    Patient patient = draft.copy();
    String id = Uuids.v7(clock).toString();
    String mrn = mrns.next();
    patient.setId(id);
    List<Identifier> others =
        patient.getIdentifier().stream().filter(i -> !MRN_SYSTEM.equals(i.getSystem())).toList();
    patient.setIdentifier(new ArrayList<>(others));
    patient.addIdentifier().setSystem(MRN_SYSTEM).setValue(mrn);
    patient.setActive(true);
    return store.create(input(patient, 1), changedBy, event);
  }

  /**
   * Replace a patient with optimistic concurrency. The caller passes the complete new resource; the
   * stored MRN is kept by the caller.
   */
  public Entry update(Patient patient, int expectedVersion, String changedBy, CloudEvent event) {
    return store.update(input(patient, expectedVersion + 1), expectedVersion, changedBy, event);
  }

  public Optional<Entry> find(String id) {
    return store.find(TYPE, id);
  }

  /**
   * True when an active patient has this E.164 phone and birth date (03 §2.6; index {@code
   * search.phone, search.birthDate}). Returns only a boolean so no caller can read another record.
   */
  public boolean existsActiveByPhoneAndBirthDate(String phoneE164, String birthDate) {
    return mongo.exists(
        Query.query(
            Criteria.where("search.phone")
                .is(phoneE164)
                .and("search.birthDate")
                .is(birthDate)
                .and("search.active")
                .is(true)),
        "patients");
  }

  /** Parse the stored FHIR JSON of an entry. */
  public static Patient toFhir(Entry entry) {
    return FhirJson.parse(entry.resource().toJson(), Patient.class);
  }

  private ResourceInput input(Patient patient, int version) {
    validator.validate(patient);
    patient.getMeta().setVersionId(String.valueOf(version));
    patient.getMeta().setLastUpdated(Date.from(clock.instant()));
    Document resource = Document.parse(FhirJson.serialize(patient));
    return new ResourceInput(
        TYPE,
        patient.getIdElement().getIdPart(),
        patient.getIdElement().getIdPart(),
        resource,
        search(patient));
  }

  /** The normalized {@code search} fields of 03 §2.2. Server-written only. */
  static Document search(Patient patient) {
    String mrn =
        patient.getIdentifier().stream()
            .filter(i -> MRN_SYSTEM.equals(i.getSystem()))
            .findFirst()
            .orElseThrow()
            .getValue();
    String phone =
        patient.getTelecom().stream()
            .filter(t -> t.getSystem() == ContactPointSystem.PHONE)
            .findFirst()
            .map(t -> t.getValue())
            .orElseThrow();
    return new Document("mrn", mrn)
        .append("nameTokens", PatientNormalizer.nameTokens(patient.getNameFirstRep().getText()))
        .append("phone", PatientNormalizer.normalizePhone(phone).orElse(phone))
        .append("birthDate", patient.getBirthDateElement().getValueAsString())
        .append("gender", patient.getGender().toCode())
        .append("active", patient.getActive());
  }
}
