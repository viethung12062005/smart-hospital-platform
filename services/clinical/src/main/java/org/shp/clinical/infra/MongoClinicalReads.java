package org.shp.clinical.infra;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.bson.Document;
import org.shp.clinical.app.ClinicalReads;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

/**
 * Reads the clinical collections of 03 §2.2 for the internal API. Resource collections hold the
 * {@code MongoResourceStore} shape ({@code _id, resource, search, deleted}); deleted documents are
 * never returned.
 */
@Repository
public class MongoClinicalReads implements ClinicalReads {
  public static final String PATIENTS = "patients";
  public static final String PRACTITIONERS = "practitioners";
  public static final String PRACTITIONER_ROLES = "practitioner_roles";
  public static final String CARE_RELATIONSHIPS = "care_relationships";
  public static final String BREAK_GLASS_SESSIONS = "break_glass_sessions";

  private final MongoTemplate mongo;

  public MongoClinicalReads(MongoTemplate mongo) {
    this.mongo = mongo;
  }

  @Override
  public Optional<PatientFacts> patient(String patientId) {
    return live(patientId, PATIENTS)
        .map(d -> d.get("resource", Document.class))
        .map(MongoClinicalReads::toFacts);
  }

  @Override
  public boolean practitionerExists(String practitionerId) {
    return live(practitionerId, PRACTITIONERS).isPresent();
  }

  @Override
  public List<RoleView> roles(String practitionerId) {
    Query q =
        Query.query(
                Criteria.where("search.practitionerId").is(practitionerId).and("deleted").ne(true))
            .with(Sort.by("_id"));
    return mongo.find(q, Document.class, PRACTITIONER_ROLES).stream()
        .map(MongoClinicalReads::toRole)
        .toList();
  }

  @Override
  public Optional<CareRow> careRelationship(String practitionerId, String patientId, Instant now) {
    Query q =
        Query.query(
            Criteria.where("_id")
                .is(careId(practitionerId, patientId))
                .and("validUntil")
                .gt(Date.from(now)));
    return Optional.ofNullable(mongo.findOne(q, Document.class, CARE_RELATIONSHIPS))
        .map(MongoClinicalReads::toCare);
  }

  @Override
  public Optional<Instant> breakGlass(String practitionerId, String patientId, Instant now) {
    Query q =
        Query.query(
                Criteria.where("practitionerId")
                    .is(practitionerId)
                    .and("patientId")
                    .is(patientId)
                    .and("expiresAt")
                    .gt(Date.from(now)))
            .with(Sort.by(Sort.Direction.DESC, "expiresAt"))
            .limit(1);
    return Optional.ofNullable(mongo.findOne(q, Document.class, BREAK_GLASS_SESSIONS))
        .map(d -> d.getDate("expiresAt").toInstant());
  }

  /** {@code _id} of {@code care_relationships} (03 §2.2). */
  public static String careId(String practitionerId, String patientId) {
    return practitionerId + "|" + patientId;
  }

  private Optional<Document> live(String id, String collection) {
    Query q = Query.query(Criteria.where("_id").is(id).and("deleted").ne(true));
    return Optional.ofNullable(mongo.findOne(q, Document.class, collection));
  }

  static PatientFacts toFacts(Document resource) {
    LocalDate birthDate = null;
    String text = resource.getString("birthDate");
    if (text != null) {
      try {
        birthDate = LocalDate.parse(text);
      } catch (DateTimeParseException e) {
        birthDate = null; // partial date: the age band is unknown
      }
    }
    return new PatientFacts(
        birthDate, telecom(resource, "phone"), telecom(resource, "email"), language(resource));
  }

  private static String telecom(Document resource, String system) {
    return resource.getList("telecom", Document.class, List.of()).stream()
        .filter(t -> system.equals(t.getString("system")))
        .map(t -> t.getString("value"))
        .filter(Objects::nonNull)
        .findFirst()
        .orElse(null);
  }

  private static String language(Document resource) {
    return resource.getList("communication", Document.class, List.of()).stream()
        .map(c -> c.get("language", Document.class))
        .filter(Objects::nonNull)
        .flatMap(l -> l.getList("coding", Document.class, List.of()).stream())
        .map(c -> c.getString("code"))
        .filter(Objects::nonNull)
        .findFirst()
        .orElse(null);
  }

  static RoleView toRole(Document d) {
    Document search = d.get("search", Document.class);
    return new RoleView(
        d.getString("_id"),
        search.getString("specialty"),
        search.getString("code"),
        Boolean.TRUE.equals(search.getBoolean("active")));
  }

  /** {@code via} is the type of the most recent source that is not cancelled. */
  static CareRow toCare(Document d) {
    String via =
        d.getList("sources", Document.class, List.of()).stream()
            .filter(s -> !"cancelled".equals(s.getString("status")))
            .filter(s -> s.getDate("at") != null)
            .max(Comparator.comparing(s -> s.getDate("at")))
            .map(s -> s.getString("type"))
            .orElse("appointment");
    return new CareRow(d.getDate("validUntil").toInstant(), via);
  }
}
