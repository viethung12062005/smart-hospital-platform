package org.shp.events;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.json.JsonMapper;

/** Tạo, tuần tự hóa và đọc {@link CloudEvent}. */
public final class CloudEvents {
  /** Giới hạn kích thước một event (13-events §1). */
  public static final int MAX_BYTES = 64 * 1024;

  /** JSON mapper dùng cho event: bỏ trường null, thời gian ISO-8601. */
  public static final JsonMapper JSON =
      JsonMapper.builder()
          .changeDefaultPropertyInclusion(i -> i.withValueInclusion(JsonInclude.Include.NON_NULL))
          .build();

  private static final Pattern TYPE = Pattern.compile("^shp\\.([a-z]+)\\.([a-z-]+)\\.v([0-9]+)$");

  private CloudEvents() {}

  public static CloudEvent create(String service, String type, String subject, Object data) {
    return create(service, type, subject, data, Clock.systemUTC(), null);
  }

  /**
   * Tạo event hợp lệ: id UUIDv7, thời điểm UTC, {@code dataschema} suy từ type, {@code traceparent}
   * từ span hiện tại. {@code subject} dạng {@code Appointment/{id}} (giữ thứ tự theo đối tượng trên
   * bus).
   */
  public static CloudEvent create(
      String service, String type, String subject, Object data, Clock clock, UUID id) {
    if (service == null || service.isBlank()) {
      throw new IllegalArgumentException("service bắt buộc");
    }
    Matcher m = TYPE.matcher(type);
    if (!m.matches()) {
      throw new IllegalArgumentException("type phải dạng shp.<context>.<event>.v<N>: " + type);
    }
    CloudEvent evt =
        new CloudEvent(
            "1.0",
            (id != null ? id : Uuids.v7(clock)).toString(),
            "/services/" + service,
            type,
            subject,
            Instant.now(clock).truncatedTo(ChronoUnit.MILLIS),
            "application/json",
            "https://shp.example.org/schemas/%s/%s/v%s.json"
                .formatted(m.group(1), m.group(2), m.group(3)),
            traceParent(),
            JSON.valueToTree(data));
    if (toJson(evt).getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
      throw new IllegalArgumentException("Event vượt " + MAX_BYTES + " byte");
    }
    return evt;
  }

  public static String toJson(CloudEvent evt) {
    return JSON.writeValueAsString(evt);
  }

  public static CloudEvent parse(String json) {
    return JSON.readValue(json, CloudEvent.class);
  }

  private static String traceParent() {
    SpanContext c = Span.current().getSpanContext();
    return c.isValid()
        ? "00-" + c.getTraceId() + "-" + c.getSpanId() + "-" + c.getTraceFlags().asHex()
        : null;
  }
}
