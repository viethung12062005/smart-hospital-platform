package org.shp.scheduling.api;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.hl7.fhir.r4.model.Appointment;
import org.hl7.fhir.r4.model.Bundle;
import org.shp.authz.Principal;
import org.shp.fhir.FhirJson;
import org.shp.fhir.search.Criterion;
import org.shp.fhir.search.PageToken;
import org.shp.fhir.search.PageTokens;
import org.shp.fhir.search.SearchBundles;
import org.shp.fhir.search.SearchParser;
import org.shp.fhir.search.SearchQuery;
import org.shp.scheduling.domain.AppointmentSearchCriteria;
import org.shp.scheduling.domain.AppointmentSearchCriteria.AppointmentRow;
import org.shp.scheduling.infra.AppointmentSearchRepository;
import org.shp.web.auth.IdentityInterceptor;
import org.shp.web.authz.RequireShpAuthz;
import org.shp.web.errors.ShpException;
import org.shp.web.precondition.ETags;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * S12 {@code GET /fhir/Appointment/{id}} and S13 {@code GET /fhir/Appointment}: read and search
 * appointments with role-forced filters, 92-day max date range, and keyset pagination (08 §4).
 */
@RestController
public class AppointmentController {

  private static final MediaType FHIR_MEDIA_TYPE = MediaType.parseMediaType(FhirJson.MEDIA_TYPE);
  private static final String RESOURCE_TYPE = "Appointment";
  private static final String KEY_START_ASC = "start_asc";
  private static final String KEY_START_DESC = "start_desc";
  private static final String KEY_ID = "id";
  private static final int MAX_COUNT = 100;
  private static final Duration MAX_DATE_RANGE = Duration.ofDays(92);
  private static final SearchParser SEARCH_PARSER = SearchParser.standard();

  private final AppointmentSearchRepository repository;
  private final PageTokens pageTokens;

  public AppointmentController(
      AppointmentSearchRepository repository,
      ObjectProvider<PageTokens> pageTokensProvider,
      @Value("${shp.paging.token-key:}") String configuredKey) {
    this.repository = Objects.requireNonNull(repository, "repository must not be null");
    PageTokens existing = pageTokensProvider.getIfAvailable();
    if (existing != null) {
      this.pageTokens = existing;
    } else if (configuredKey != null && !configuredKey.isBlank()) {
      this.pageTokens = new PageTokens(configuredKey.getBytes(StandardCharsets.UTF_8));
    } else {
      byte[] key = new byte[PageTokens.MIN_KEY_BYTES];
      new SecureRandom().nextBytes(key);
      this.pageTokens = new PageTokens(key);
    }
  }

  /** S12: Read a single Appointment by ID. Permissions: Nurse; Patient (own); Doctor (own). */
  @GetMapping(
      value = "/fhir/Appointment/{id}",
      produces = {FhirJson.MEDIA_TYPE, MediaType.APPLICATION_JSON_VALUE})
  @RequireShpAuthz(resource = "Appointment", action = "read", context = "appointmentReadFacts")
  public ResponseEntity<String> getAppointment(
      @PathVariable String id, HttpServletRequest request) {
    AppointmentRow row;
    if (request.getAttribute(AppointmentFactsResolver.ATTR_APPOINTMENT)
        instanceof AppointmentRow cached) {
      row = cached;
    } else {
      UUID uuid = parseUuidOrNotFound(id);
      row = repository.findById(uuid).orElseThrow(() -> new ShpException("not-found"));
    }
    return ResponseEntity.ok()
        .contentType(FHIR_MEDIA_TYPE)
        .header(HttpHeaders.ETAG, ETags.format(row.versionId()))
        .body(row.content());
  }

  /**
   * S13: Search Appointments with role-forced filters, 92-day max date range, and keyset
   * pagination. Permissions: Nurse (at least one of patient, practitioner, date); Doctor
   * (practitioner forced to self); Patient (patient forced to self).
   */
  @GetMapping(
      value = "/fhir/Appointment",
      produces = {FhirJson.MEDIA_TYPE, MediaType.APPLICATION_JSON_VALUE})
  @RequireShpAuthz(resource = "Appointment", action = "read", context = "appointmentSearchFacts")
  public ResponseEntity<String> searchAppointments(HttpServletRequest request) {
    Map<String, List<String>> rawParams = SearchParser.fromServlet(request.getParameterMap());
    boolean descending = parseSort(rawParams.get("_sort"));

    Map<String, List<String>> parserParams = new LinkedHashMap<>(rawParams);
    parserParams.remove("_sort");
    SearchQuery query = SEARCH_PARSER.parse(RESOURCE_TYPE, parserParams);
    if (rawParams.containsKey("_count")
        && Integer.parseInt(rawParams.get("_count").getFirst()) > MAX_COUNT) {
      throw new ShpException("validation-failed");
    }

    UUID patientId = singleReference(query, "patient");
    UUID practitionerId = singleReference(query, "practitioner");
    Set<String> statuses = parseStatuses(query);
    String specialty = singleSpecialty(query);

    Instant from = null;
    Instant to = null;
    for (Criterion c : query.criteria("date")) {
      if (c instanceof Criterion.DateCriterion d) {
        if (d.from() != null && (from == null || d.from().isAfter(from))) {
          from = d.from();
        }
        if (d.to() != null && (to == null || d.to().isBefore(to))) {
          to = d.to();
        }
      }
    }

    Principal principal = IdentityInterceptor.principal(request);
    boolean nurse = principal.roles().contains("nurse");
    boolean doctor = principal.roles().contains("doctor");
    boolean patient = principal.roles().contains("patient");
    if (!nurse) {
      if (doctor) {
        practitionerId = parsePrincipalFhirUserId(principal);
      } else if (patient) {
        patientId = parsePrincipalFhirUserId(principal);
      }
    }

    if (patientId == null && practitionerId == null && query.criteria("date").isEmpty()) {
      throw new ShpException("validation-failed");
    }

    if (from != null && to != null) {
      Duration span = Duration.between(from, to);
      if (span.compareTo(MAX_DATE_RANGE) > 0 || !to.isAfter(from)) {
        throw new ShpException("validation-failed");
      }
    } else if (patientId == null && practitionerId == null) {
      // Nurse searching only by date must provide a bounded interval <= 92 days.
      throw new ShpException("validation-failed");
    }

    SearchQuery effectiveQuery =
        buildEffectiveQuery(query, patientId, practitionerId, nurse, doctor, patient);
    String expectedSortKey = descending ? KEY_START_DESC : KEY_START_ASC;

    Instant afterStart = null;
    UUID afterId = null;
    if (query.pageToken() != null) {
      PageToken token = pageTokens.decode(query.pageToken(), effectiveQuery);
      if (token.keyset().size() != 2
          || !expectedSortKey.equals(token.keyset().get(0).name())
          || !KEY_ID.equals(token.keyset().get(1).name())) {
        throw new ShpException("validation-failed");
      }
      try {
        afterStart = Instant.parse(token.keyset().get(0).value());
        afterId = UUID.fromString(token.keyset().get(1).value());
      } catch (RuntimeException e) {
        throw new ShpException("validation-failed");
      }
    }

    List<AppointmentRow> rows =
        repository.search(
            new AppointmentSearchCriteria(
                patientId,
                practitionerId,
                statuses,
                specialty,
                from,
                to,
                descending,
                afterStart,
                afterId,
                query.count() + 1));

    boolean hasNext = rows.size() > query.count();
    List<AppointmentRow> page = hasNext ? rows.subList(0, query.count()) : rows;
    String nextToken = null;
    if (hasNext) {
      AppointmentRow last = page.getLast();
      nextToken =
          pageTokens.encode(
              effectiveQuery,
              List.of(
                  new PageToken.Key(expectedSortKey, last.startAt().toString()),
                  new PageToken.Key(KEY_ID, last.id().toString())));
    }

    List<Appointment> resources = new ArrayList<>(page.size());
    for (AppointmentRow r : page) {
      Appointment appt = FhirJson.parse(r.content(), Appointment.class);
      appt.setId(r.id().toString());
      resources.add(appt);
    }

    Bundle bundle =
        SearchBundles.searchset(
            request.getRequestURL().toString(), rawParams, resources, nextToken);
    return ResponseEntity.ok().contentType(FHIR_MEDIA_TYPE).body(FhirJson.serialize(bundle));
  }

  private static boolean parseSort(List<String> sortValues) {
    if (sortValues == null) {
      return false;
    }
    if (sortValues.size() != 1 || sortValues.getFirst() == null) {
      throw new ShpException("validation-failed");
    }
    return switch (sortValues.getFirst()) {
      case "date" -> false;
      case "-date" -> true;
      default -> throw new ShpException("validation-failed");
    };
  }

  private static Set<String> parseStatuses(SearchQuery query) {
    List<Criterion> all = query.criteria("status");
    if (all.size() > 1) {
      throw new ShpException("validation-failed");
    }
    if (all.isEmpty()) {
      return Set.of();
    }
    Set<String> result = new LinkedHashSet<>();
    for (Criterion.Token token : ((Criterion.TokenCriterion) all.getFirst()).anyOf()) {
      if (token.code() == null
          || token.system() != null
          || !AppointmentSearchCriteria.ALLOWED_STATUSES.contains(token.code())) {
        throw new ShpException("validation-failed");
      }
      result.add(token.code());
    }
    return result;
  }

  private static String singleSpecialty(SearchQuery query) {
    List<Criterion> all = query.criteria("specialty");
    if (all.size() > 1) {
      throw new ShpException("validation-failed");
    }
    if (all.isEmpty()) {
      return null;
    }
    var criterion = (Criterion.TokenCriterion) all.getFirst();
    if (criterion.anyOf().size() != 1) {
      throw new ShpException("validation-failed");
    }
    Criterion.Token token = criterion.anyOf().getFirst();
    if (token.code() == null
        || (token.system() != null
            && !token.system().equals(AppointmentSearchCriteria.SPECIALTY_SYSTEM))) {
      throw new ShpException("validation-failed");
    }
    String normalized = token.code().strip().toLowerCase(Locale.ROOT);
    if (!AppointmentSearchCriteria.ALLOWED_SPECIALTIES.contains(normalized)) {
      throw new ShpException("validation-failed");
    }
    return normalized;
  }

  private static UUID singleReference(SearchQuery query, String param) {
    List<Criterion> all = query.criteria(param);
    if (all.size() > 1) {
      throw new ShpException("validation-failed");
    }
    if (all.isEmpty()) {
      return null;
    }
    var criterion = (Criterion.ReferenceCriterion) all.getFirst();
    if (criterion.anyOfIds().size() != 1) {
      throw new ShpException("validation-failed");
    }
    return parseUuidOrValidationFailed(criterion.anyOfIds().getFirst());
  }

  private static SearchQuery buildEffectiveQuery(
      SearchQuery query,
      UUID patientId,
      UUID practitionerId,
      boolean nurse,
      boolean doctor,
      boolean patient) {
    List<Criterion> effective = new ArrayList<>();
    if (!nurse && patient && patientId != null) {
      effective.add(
          new Criterion.ReferenceCriterion("patient", "Patient", List.of(patientId.toString())));
    }
    if (!nurse && doctor && practitionerId != null) {
      effective.add(
          new Criterion.ReferenceCriterion(
              "practitioner", "Practitioner", List.of(practitionerId.toString())));
    }
    for (Criterion c : query.criteria()) {
      if (!nurse && patient && "patient".equals(c.param())) {
        continue;
      }
      if (!nurse && doctor && "practitioner".equals(c.param())) {
        continue;
      }
      effective.add(c);
    }
    return new SearchQuery(RESOURCE_TYPE, effective, query.count(), query.pageToken());
  }

  private static UUID parsePrincipalFhirUserId(Principal principal) {
    try {
      return UUID.fromString(principal.fhirUserId());
    } catch (RuntimeException e) {
      throw new ShpException("forbidden");
    }
  }

  private static UUID parseUuidOrValidationFailed(String rawId) {
    try {
      return UUID.fromString(rawId);
    } catch (IllegalArgumentException e) {
      throw new ShpException("validation-failed");
    }
  }

  private static UUID parseUuidOrNotFound(String rawId) {
    try {
      return UUID.fromString(rawId);
    } catch (IllegalArgumentException e) {
      throw new ShpException("not-found");
    }
  }
}
