package org.shp.scheduling.api;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Schedule;
import org.shp.authz.Principal;
import org.shp.fhir.FhirJson;
import org.shp.fhir.search.Criterion;
import org.shp.fhir.search.PageToken;
import org.shp.fhir.search.PageTokens;
import org.shp.fhir.search.SearchBundles;
import org.shp.fhir.search.SearchParser;
import org.shp.fhir.search.SearchQuery;
import org.shp.scheduling.app.ScheduleService;
import org.shp.scheduling.domain.ScheduleRecord;
import org.shp.scheduling.domain.SpecialtyCatalog;
import org.shp.web.auth.IdentityInterceptor;
import org.shp.web.authz.RequireShpAuthz;
import org.shp.web.errors.ShpException;
import org.shp.web.precondition.ETags;
import org.shp.web.precondition.PreconditionInterceptor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** FHIR REST API controller for doctor work schedules (S1–S4). */
@RestController
public class ScheduleController {

  private static final MediaType FHIR_MEDIA_TYPE = MediaType.parseMediaType(FhirJson.MEDIA_TYPE);

  /** Maximum {@code _count} per 07-api-conventions section 4. */
  private static final int MAX_COUNT = 100;

  private static final String RESOURCE_TYPE = "Schedule";
  private static final String KEY_ID = "id";
  private static final SearchParser SEARCH_PARSER = SearchParser.standard();

  private final ScheduleService service;
  private final PageTokens pageTokens;

  public ScheduleController(ScheduleService service, PageTokens pageTokens) {
    this.service = Objects.requireNonNull(service, "service must not be null");
    this.pageTokens = Objects.requireNonNull(pageTokens, "pageTokens must not be null");
  }

  /** S1: POST /fhir/Schedule - Create a doctor's work schedule. Permissions: Nurse, Admin. */
  @PostMapping(
      value = "/fhir/Schedule",
      consumes = {FhirJson.MEDIA_TYPE, MediaType.APPLICATION_JSON_VALUE},
      produces = {FhirJson.MEDIA_TYPE, MediaType.APPLICATION_JSON_VALUE})
  @RequireShpAuthz(resource = "Schedule", action = "create")
  public ResponseEntity<String> createSchedule(
      @RequestBody String body, HttpServletRequest request) {
    Schedule schedule = FhirJson.parse(body, Schedule.class);
    Principal principal = IdentityInterceptor.principal(request);
    String changedBy = principal.subject() != null ? principal.subject() : "unknown";

    ScheduleRecord created = service.createSchedule(schedule, changedBy);
    String location = "/fhir/Schedule/" + created.id() + "/_history/" + created.versionId();

    return ResponseEntity.created(URI.create(location))
        .contentType(FHIR_MEDIA_TYPE)
        .header(HttpHeaders.ETAG, ETags.format(created.versionId()))
        .header(HttpHeaders.LOCATION, location)
        .body(created.content());
  }

  /**
   * S2: GET /fhir/Schedule/{id} - Read a schedule. Permissions: Nurse, Admin; Doctor (own schedule
   * only).
   */
  @GetMapping(
      value = "/fhir/Schedule/{id}",
      produces = {FhirJson.MEDIA_TYPE, MediaType.APPLICATION_JSON_VALUE})
  @RequireShpAuthz(resource = "Schedule", action = "read", context = "scheduleFacts")
  public ResponseEntity<String> getSchedule(@PathVariable String id) {
    UUID uuid = parseUuid(id);
    ScheduleRecord record = service.getSchedule(uuid);

    return ResponseEntity.ok()
        .contentType(FHIR_MEDIA_TYPE)
        .header(HttpHeaders.ETAG, ETags.format(record.versionId()))
        .body(record.content());
  }

  /**
   * S3: GET /fhir/Schedule - Find schedules. Permissions: Nurse, Admin; Doctor (own schedule only).
   * If a doctor calls without their own actor, the server adds actor=&lt;self&gt;.
   *
   * <p>Supported parameters: actor, specialty, active, _count (1..100), _page_token. Anything else
   * (an unknown parameter or a modifier such as {@code specialty:exact}) is 400
   * unsupported-parameter. Pages are keyset-paged on the schedule id; {@code Bundle.link[next]}
   * carries the signed opaque {@code _page_token}.
   */
  @GetMapping(
      value = "/fhir/Schedule",
      produces = {FhirJson.MEDIA_TYPE, MediaType.APPLICATION_JSON_VALUE})
  @RequireShpAuthz(resource = "Schedule", action = "search")
  public ResponseEntity<String> searchSchedules(HttpServletRequest request) {
    Map<String, List<String>> params = SearchParser.fromServlet(request.getParameterMap());
    SearchQuery query = SEARCH_PARSER.parse(RESOURCE_TYPE, params);
    // The parser clamps _count above the maximum; 07 section 4 says outside 1..100 is a 400.
    if (params.containsKey("_count")
        && Integer.parseInt(params.get("_count").getFirst()) > MAX_COUNT) {
      throw new ShpException("validation-failed");
    }

    UUID actor = singleActor(query);
    String specialty = singleSpecialty(query);
    Boolean active = singleActive(query);
    Principal principal = IdentityInterceptor.principal(request);
    UUID targetPractitionerId = actor;

    if (principal.roles().contains("doctor")) {
      String doctorFhirUserId = principal.fhirUserId();
      if (doctorFhirUserId == null) {
        throw new ShpException("forbidden");
      }
      if (actor != null) {
        if (!actor.toString().equalsIgnoreCase(doctorFhirUserId)) {
          throw new ShpException("forbidden");
        }
      } else {
        targetPractitionerId = UUID.fromString(doctorFhirUserId);
      }
    }

    UUID afterId =
        query.pageToken() == null ? null : afterId(pageTokens.decode(query.pageToken(), query));

    // One extra row tells whether a next page exists.
    var records =
        service.searchSchedules(
            targetPractitionerId, specialty, active, query.count() + 1, afterId);
    boolean hasNext = records.size() > query.count();
    var page = hasNext ? records.subList(0, query.count()) : records;
    String nextToken =
        hasNext
            ? pageTokens.encode(
                query, List.of(new PageToken.Key(KEY_ID, page.getLast().id().toString())))
            : null;

    List<Schedule> resources = new ArrayList<>();
    for (var r : page) {
      resources.add(FhirJson.parse(r.content(), Schedule.class));
    }
    Bundle bundle =
        SearchBundles.searchset(
            request.getRequestURL().toString(), params, withIds(resources, page), nextToken);
    return ResponseEntity.ok().contentType(FHIR_MEDIA_TYPE).body(FhirJson.serialize(bundle));
  }

  private static List<Schedule> withIds(List<Schedule> resources, List<ScheduleRecord> page) {
    // SearchBundles takes the entry URL from the resource id; stored content carries
    // "Schedule/<id>".
    for (int i = 0; i < resources.size(); i++) {
      resources.get(i).setId(page.get(i).id().toString());
    }
    return resources;
  }

  private static UUID afterId(PageToken token) {
    if (token.keyset().size() != 1 || !KEY_ID.equals(token.keyset().getFirst().name())) {
      throw new ShpException("validation-failed");
    }
    try {
      return UUID.fromString(token.keyset().getFirst().value());
    } catch (IllegalArgumentException e) {
      throw new ShpException("validation-failed");
    }
  }

  /**
   * At most one value per search parameter: repeated parameters or comma lists are not supported.
   */
  private static Criterion only(SearchQuery query, String param) {
    List<Criterion> all = query.criteria(param);
    if (all.size() > 1) {
      throw new ShpException("validation-failed");
    }
    return all.isEmpty() ? null : all.getFirst();
  }

  private static UUID singleActor(SearchQuery query) {
    if (only(query, "actor") instanceof Criterion.ReferenceCriterion c) {
      if (c.anyOfIds().size() != 1) {
        throw new ShpException("validation-failed");
      }
      return ScheduleService.parsePractitionerId(c.anyOfIds().getFirst());
    }
    return null;
  }

  private static String singleSpecialty(SearchQuery query) {
    if (only(query, "specialty") instanceof Criterion.TokenCriterion c) {
      Criterion.Token token = singleToken(c);
      if (token.code() == null
          || (token.system() != null && !token.system().equals(SpecialtyCatalog.SYSTEM))) {
        throw new ShpException("validation-failed");
      }
      return token.code().strip().toLowerCase(Locale.ROOT);
    }
    return null;
  }

  private static Boolean singleActive(SearchQuery query) {
    if (only(query, "active") instanceof Criterion.TokenCriterion c) {
      Criterion.Token token = singleToken(c);
      if (token.system() != null || token.code() == null) {
        throw new ShpException("validation-failed");
      }
      return switch (token.code()) {
        case "true" -> Boolean.TRUE;
        case "false" -> Boolean.FALSE;
        default -> throw new ShpException("validation-failed");
      };
    }
    return null;
  }

  private static Criterion.Token singleToken(Criterion.TokenCriterion criterion) {
    if (criterion.anyOf().size() != 1) {
      throw new ShpException("validation-failed");
    }
    return criterion.anyOf().getFirst();
  }

  /**
   * S4: PUT /fhir/Schedule/{id} - Edit a schedule. Permissions: Nurse, Admin. Requires If-Match
   * header.
   */
  @PutMapping(
      value = "/fhir/Schedule/{id}",
      consumes = {FhirJson.MEDIA_TYPE, MediaType.APPLICATION_JSON_VALUE},
      produces = {FhirJson.MEDIA_TYPE, MediaType.APPLICATION_JSON_VALUE})
  @RequireShpAuthz(resource = "Schedule", action = "update", context = "scheduleFacts")
  public ResponseEntity<String> updateSchedule(
      @PathVariable String id, @RequestBody String body, HttpServletRequest request) {
    UUID uuid = parseUuid(id);
    long expectedVersion = PreconditionInterceptor.expectedVersion(request);
    Schedule schedule = FhirJson.parse(body, Schedule.class);

    Principal principal = IdentityInterceptor.principal(request);
    String changedBy = principal.subject() != null ? principal.subject() : "unknown";

    ScheduleRecord updated = service.updateSchedule(uuid, schedule, expectedVersion, changedBy);
    String location = "/fhir/Schedule/" + updated.id() + "/_history/" + updated.versionId();

    return ResponseEntity.ok()
        .contentType(FHIR_MEDIA_TYPE)
        .header(HttpHeaders.ETAG, ETags.format(updated.versionId()))
        .header(HttpHeaders.LOCATION, location)
        .body(updated.content());
  }

  private static UUID parseUuid(String id) {
    try {
      return UUID.fromString(id);
    } catch (IllegalArgumentException e) {
      throw new ShpException("not-found");
    }
  }
}
