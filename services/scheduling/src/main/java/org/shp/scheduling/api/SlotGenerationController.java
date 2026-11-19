package org.shp.scheduling.api;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;
import org.shp.scheduling.app.SlotGenerator;
import org.shp.scheduling.app.SlotGenerator.SlotGenerationResult;
import org.shp.web.authz.RequireShpAuthz;
import org.shp.web.errors.ShpException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Application API for generating slots from a schedule's weekly templates (S7). */
@RestController
public class SlotGenerationController {

  /** BR-04: slots may be generated at most this many days ahead of today. */
  static final int MAX_DAYS_AHEAD = 30;

  /** At most this many calendar days (inclusive) per call. */
  static final int MAX_RANGE_DAYS = 31;

  private final SlotGenerator generator;
  private final Clock clock;

  public SlotGenerationController(SlotGenerator generator, Clock clock) {
    this.generator = Objects.requireNonNull(generator, "generator must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
  }

  public record GenerateSlotsRequest(String from, String to) {}

  public record GenerateSlotsResponse(int created, int skippedExisting) {}

  /**
   * S7: POST /api/v1/scheduling/schedules/{id}/slots:generate. Permissions: Nurse, Admin. The range
   * is validated against today in {@code Asia/Ho_Chi_Minh}: {@code from >= today}, {@code to <=
   * today + 30}, at most 31 days per call.
   */
  @PostMapping(
      value = "/api/v1/scheduling/schedules/{id}/slots:generate",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  @RequireShpAuthz(resource = "Slot", action = "create")
  public ResponseEntity<GenerateSlotsResponse> generate(
      @PathVariable String id, @RequestBody(required = false) GenerateSlotsRequest body) {
    UUID scheduleId = parseUuid(id);
    if (body == null) {
      throw new ShpException("validation-failed");
    }
    LocalDate from = parseDate(body.from());
    LocalDate to = parseDate(body.to());
    LocalDate today = LocalDate.now(clock.withZone(SlotGenerator.VN_ZONE));
    if (from.isBefore(today)
        || to.isBefore(from)
        || to.isAfter(today.plusDays(MAX_DAYS_AHEAD))
        || ChronoUnit.DAYS.between(from, to) + 1 > MAX_RANGE_DAYS) {
      throw new ShpException("validation-failed");
    }

    SlotGenerationResult result = generator.generate(scheduleId, from, to);
    return ResponseEntity.ok(new GenerateSlotsResponse(result.created(), result.skippedExisting()));
  }

  private static LocalDate parseDate(String value) {
    if (value == null) {
      throw new ShpException("validation-failed");
    }
    try {
      return LocalDate.parse(value);
    } catch (DateTimeParseException e) {
      throw new ShpException("validation-failed");
    }
  }

  private static UUID parseUuid(String id) {
    try {
      return UUID.fromString(id);
    } catch (IllegalArgumentException e) {
      throw new ShpException("not-found");
    }
  }
}
