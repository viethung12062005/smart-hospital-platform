package org.shp.clinical.infra;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import org.bson.Document;
import org.hl7.fhir.r4.model.ContactPoint.ContactPointSystem;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Patient;
import org.shp.clinical.app.ProfileValidator;
import org.shp.clinical.domain.PatientNormalizer;
import org.shp.data.mongo.MongoMessaging;
import org.shp.data.mongo.MongoResourceStore;
import org.shp.data.mongo.MongoResourceStore.Entry;
import org.shp.data.mongo.MongoResourceStore.History;
import org.shp.data.mongo.MongoResourceStore.ResourceInput;
import org.shp.events.CloudEvent;
import org.shp.events.Uuids;
import org.shp.fhir.FhirJson;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

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
  private final TransactionTemplate tx;
  private final Clock clock;

  public PatientRepository(
      MongoTemplate mongo,
      MongoResourceStore store,
      MrnGenerator mrns,
      ProfileValidator validator,
      TransactionTemplate tx,
      Clock clock) {
    this.mongo = mongo;
    this.store = store;
    this.mrns = mrns;
    this.validator = validator;
    this.tx = tx;
    this.clock = clock;
  }

  /**
   * Create a patient at version 1. The caller supplies the demographic elements; id, MRN and {@code
   * active=true} are generated here and any value the caller sent for them is replaced.
   *
   * @param event outbox event written in the same transaction, or null
   */
  public Entry create(Patient draft, String changedBy, CloudEvent event) {
    return create(draft, changedBy, event, entry -> {});
  }

  /**
   * Like {@link #create(Patient, String, CloudEvent)}, and runs {@code sameTransaction} with the
   * stored entry inside the same transaction as the patient, its history and the event. If it
   * throws, everything rolls back (for example the identity link of C1).
   */
  public Entry create(
      Patient draft, String changedBy, CloudEvent event, Consumer<Entry> sameTransaction) {
    Patient patient = draft.copy();
    String id = Uuids.v7(clock).toString();
    String mrn = mrns.next(); // outside the transaction, see MrnGenerator
    patient.setId(id);
    List<Identifier> others =
        patient.getIdentifier().stream().filter(i -> !MRN_SYSTEM.equals(i.getSystem())).toList();
    patient.setIdentifier(new ArrayList<>(others));
    patient.addIdentifier().setSystem(MRN_SYSTEM).setValue(mrn);
    patient.setActive(true);
    ResourceInput input = input(patient, 1);
    return MongoMessaging.inTransaction(
        tx,
        () -> {
          Entry entry = store.create(input, changedBy, event);
          sameTransaction.accept(entry);
          return entry;
        });
  }

  /**
   * Check a registration draft against the profile (01 §4.1) before an MRN is spent on it. The
   * server-generated elements are filled with placeholders, as {@link #create} would.
   */
  public void validateDraft(Patient draft) {
    Patient probe = draft.copy();
    probe.setIdentifier(new ArrayList<>());
    probe.addIdentifier().setSystem(MRN_SYSTEM).setValue("BN-0000-000000");
    probe.setActive(true);
    validator.validate(probe);
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

  /** Every stored version of a patient, oldest first. */
  public List<History> history(String id) {
    return store.history(TYPE, id);
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
