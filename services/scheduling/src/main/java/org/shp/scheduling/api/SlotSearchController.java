package org.shp.scheduling.api;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Slot;
import org.shp.authz.Principal;
import org.shp.fhir.FhirJson;
import org.shp.fhir.search.Criterion;
import org.shp.fhir.search.PageToken;
import org.shp.fhir.search.PageTokens;
import org.shp.fhir.search.SearchBundles;
import org.shp.fhir.search.SearchParser;
import org.shp.fhir.search.SearchQuery;
import org.shp.scheduling.domain.SlotRecord;
import org.shp.scheduling.domain.SlotSearchCriteria;
import org.shp.scheduling.domain.SpecialtyCatalog;
import org.shp.scheduling.infra.SlotSearchCache;
import org.shp.web.auth.IdentityInterceptor;
import org.shp.web.authz.RequireShpAuthz;
import org.shp.web.errors.ShpException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * S8 {@code GET /fhir/Slot}: find slots (hot path, NFR-P1). A patient only ever sees {@code free}
 * slots that start at or after now; the filters of 08 section 3 are enforced here, the query itself
 * lives in {@link SlotSearchRepository}.
 */
@RestController
public class SlotSearchController {

  private static final MediaType FHIR_MEDIA_TYPE = MediaType.parseMediaType(FhirJson.MEDIA_TYPE);
  private static final String RESOURCE_TYPE = "Slot";
  private static final String KEY_START = "start";
  private static final String KEY_ID = "id";
  private static final int MAX_COUNT = 100;
  private static final Duration MAX_RANGE = Duration.ofDays(31);
  private static final Duration BOOKING_WINDOW = Duration.ofDays(30);
  private static final SearchParser SEARCH_PARSER = SearchParser.standard();

  private final SlotSearchCache cache;
  private final PageTokens pageTokens;
  private final Clock clock;

  public SlotSearchController(SlotSearchCache cache, PageTokens pageTokens, Clock clock) {
    this.cache = Objects.requireNonNull(cache, "cache must not be null");
    this.pageTokens = Objects.requireNonNull(pageTokens, "pageTokens must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
  }

  @GetMapping(
      value = "/fhir/Slot",
      produces = {FhirJson.MEDIA_TYPE, MediaType.APPLICATION_JSON_VALUE})
  @RequireShpAuthz(resource = "Slot", action = "read")
  public ResponseEntity<String> searchSlots(HttpServletRequest request) {
    Map<String, List<String>> params = SearchParser.fromServlet(request.getParameterMap());
    SearchQuery query = SEARCH_PARSER.parse(RESOURCE_TYPE, params);
    // The parser clamps _count above the maximum; 07 section 4 says outside 1..100 is a 400.
    if (params.containsKey("_count")
        && Integer.parseInt(params.get("_count").getFirst()) > MAX_COUNT) {
      throw new ShpException("validation-failed");
    }

    String specialty = singleSpecialty(query);
    UUID practitionerId = singleReference(query, "practitioner");
    UUID scheduleId = singleReference(query, "schedule");
    if (specialty == null && practitionerId == null && scheduleId == null) {
      throw new ShpException("validation-failed");
    }

    Principal principal = IdentityInterceptor.principal(request);
    boolean patient = principal.roles().contains("patient");
    Set<String> statuses = patient ? Set.of("free") : statuses(query);

    Instant now = clock.instant();
    Instant from = null;
    Instant to = null;
    for (Criterion c : query.criteria("start")) {
      if (c instanceof Criterion.DateCriterion d) {
        if (d.from() != null && (from == null || d.from().isAfter(from))) {
          from = d.from();
        }
        if (d.to() != null && (to == null || d.to().isBefore(to))) {
          to = d.to();
        }
      }
    }
    if (from != null && to != null) {
      Duration span = Duration.between(from, to);
      if (span.compareTo(MAX_RANGE) > 0 || span.isNegative() || span.isZero()) {
        throw new ShpException("validation-failed");
      }
    }
    boolean fromDefaultedNow = false;
    boolean toDefaultedNow = false;
    if (patient && (from == null || from.isBefore(now))) {
      from = now;
      fromDefaultedNow = true;
    }
    // A missing bound is filled so the scanned range is never open-ended.
    if (from != null && to == null) {
      to = from.plus(MAX_RANGE);
      toDefaultedNow = fromDefaultedNow;
    } else if (from == null && to != null) {
      from = to.minus(MAX_RANGE);
    } else if (from == null) {
      from = now;
      to = now.plus(MAX_RANGE);
      fromDefaultedNow = true;
      toDefaultedNow = true;
    }
    // BR-04: a patient can only book up to 30 days ahead, so nothing later is offered.
    if (patient && to.isAfter(now.plus(BOOKING_WINDOW))) {
      to = now.plus(BOOKING_WINDOW);
      toDefaultedNow = true;
    }
    boolean emptyRange = !to.isAfter(from);

    Instant afterStart = null;
    UUID afterId = null;
    if (query.pageToken() != null) {
      PageToken token = pageTokens.decode(query.pageToken(), query);
      if (token.keyset().size() != 2
          || !KEY_START.equals(token.keyset().get(0).name())
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

    // One extra row tells whether a next page exists.
    List<SlotRecord> rows =
        emptyRange
            ? List.of()
            : cache.search(
                new SlotSearchCriteria(
                    statuses,
                    specialty,
                    practitionerId,
                    scheduleId,
                    from,
                    to,
                    afterStart,
                    afterId,
                    query.count() + 1),
                fromDefaultedNow,
                toDefaultedNow,
                now);
    boolean hasNext = rows.size() > query.count();
    List<SlotRecord> page = hasNext ? rows.subList(0, query.count()) : rows;
    String nextToken = null;
    if (hasNext) {
      SlotRecord last = page.getLast();
      nextToken =
          pageTokens.encode(
              query,
              List.of(
                  new PageToken.Key(KEY_START, last.startAt().toString()),
                  new PageToken.Key(KEY_ID, last.id().toString())));
    }

    List<Slot> resources = new ArrayList<>();
    for (SlotRecord r : page) {
      Slot slot = FhirJson.parse(r.content(), Slot.class);
      slot.setId(r.id().toString());
      slot.setComment(null);
      resources.add(slot);
    }
    Bundle bundle =
        SearchBundles.searchset(request.getRequestURL().toString(), params, resources, nextToken);
    return ResponseEntity.ok().contentType(FHIR_MEDIA_TYPE).body(FhirJson.serialize(bundle));
  }

  /** Statuses a non-patient may ask for; default {@code free}. */
  private static Set<String> statuses(SearchQuery query) {
    List<Criterion> all = query.criteria("status");
    if (all.size() > 1) {
      throw new ShpException("validation-failed");
    }
    if (all.isEmpty()) {
      return Set.of("free");
    }
    Set<String> result = new LinkedHashSet<>();
    for (Criterion.Token t : ((Criterion.TokenCriterion) all.getFirst()).anyOf()) {
      if (t.code() == null || !SlotRecord.ALLOWED_STATUSES.contains(t.code())) {
        throw new ShpException("validation-failed");
      }
      result.add(t.code());
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
    var c = (Criterion.TokenCriterion) all.getFirst();
    if (c.anyOf().size() != 1) {
      throw new ShpException("validation-failed");
    }
    Criterion.Token token = c.anyOf().getFirst();
    if (token.code() == null
        || (token.system() != null && !token.system().equals(SpecialtyCatalog.SYSTEM))) {
      throw new ShpException("validation-failed");
    }
    return token.code().strip().toLowerCase(Locale.ROOT);
  }

  private static UUID singleReference(SearchQuery query, String param) {
    List<Criterion> all = query.criteria(param);
    if (all.size() > 1) {
      throw new ShpException("validation-failed");
    }
    if (all.isEmpty()) {
      return null;
    }
    var c = (Criterion.ReferenceCriterion) all.getFirst();
    if (c.anyOfIds().size() != 1) {
      throw new ShpException("validation-failed");
    }
    try {
      return UUID.fromString(c.anyOfIds().getFirst());
    } catch (IllegalArgumentException e) {
      throw new ShpException("validation-failed");
    }
  }
}
