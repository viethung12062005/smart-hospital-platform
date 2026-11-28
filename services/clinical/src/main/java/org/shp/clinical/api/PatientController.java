package org.shp.clinical.api;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.util.UUID;
import org.hl7.fhir.r4.model.Patient;
import org.shp.authz.AuthorizationEngine;
import org.shp.authz.AuthzDecision;
import org.shp.authz.Principal;
import org.shp.authz.ResourceContext;
import org.shp.clinical.app.NationalIdRules;
import org.shp.clinical.app.PatientService;
import org.shp.clinical.infra.NationalIdVault;
import org.shp.clinical.infra.PatientRepository;
import org.shp.data.mongo.MongoResourceStore.Entry;
import org.shp.events.audit.AuditActions;
import org.shp.events.audit.AuditRecord;
import org.shp.events.audit.AuditRecordBuilder;
import org.shp.events.audit.AuditSink;
import org.shp.events.audit.AuthzAudit;
import org.shp.fhir.FhirJson;
import org.shp.web.auth.IdentityInterceptor;
import org.shp.web.authz.AuthzInterceptor;
import org.shp.web.authz.RequireShpAuthz;
import org.shp.web.errors.ShpException;
import org.shp.web.precondition.ETags;
import org.shp.web.precondition.PreconditionInterceptor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * C4 read, C5 update and C7 history of a Patient (09 §2). The CCCD (CLN-30) is returned masked
 * ({@code ********1234}); a nurse sees the full number with {@code ?reveal=national-id}, and every
 * reveal writes an audit record.
 */
@RestController
class PatientController {
  private static final MediaType FHIR_JSON = MediaType.parseMediaType(FhirJson.MEDIA_TYPE);
  static final String REVEAL = "national-id";

  private final PatientService service;
  private final NationalIdVault vault;
  private final AuditSink audit;
  private final Clock clock;
  private final AuthorizationEngine engine;

  PatientController(
      PatientService service,
      NationalIdVault vault,
      AuditSink audit,
      Clock clock,
      AuthorizationEngine engine) {
    this.engine = engine;
    this.service = service;
    this.vault = vault;
    this.audit = audit;
    this.clock = clock;
  }

  @GetMapping("/fhir/Patient/{id}")
  @RequireShpAuthz(resource = "Patient", action = "read", context = "patientFacts")
  ResponseEntity<String> read(
      @PathVariable String id,
      @RequestParam(name = "reveal", required = false) String reveal,
      HttpServletRequest req) {
    Entry entry = service.read(id);
    boolean full = false;
    if (reveal != null) {
      Principal p = IdentityInterceptor.principal(req);
      if (!REVEAL.equals(reveal)) {
        throw new ShpException("validation-failed", "reveal must be national-id.");
      }
      AuthzDecision revealDecision = engine.decide(p, "Patient", "reveal", ResourceContext.empty());
      if (!revealDecision.allowed()) {
        auditRevealDenied(p, id, revealDecision, req);
        throw new ShpException("forbidden", "Only a nurse may reveal the national ID number.");
      }
      if (entry.search().getString("nationalIdEnc") != null) {
        auditReveal(p, id, req);
        full = true;
      }
    }
    return resource(entry, full);
  }

  @PutMapping("/fhir/Patient/{id}")
  @RequireShpAuthz(resource = "Patient", action = "update", context = "patientFacts")
  ResponseEntity<String> update(
      @PathVariable String id, @RequestBody String body, HttpServletRequest req) {
    Entry updated =
        service.update(
            IdentityInterceptor.principal(req),
            id,
            PreconditionInterceptor.expectedVersion(req),
            body,
            AuthzInterceptor.decision(req),
            AuthzInterceptor.delegation(req),
            req.getRemoteAddr());
    return resource(updated, false);
  }

  /** Nurses only; see {@code PatientApiConfig}. */
  @GetMapping("/fhir/Patient/{id}/_history")
  @RequireShpAuthz(resource = "Patient", action = "read", context = "patientHistoryFacts")
  ResponseEntity<String> history(@PathVariable String id) {
    return ResponseEntity.ok().contentType(FHIR_JSON).body(FhirJson.serialize(service.history(id)));
  }

  private ResponseEntity<String> resource(Entry entry, boolean revealNationalId) {
    Patient p = PatientRepository.toFhir(entry);
    String last4 = entry.search().getString("nationalIdLast4");
    if (last4 != null) {
      String value =
          revealNationalId
              ? vault.decrypt(entry.search().getString("nationalIdEnc"))
              : NationalIdVault.mask(last4);
      p.addIdentifier().setSystem(NationalIdRules.SYSTEM).setValue(value);
    }
    return ResponseEntity.ok()
        .contentType(FHIR_JSON)
        .header(HttpHeaders.ETAG, ETags.format(entry.versionId()))
        .body(FhirJson.serialize(p));
  }

  /** 09 C5: "requires ?reveal=national-id and emits an audit record every time". No value. */
  private void auditReveal(Principal p, String id, HttpServletRequest req) {
    UUID patientId;
    try {
      patientId = UUID.fromString(id);
    } catch (IllegalArgumentException e) {
      patientId = null;
    }
    audit.emit(
        AuditRecordBuilder.build(
            clock,
            "clinical",
            AuditActions.READ,
            AuditActions.SUCCESS,
            new AuditRecord.Actor(
                p.subject(),
                "nurse",
                p.fhirUserId() == null ? null : "Practitioner/" + p.fhirUserId()),
            "Patient/" + id,
            patientId,
            "TREAT",
            "reveal: national-id",
            null,
            req.getRemoteAddr(),
            req.getHeader("User-Agent")));
  }

  /**
   * A refused reveal leaves a trace as well (matrix action {@code Patient: reveal}). The read was
   * allowed through {@code patientFacts}, so a guardian or proxy reaching a managed profile keeps
   * {@code onBehalfOf} on the refusal.
   */
  private void auditRevealDenied(
      Principal p, String id, AuthzDecision decision, HttpServletRequest req) {
    UUID patientId;
    try {
      patientId = UUID.fromString(id);
    } catch (IllegalArgumentException e) {
      patientId = null;
    }
    audit.emit(
        AuthzAudit.fromDecision(
            clock,
            "clinical",
            p,
            AuthzAudit.fhirUserType(p),
            "reveal",
            "Patient/" + id,
            decision,
            patientId,
            null,
            req.getRemoteAddr(),
            req.getHeader("User-Agent"),
            null,
            null,
            AuthzInterceptor.delegation(req)));
  }
}
