package org.shp.clinical.app;

import java.text.Normalizer;
import java.time.Clock;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import org.bson.Document;
import org.hl7.fhir.r4.model.Practitioner;
import org.hl7.fhir.r4.model.PractitionerRole;
import org.hl7.fhir.r4.model.Resource;
import org.shp.clinical.infra.PractitionerRepository;
import org.shp.data.mongo.MongoResourceStore.Entry;
import org.shp.events.Uuids;
import org.shp.fhir.FhirJson;
import org.shp.web.errors.ShpException;
import org.springframework.stereotype.Service;

/**
 * Doctor directory and staff management (09 C9, C10; US-006). Writes are Admin-only (enforced at
 * the API) and must match the profile of 01 §4.2; the staff code {@code NV-NNNN} is entered by the
 * Admin and must be unique. The directory is readable by every signed-in user, so it never returns
 * contact details ({@code telecom}).
 */
@Service
public class PractitionerService {
  static final String PRACTITIONER = "Practitioner";
  static final String ROLE = "PractitionerRole";

  /** A stored resource with its version, for the ETag and Location headers. */
  public record Stored<T extends Resource>(T resource, int version) {}

  private final PractitionerRepository repository;
  private final ProfileValidator validator;
  private final Clock clock;

  public PractitionerService(
      PractitionerRepository repository, ProfileValidator validator, Clock clock) {
    this.repository = repository;
    this.validator = validator;
    this.clock = clock;
  }

  // ---------- Practitioner ----------

  public Stored<Practitioner> createPractitioner(String body, String changedBy) {
    Practitioner p = FhirJson.parse(body, Practitioner.class);
    String id = Uuids.v7(clock).toString();
    prepare(p, id);
    validator.validate(p);
    String staffCode = p.getIdentifierFirstRep().getValue();
    if (repository.staffCodeTaken(staffCode, null)) {
      throw new ShpException(
          "validation-failed", "Practitioner.identifier[staff] is already in use.");
    }
    Entry e = repository.create(PRACTITIONER, id, toDocument(p), search(p), changedBy);
    return stored(e, Practitioner.class);
  }

  public Stored<Practitioner> updatePractitioner(
      String id, String body, int expectedVersion, String changedBy) {
    Practitioner p = FhirJson.parse(body, Practitioner.class);
    requireSameId(p, id, PRACTITIONER);
    prepare(p, id);
    validator.validate(p);
    if (repository.staffCodeTaken(p.getIdentifierFirstRep().getValue(), id)) {
      throw new ShpException(
          "validation-failed", "Practitioner.identifier[staff] is already in use.");
    }
    Entry e =
        repository.update(PRACTITIONER, id, toDocument(p), search(p), expectedVersion, changedBy);
    return stored(e, Practitioner.class);
  }

  /** One directory entry, without contact details; inactive staff are still readable by id. */
  public Stored<Practitioner> readPractitioner(String id) {
    Entry e = repository.find(PRACTITIONER, id).orElseThrow(() -> new ShpException("not-found"));
    Stored<Practitioner> s = stored(e, Practitioner.class);
    s.resource().setTelecom(null);
    return s;
  }

  /** Active practitioners for the directory, without contact details. */
  public List<Practitioner> directory(int count) {
    return repository.listActive(PRACTITIONER, count).stream()
        .map(e -> stored(e, Practitioner.class).resource().setTelecom(null))
        .toList();
  }

  // ---------- PractitionerRole ----------

  public Stored<PractitionerRole> createRole(String body, String changedBy) {
    PractitionerRole r = FhirJson.parse(body, PractitionerRole.class);
    String id = Uuids.v7(clock).toString();
    prepare(r, id);
    validator.validate(r);
    requirePractitioner(r);
    Entry e = repository.create(ROLE, id, toDocument(r), search(r), changedBy);
    return stored(e, PractitionerRole.class);
  }

  public Stored<PractitionerRole> updateRole(
      String id, String body, int expectedVersion, String changedBy) {
    PractitionerRole r = FhirJson.parse(body, PractitionerRole.class);
    requireSameId(r, id, ROLE);
    prepare(r, id);
    validator.validate(r);
    requirePractitioner(r);
    Entry e = repository.update(ROLE, id, toDocument(r), search(r), expectedVersion, changedBy);
    return stored(e, PractitionerRole.class);
  }

  /** Active roles for the directory (who practises which specialty). */
  public List<PractitionerRole> roles(int count) {
    return repository.listActive(ROLE, count).stream()
        .map(e -> stored(e, PractitionerRole.class).resource())
        .toList();
  }

  // ---------- mapping ----------

  /** The server owns {@code id} and {@code meta} (versionId, lastUpdated). */
  private static void prepare(Resource resource, String id) {
    resource.setId(id);
    resource.setMeta(null);
  }

  private static void requireSameId(Resource resource, String id, String type) {
    String bodyId = resource.getIdElement().getIdPart();
    if (bodyId != null && !bodyId.equals(id)) {
      throw new ShpException("validation-failed", type + ".id must match the URL.");
    }
  }

  private void requirePractitioner(PractitionerRole r) {
    String practitionerId = r.getPractitioner().getReferenceElement().getIdPart();
    if (repository.find(PRACTITIONER, practitionerId).isEmpty()) {
      throw new ShpException("validation-failed", "PractitionerRole.practitioner does not exist.");
    }
  }

  private static Document toDocument(Resource resource) {
    return Document.parse(FhirJson.serialize(resource));
  }

  private static <T extends Resource> Stored<T> stored(Entry e, Class<T> type) {
    T resource = FhirJson.parse(e.resource().toJson(), type);
    resource.setId(e.id());
    resource.getMeta().setVersionId(String.valueOf(e.versionId()));
    if (e.lastUpdated() != null) {
      resource.getMeta().setLastUpdated(Date.from(e.lastUpdated()));
    }
    return new Stored<>(resource, e.versionId());
  }

  /** 03 §2.2: {@code practitioners.search}. */
  static Document search(Practitioner p) {
    return new Document("staffCode", p.getIdentifierFirstRep().getValue())
        .append("nameTokens", nameTokens(p.getNameFirstRep().getText()))
        .append("active", p.getActive());
  }

  /** 03 §2.2: {@code practitioner_roles.search}. */
  static Document search(PractitionerRole r) {
    return new Document("practitionerId", r.getPractitioner().getReferenceElement().getIdPart())
        .append("specialty", r.getSpecialtyFirstRep().getCodingFirstRep().getCode())
        .append("code", r.getCodeFirstRep().getCodingFirstRep().getCode())
        .append("active", r.getActive());
  }

  /** Lowercase tokens without diacritics ("Lê Minh Châu" → le, minh, chau), as 03 §2.3. */
  static List<String> nameTokens(String text) {
    String folded =
        Normalizer.normalize(text, Normalizer.Form.NFD)
            .replaceAll("\\p{M}", "")
            .replace('đ', 'd')
            .replace('Đ', 'D')
            .toLowerCase(Locale.ROOT);
    return Arrays.stream(folded.split("[^a-z0-9]+")).filter(t -> !t.isEmpty()).distinct().toList();
  }
}
