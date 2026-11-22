package org.shp.clinical.app;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bson.Document;
import org.hl7.fhir.r4.model.DateType;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Patient;
import org.shp.clinical.domain.PatientNormalizer;
import org.shp.data.mongo.search.MongoSearchExecutor;
import org.shp.fhir.FhirJson;
import org.shp.fhir.search.SearchParser;
import org.shp.fhir.search.SearchQuery;
import org.shp.web.errors.ShpException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

/**
 * 09 C6 patient search (US-031; AC-06.1, AC-06.2): by name without diacritics (last token by
 * prefix), phone (E.164 after normalization), MRN or birth date, through the shared executor
 * (SHR-11). Every result is SHORTENED to id, MRN, name, birth date and gender; a doctor without
 * care with that patient sees only the birth YEAR (and still gets 403 when opening the record, C4).
 */
@Service
public class PatientSearchService {
  static final Set<String> SEARCH_PARAMS = Set.of("name", "phone", "identifier", "birthdate");
  static final String MRN_SYSTEM = "urn:shp:mrn";
  static final int MIN_NAME = 2;

  private final MongoTemplate mongo;
  private final MongoSearchExecutor executor;
  private final SearchParser parser = SearchParser.standard();
  private final Clock clock;

  public PatientSearchService(MongoTemplate mongo, Clock clock) {
    this.mongo = mongo;
    this.executor = new MongoSearchExecutor(mongo);
    this.clock = clock;
  }

  /**
   * @param doctorId the caller's Practitioner id when the caller is a doctor (birth year rule),
   *     else {@code null}
   */
  public List<Patient> search(Map<String, List<String>> raw, String doctorId) {
    if (raw.containsKey("_page_token")) {
      // The page-token signing key is not configured yet (#641).
      throw new ShpException("unsupported-parameter", "_page_token is not supported yet.");
    }
    if (SEARCH_PARAMS.stream().noneMatch(raw::containsKey)) {
      throw new ShpException(
          "validation-failed", "At least one of name, phone, identifier or birthdate is required.");
    }
    Map<String, List<String>> params = new LinkedHashMap<>(raw);
    for (String name : params.getOrDefault("name", List.of())) {
      if (name == null || name.strip().length() < MIN_NAME) {
        throw new ShpException("validation-failed", "name must be at least 2 characters.");
      }
    }
    if (params.containsKey("phone")) {
      List<String> normalized = new ArrayList<>();
      for (String phone : params.get("phone")) {
        PatientNormalizer.normalizePhone(phone).ifPresent(normalized::add);
      }
      if (normalized.isEmpty()) {
        return List.of(); // not a Vietnamese number: it cannot match a stored E.164 number
      }
      params.put("phone", normalized);
    }
    String birth = birthDate(params.remove("birthdate"));
    SearchQuery query = parser.parse("Patient", params);
    List<Document> found;
    if (birth == null) {
      Query q = executor.plan(query, null).query();
      q.addCriteria(Criteria.where("deleted").ne(true));
      found = mongo.find(q, Document.class, "patients").stream().limit(query.count()).toList();
    } else {
      // CLN-03 stores search.birthDate as the FHIR string yyyy-MM-dd (the CLN-04 duplicate check
      // compares strings), while the executor compares dates: match the day as a string here.
      Query q =
          params.keySet().stream().anyMatch(SEARCH_PARAMS::contains)
              ? executor.plan(query, null).query()
              : new Query().limit(query.count());
      q.addCriteria(Criteria.where("search.birthDate").is(birth));
      q.addCriteria(Criteria.where("deleted").ne(true));
      found = mongo.find(q, Document.class, "patients").stream().limit(query.count()).toList();
    }
    Set<String> careFor = doctorId == null ? Set.of() : careWith(doctorId, found);
    return found.stream()
        .map(d -> shorten(d, doctorId != null && !careFor.contains(d.getString("_id"))))
        .toList();
  }

  /** C6 {@code birthdate}: one day, {@code yyyy-MM-dd} or {@code eqyyyy-MM-dd}; null if absent. */
  static String birthDate(List<String> values) {
    if (values == null || values.isEmpty()) {
      return null;
    }
    String v = values.size() == 1 ? values.get(0) : null;
    if (v != null && v.startsWith("eq")) {
      v = v.substring(2);
    }
    if (v == null || !v.matches("^\\d{4}-\\d{2}-\\d{2}$")) {
      throw new ShpException("validation-failed", "birthdate must be one day (yyyy-MM-dd).");
    }
    try {
      java.time.LocalDate.parse(v);
    } catch (java.time.format.DateTimeParseException e) {
      throw new ShpException("validation-failed", "birthdate must be one day (yyyy-MM-dd).");
    }
    return v;
  }

  /** The patients among {@code found} the doctor has a valid care relationship with (15 §4). */
  private Set<String> careWith(String doctorId, List<Document> found) {
    if (found.isEmpty()) {
      return Set.of();
    }
    List<String> ids = found.stream().map(d -> doctorId + "|" + d.getString("_id")).toList();
    Set<String> out = new HashSet<>();
    mongo
        .find(
            Query.query(
                Criteria.where("_id").in(ids).and("validUntil").gt(Date.from(clock.instant()))),
            Document.class,
            "care_relationships")
        .forEach(d -> out.add(d.getString("patientId")));
    return out;
  }

  /** C6: id, identifier[mrn], name, birthDate (year only when {@code yearOnly}), gender. */
  static Patient shorten(Document stored, boolean yearOnly) {
    Patient full = FhirJson.parse(stored.get("resource", Document.class).toJson(), Patient.class);
    Patient p = new Patient();
    p.setId(stored.getString("_id"));
    for (Identifier i : full.getIdentifier()) {
      if (MRN_SYSTEM.equals(i.getSystem())) {
        p.addIdentifier().setSystem(MRN_SYSTEM).setValue(i.getValue());
      }
    }
    if (full.hasName()) {
      p.addName(full.getNameFirstRep().copy());
    }
    if (full.hasBirthDateElement() && full.getBirthDateElement().getValueAsString() != null) {
      String birth = full.getBirthDateElement().getValueAsString();
      p.setBirthDateElement(new DateType(yearOnly ? birth.substring(0, 4) : birth));
    }
    if (full.hasGender()) {
      p.setGender(full.getGender());
    }
    return p;
  }
}
