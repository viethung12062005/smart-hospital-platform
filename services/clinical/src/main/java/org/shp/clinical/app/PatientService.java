package org.shp.clinical.app;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.bson.Document;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Bundle.BundleType;
import org.hl7.fhir.r4.model.Bundle.HTTPVerb;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Patient;
import org.shp.authz.AuthzDecision;
import org.shp.authz.Principal;
import org.shp.authz.ResourceContext;
import org.shp.clinical.infra.PatientRepository;
import org.shp.data.mongo.MongoResourceStore.Entry;
import org.shp.data.mongo.MongoResourceStore.History;
import org.shp.events.audit.AuditSink;
import org.shp.events.audit.AuthzAudit;
import org.shp.fhir.FhirJson;
import org.shp.web.errors.ShpException;
import org.springframework.stereotype.Service;

/**
 * Read, update and history of a Patient (C4, C5, C7; 09 §2). Who may call is decided by the
 * authorization matrix before this class runs; here live the rules that depend on the content of
 * the change: which elements a caller may modify and the versioning of every edit.
 */
@Service
public class PatientService {
  /** Elements the server owns: nobody changes them through C5 (MRN, merge state, merge links). */
  private static final Set<String> SERVER_MANAGED = Set.of("identifier", "active", "link");

  private static final Set<String> NEVER_COMPARED = Set.of("resourceType", "id", "meta");

  private final PatientRepository patients;
  private final AuditSink audit;
  private final Clock clock;
  private final NationalIdRules nationalIds;

  public PatientService(
      PatientRepository patients, AuditSink audit, Clock clock, NationalIdRules nationalIds) {
    this.patients = patients;
    this.audit = audit;
    this.clock = clock;
    this.nationalIds = nationalIds;
  }

  public Entry read(String id) {
    return patients.find(id).orElseThrow(() -> new ShpException("not-found"));
  }

  /**
   * Replace a patient (C5). {@code decision.fields()} lists the only top-level elements the caller
   * may change (a patient edits {@code telecom, address, contact, communication}); empty means the
   * rule has no field limit (nurse). {@code delegation} (null when the caller does not act for a
   * managed profile) goes on the audit record of a field-rule refusal.
   *
   * @throws ShpException {@code version-conflict} (412) when {@code expectedVersion} is stale;
   *     {@code forbidden} (403) when the change touches an element the caller may not modify;
   *     {@code validation-failed} (400) for a body that is not a valid Patient profile
   */
  public Entry update(
      Principal principal,
      String id,
      long expectedVersion,
      String body,
      AuthzDecision decision,
      ResourceContext.Delegation delegation,
      String clientIp) {
    Entry current = read(id);
    if (current.versionId() != expectedVersion) {
      throw new ShpException("version-conflict");
    }
    Patient incoming = FhirJson.parse(body, Patient.class);
    if (incoming.hasId() && !id.equals(incoming.getIdElement().getIdPart())) {
      throw new ShpException("validation-failed", "Patient.id must match the URL.");
    }
    incoming.setId(id);

    // CLN-30: the CCCD never stays in the resource. A masked value echoed back is "unchanged"; a
    // clear one is checked and turned into encrypted search fields.
    Document nationalId = null;
    List<Identifier> cccd =
        incoming.getIdentifier().stream()
            .filter(i -> NationalIdRules.SYSTEM.equals(i.getSystem()))
            .toList();
    if (cccd.size() > 1) {
      throw new ShpException(
          "validation-failed", "Patient.identifier[cccd] is allowed at most once.");
    }
    incoming.getIdentifier().removeIf(i -> NationalIdRules.SYSTEM.equals(i.getSystem()));
    if (cccd.size() == 1 && !NationalIdRules.isMasked(cccd.get(0))) {
      nationalId =
          nationalIds.accept(principal.roles().contains("nurse"), cccd.get(0), incoming, id);
    }

    Set<String> changed = changedElements(current.resource(), incoming);
    if (!decision.fields().isEmpty() && !decision.fields().containsAll(changed)) {
      auditFieldDenial(principal, id, clientIp, delegation);
      throw new ShpException("forbidden");
    }
    if (!Collections.disjoint(changed, SERVER_MANAGED)) {
      throw new ShpException(
          "validation-failed", "Patient.identifier, active and link are managed by the server.");
    }
    return patients.update(incoming, (int) expectedVersion, principal.subject(), null, nationalId);
  }

  /** Every version of a patient as a FHIR history Bundle, newest first (C7). */
  public Bundle history(String id) {
    read(id);
    List<History> versions = new ArrayList<>(patients.history(id));
    Collections.reverse(versions);
    Bundle bundle = new Bundle();
    bundle.setType(BundleType.HISTORY);
    bundle.setTotal(versions.size());
    for (History h : versions) {
      boolean created = "create".equals(h.operation());
      var entry = bundle.addEntry();
      entry.setFullUrl("Patient/" + id + "/_history/" + h.versionId());
      entry.setResource(FhirJson.parse(h.resource().toJson(), Patient.class));
      entry
          .getRequest()
          .setMethod(created ? HTTPVerb.POST : HTTPVerb.PUT)
          .setUrl(created ? "Patient" : "Patient/" + id);
      entry
          .getResponse()
          .setStatus(created ? "201" : "200")
          .setEtag("W/\"" + h.versionId() + "\"")
          .setLastModified(Date.from(h.changedAt()));
    }
    return bundle;
  }

  /** Top-level elements whose value differs between the stored resource and the new one. */
  private static Set<String> changedElements(Document stored, Patient incoming) {
    Document next = Document.parse(FhirJson.serialize(incoming));
    Set<String> keys = new HashSet<>(stored.keySet());
    keys.addAll(next.keySet());
    keys.removeAll(NEVER_COMPARED);
    Set<String> changed = new TreeSet<>();
    for (String key : keys) {
      if (!Objects.equals(stored.get(key), next.get(key))) {
        changed.add(key);
      }
    }
    return changed;
  }

  /**
   * A field-rule refusal happens after the matrix allowed the request, so the interceptor would log
   * it as a success; record the refusal as the denial it is.
   */
  private void auditFieldDenial(
      Principal principal, String id, String clientIp, ResourceContext.Delegation delegation) {
    audit.emit(
        AuthzAudit.fromDecision(
            clock,
            "clinical",
            principal,
            "Patient",
            "update",
            "Patient/" + id,
            AuthzDecision.deny("field-not-allowed"),
            UUID.fromString(id),
            null,
            clientIp,
            null,
            null,
            null,
            delegation));
  }
}
