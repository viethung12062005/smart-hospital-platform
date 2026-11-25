package org.shp.scheduling.api;

import io.opentelemetry.api.trace.Span;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.shp.authz.Principal;
import org.shp.events.CloudEvents;
import org.shp.fhir.FhirJson;
import org.shp.scheduling.app.BookingService;
import org.shp.scheduling.domain.BookingCommand;
import org.shp.scheduling.domain.BookingResult;
import org.shp.scheduling.domain.PatientClient;
import org.shp.scheduling.domain.SlotUnavailableException;
import org.shp.scheduling.domain.SuggestedSlot;
import org.shp.scheduling.infra.SuggestedSlotRepository;
import org.shp.web.auth.IdentityInterceptor;
import org.shp.web.authz.RequireShpAuthz;
import org.shp.web.errors.ErrorWriter;
import org.shp.web.errors.ShpException;
import org.shp.web.precondition.ETags;
import org.shp.web.precondition.PreconditionInterceptor;
import org.shp.web.precondition.RequireIdempotencyKey;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** S11 {@code POST /fhir/Appointment/$book}: book an appointment (patient for self, nurse). */
@RestController
public class BookingController {
  /** URL of the OperationOutcome extension that lists the suggested slots (08 §4, S11). */
  public static final String SUGGESTED_SLOTS_URL =
      "https://shp.example.org/fhir/StructureDefinition/suggested-slots";

  static final int SUGGESTIONS = 3;
  private static final MediaType FHIR_MEDIA_TYPE = MediaType.parseMediaType(FhirJson.MEDIA_TYPE);

  private final BookingService service;
  private final SuggestedSlotRepository suggestions;
  private final PatientClient patients;
  private final Clock clock;

  public BookingController(
      BookingService service,
      SuggestedSlotRepository suggestions,
      PatientClient patients,
      Clock clock) {
    this.service = Objects.requireNonNull(service, "service must not be null");
    this.suggestions = Objects.requireNonNull(suggestions, "suggestions must not be null");
    this.patients = Objects.requireNonNull(patients, "patients must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
  }

  /**
   * 201 with the new Appointment, or 200 with the Appointment of an earlier request that used the
   * same Idempotency-Key and body (AC-03.2). A nurse books for any patient and the channel is
   * forced to {@code staff}; a patient books for themselves or a linked profile with channel {@code
   * web} or {@code chatbot} (default {@code web}).
   */
  @PostMapping(
      value = "/fhir/Appointment/$book",
      consumes = {FhirJson.MEDIA_TYPE, MediaType.APPLICATION_JSON_VALUE},
      produces = {FhirJson.MEDIA_TYPE, MediaType.APPLICATION_JSON_VALUE})
  @RequireIdempotencyKey
  @RequireShpAuthz(resource = "Appointment", action = "book", context = "bookingFacts")
  public ResponseEntity<String> book(HttpServletRequest request) {
    Principal principal = IdentityInterceptor.principal(request);
    BookRequest body = BookRequest.from(request);
    boolean nurse = principal.roles().contains("nurse");
    String channel = nurse ? "staff" : body.channel() == null ? "web" : body.channel();
    if (!nurse && "staff".equals(channel)) {
      throw new ShpException("validation-failed");
    }
    UUID patient =
        body.patientId() != null ? body.patientId() : UUID.fromString(principal.fhirUserId());
    if (!patients.exists(patient)) {
      throw new ShpException("not-found");
    }

    BookingResult result =
        service.book(
            new BookingCommand(
                principal.subject(),
                PreconditionInterceptor.idempotencyKey(request),
                body.hash(),
                body.slotId(),
                patient,
                channel,
                body.comment()));

    var a = result.appointment();
    String location = "/fhir/Appointment/" + a.id() + "/_history/" + a.versionId();
    return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
        .contentType(FHIR_MEDIA_TYPE)
        .header(HttpHeaders.ETAG, ETags.format(a.versionId()))
        .location(URI.create(location))
        .body(a.content());
  }

  /**
   * 409 {@code slot-unavailable} as an OperationOutcome carrying the 3 nearest free slots, so the
   * patient can pick another one at once (14-sequences). The lookup runs after the booking
   * transaction has rolled back.
   */
  @ExceptionHandler(SlotUnavailableException.class)
  void slotUnavailable(SlotUnavailableException ex, HttpServletRequest req, HttpServletResponse res)
      throws IOException {
    Instant now = clock.instant();
    List<SuggestedSlot> nearest =
        suggestions.nearestFree(
            ex.specialty(),
            ex.practitionerId(),
            ex.slotId(),
            now,
            now.plus(BookingService.BOOKING_WINDOW),
            SUGGESTIONS);
    String traceId = traceId();
    var issue = new LinkedHashMap<String, Object>();
    issue.put("severity", "error");
    issue.put("code", ex.info().fhirIssueType());
    issue.put(
        "details",
        Map.of(
            "coding",
            List.of(Map.of("system", ErrorWriter.FHIR_ERROR_SYSTEM, "code", ex.info().code()))));
    issue.put("diagnostics", wantsEnglish(req) ? ex.info().messageEn() : ex.info().messageVi());
    var outcome = new LinkedHashMap<String, Object>();
    outcome.put("resourceType", "OperationOutcome");
    if (!nearest.isEmpty()) {
      outcome.put("extension", suggestedSlots(nearest));
    }
    outcome.put("issue", List.of(issue));
    res.setStatus(ex.info().status());
    res.setHeader("X-Request-Id", traceId);
    res.setContentType(FhirJson.MEDIA_TYPE);
    res.setCharacterEncoding("UTF-8");
    res.getWriter().write(CloudEvents.JSON.writeValueAsString(outcome));
  }

  private static String traceId() {
    var ctx = Span.current().getSpanContext();
    return ctx.isValid() ? ctx.getTraceId() : UUID.randomUUID().toString().replace("-", "");
  }

  private static boolean wantsEnglish(HttpServletRequest req) {
    String lang = req.getHeader("Accept-Language");
    return lang != null && lang.toLowerCase(Locale.ROOT).startsWith("en");
  }

  static List<Object> suggestedSlots(List<SuggestedSlot> slots) {
    List<Object> items = new ArrayList<>();
    for (SuggestedSlot s : slots) {
      items.add(
          Map.of(
              "url",
              "slot",
              "extension",
              List.of(
                  Map.of(
                      "url", "reference", "valueReference", Map.of("reference", "Slot/" + s.id())),
                  Map.of(
                      "url",
                      "practitioner",
                      "valueReference",
                      Map.of("reference", "Practitioner/" + s.practitionerId())),
                  Map.of("url", "start", "valueInstant", s.start().toString()),
                  Map.of("url", "end", "valueInstant", s.end().toString()))));
    }
    return List.of(Map.of("url", SUGGESTED_SLOTS_URL, "extension", items));
  }
}
