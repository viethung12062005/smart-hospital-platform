package org.shp.scheduling.api;

import jakarta.servlet.http.HttpServletRequest;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.shp.authz.Principal;
import org.shp.scheduling.app.ScheduleService;
import org.shp.scheduling.app.ScheduleService.GetTemplatesResult;
import org.shp.scheduling.app.ScheduleService.ReplaceTemplatesResult;
import org.shp.scheduling.app.ScheduleService.TemplateInput;
import org.shp.scheduling.domain.ScheduleTemplateRecord;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Application API controller for weekly schedule templates (S5–S6). */
@RestController
public class ScheduleTemplateController {

  private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");

  private final ScheduleService service;

  public ScheduleTemplateController(ScheduleService service) {
    this.service = Objects.requireNonNull(service, "service must not be null");
  }

  public record ReplaceTemplatesRequest(List<TemplateInput> templates) {}

  public record TemplateItemResponse(
      String id,
      int weekday,
      String startTime,
      String endTime,
      int slotMinutes,
      String validFrom,
      String validTo) {

    static TemplateItemResponse from(ScheduleTemplateRecord r) {
      return new TemplateItemResponse(
          r.id().toString(),
          r.weekday(),
          TIME_FORMATTER.format(r.startTime()),
          TIME_FORMATTER.format(r.endTime()),
          r.slotMinutes(),
          r.validFrom().toString(),
          r.validTo() != null ? r.validTo().toString() : null);
    }
  }

  public record ReplaceTemplatesResponse(
      List<TemplateItemResponse> templates, int affectedFutureSlots) {}

  public record GetTemplatesResponse(List<TemplateItemResponse> templates) {}

  /**
   * S5: PUT /api/v1/scheduling/schedules/{id}/templates - Replace a schedule's entire weekly
   * template. Permissions: Nurse, Admin. Requires If-Match.
   */
  @PutMapping(
      value = "/api/v1/scheduling/schedules/{id}/templates",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  @RequireShpAuthz(resource = "Schedule", action = "update", context = "scheduleFacts")
  public ResponseEntity<ReplaceTemplatesResponse> replaceTemplates(
      @PathVariable String id,
      @RequestBody(required = false) ReplaceTemplatesRequest body,
      HttpServletRequest request) {
    UUID scheduleId = parseUuid(id);
    long expectedVersion = PreconditionInterceptor.expectedVersion(request);
    if (body == null || body.templates() == null) {
      throw new ShpException("validation-failed");
    }

    Principal principal = IdentityInterceptor.principal(request);
    String changedBy = principal.subject() != null ? principal.subject() : "unknown";

    ReplaceTemplatesResult result =
        service.replaceTemplates(scheduleId, body.templates(), expectedVersion, changedBy);
    List<TemplateItemResponse> items =
        result.templates().stream().map(TemplateItemResponse::from).toList();

    return ResponseEntity.ok()
        .header(HttpHeaders.ETAG, ETags.format(result.scheduleVersion()))
        .body(new ReplaceTemplatesResponse(items, result.affectedFutureSlots()));
  }

  /**
   * S6: GET /api/v1/scheduling/schedules/{id}/templates - View a schedule's weekly template.
   * Permissions: Nurse, Admin; Doctor (own schedule only).
   */
  @GetMapping(
      value = "/api/v1/scheduling/schedules/{id}/templates",
      produces = MediaType.APPLICATION_JSON_VALUE)
  @RequireShpAuthz(resource = "Schedule", action = "read", context = "scheduleFacts")
  public ResponseEntity<GetTemplatesResponse> getTemplates(@PathVariable String id) {
    UUID scheduleId = parseUuid(id);
    GetTemplatesResult result = service.getTemplates(scheduleId);
    List<TemplateItemResponse> items =
        result.templates().stream().map(TemplateItemResponse::from).toList();

    return ResponseEntity.ok()
        .header(HttpHeaders.ETAG, ETags.format(result.scheduleVersion()))
        .body(new GetTemplatesResponse(items));
  }

  private static UUID parseUuid(String id) {
    try {
      return UUID.fromString(id);
    } catch (IllegalArgumentException e) {
      throw new ShpException("not-found");
    }
  }
}
