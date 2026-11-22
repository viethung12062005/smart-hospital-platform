package org.shp.clinical.app;

import io.opentelemetry.api.trace.Span;
import java.time.Clock;
import java.util.UUID;
import org.hl7.fhir.r4.model.ContactPoint;
import org.hl7.fhir.r4.model.Patient;
import org.shp.authz.AuthzDecision;
import org.shp.authz.Principal;
import org.shp.clinical.infra.PatientRepository;
import org.shp.data.mongo.MongoOutbox;
import org.shp.data.mongo.MongoResourceStore.Entry;
import org.shp.events.audit.AuditActions;
import org.shp.events.audit.AuditRecord;
import org.shp.events.audit.AuditRecordBuilder;
import org.shp.events.audit.AuditSink;
import org.shp.events.audit.AuthzAudit;
import org.shp.fhir.FhirJson;
import org.shp.web.errors.ShpException;
import org.springframework.stereotype.Service;

/**
 * 09 C3 (US-003): a nurse creates a record at the counter for a patient who has no account. The
 * same duplicate check as self-registration (AC-01.2) applies; after checking in person the nurse
 * may override it with {@code forceCreate} and a reason, which is audited. Id, MRN and {@code
 * active} are the server's ({@link PatientRepository#create}).
 */
@Service
public class CounterRegistrationService {
  public static final int MIN_REASON = 10;
  static final String AUDIT_PREFIX = "duplicate-override: ";

  /** The audit reason is at most 200 characters ({@link AuditRecordBuilder}), prefix included. */
  public static final int MAX_REASON = AuditRecordBuilder.MAX_REASON_LENGTH - AUDIT_PREFIX.length();

  static final String SERVICE = "clinical";

  /** The nurse who creates the record. */
  public record Nurse(String subject, String practitionerId) {}

  private final PatientRepository patients;
  private final DuplicatePatientCheck duplicates;
  private final MongoOutbox outbox;
  private final AuditSink audit;
  private final Clock clock;

  public CounterRegistrationService(
      PatientRepository patients,
      DuplicatePatientCheck duplicates,
      MongoOutbox outbox,
      AuditSink audit,
      Clock clock) {
    this.patients = patients;
    this.duplicates = duplicates;
    this.outbox = outbox;
    this.audit = audit;
    this.clock = clock;
  }

  /**
   * @param forceCreate {@code ?forceCreate=true}
   * @param overrideReason {@code X-Duplicate-Override-Reason}, required with {@code forceCreate}
   * @throws ShpException 400 {@code validation-failed} (profile, or forceCreate without a 10–200
   *     character reason); 422 {@code duplicate-patient-suspected} when suspected and not forced
   */
  public Entry create(String body, boolean forceCreate, String overrideReason, Nurse nurse) {
    Patient draft = FhirJson.parse(body, Patient.class);
    String reason = overrideReason == null ? null : overrideReason.strip();
    if (forceCreate
        && (reason == null || reason.length() < MIN_REASON || reason.length() > MAX_REASON)) {
      throw new ShpException(
          "validation-failed",
          "forceCreate needs X-Duplicate-Override-Reason of 10 to " + MAX_REASON + " characters.");
    }
    patients.validateDraft(draft);
    boolean suspected =
        duplicates.isSuspected(phone(draft), draft.getBirthDateElement().getValueAsString());
    if (suspected && !forceCreate) {
      throw new ShpException("duplicate-patient-suspected");
    }
    return patients.create(
        draft,
        "User/" + nurse.subject(),
        null,
        created -> {
          if (suspected) {
            outbox.add(auditOverride(created.id(), reason, nurse).toCloudEvent(clock));
          }
        });
  }

  /**
   * C3 is the counter path and is for nurses only, but the matrix also lets a patient create a
   * Patient ({@code PAT-CREATE-PAT}, self-registration C1). The matrix therefore allows the request
   * and the shared interceptor would record its 403 as a success; record the refusal as the denial
   * it is, as the field-rule refusals of C5 do.
   */
  public void auditRefusal(Principal principal, String clientIp) {
    var span = Span.current().getSpanContext();
    audit.emit(
        AuthzAudit.fromDecision(
            clock,
            SERVICE,
            principal,
            "Patient",
            "create",
            "Patient",
            AuthzDecision.deny("role-not-allowed"),
            null,
            span.isValid() ? span.getTraceId() : null,
            clientIp,
            null,
            null));
  }

  /** The override of a suspected duplicate, with its reason (C3: "audited"). */
  private AuditRecord auditOverride(String patientId, String reason, Nurse nurse) {
    UUID uuid = null;
    try {
      uuid = UUID.fromString(patientId);
    } catch (IllegalArgumentException e) {
      uuid = null;
    }
    return AuditRecordBuilder.build(
        clock,
        SERVICE,
        AuditActions.CREATE,
        AuditActions.SUCCESS,
        new AuditRecord.Actor(
            nurse.subject(),
            "nurse",
            nurse.practitionerId() == null ? null : "Practitioner/" + nurse.practitionerId()),
        "Patient/" + patientId,
        uuid,
        null,
        AUDIT_PREFIX + reason,
        null,
        null,
        null);
  }

  private static String phone(Patient p) {
    return p.getTelecom().stream()
        .filter(t -> t.getSystem() == ContactPoint.ContactPointSystem.PHONE)
        .map(ContactPoint::getValue)
        .findFirst()
        .orElse(null);
  }
}
