package org.shp.fhir.search;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.shp.fhir.search.Criterion.DateCriterion;
import org.shp.fhir.search.Criterion.ReferenceCriterion;
import org.shp.fhir.search.Criterion.StringCriterion;
import org.shp.fhir.search.Criterion.Token;
import org.shp.fhir.search.Criterion.TokenCriterion;
import org.shp.web.errors.ShpException;

/**
 * Parse query FHIR ({@code status=free&start=ge2026-11-01}) thành {@link SearchQuery} (ADR-0003, 07
 * §4). Tham số lạ, modifier ({@code name:exact}) hoặc tiền tố không hỗ trợ → 400 {@code
 * unsupported-parameter}; giá trị sai → 400 {@code validation-failed}. Lỗi không kèm giá trị gửi
 * lên (có thể là PII như tên, số điện thoại).
 */
public final class SearchParser {
  public static final int DEFAULT_COUNT = 20;
  public static final int MAX_COUNT = 100;

  /** Ngày không có giờ ({@code 2026-11}) hiểu theo giờ Việt Nam; giá trị lưu ở UTC. */
  public static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

  private static final int MAX_STRING = 100;
  private static final Set<String> UNSUPPORTED_PREFIXES = Set.of("ne", "sa", "eb", "ap");
  private static final Pattern ID = Pattern.compile("[A-Za-z0-9\\-.]{1,64}");
  // YYYY[-MM[-DD[Thh:mm[:ss[.f]](Z|±hh:mm)]]]: có giờ thì bắt buộc múi giờ (FHIR dateTime).
  private static final Pattern DATE =
      Pattern.compile(
          "(\\d{4})(?:-(\\d{2})(?:-(\\d{2})"
              + "(?:T(\\d{2}):(\\d{2})(?::(\\d{2})(?:\\.(\\d{1,9}))?)?(Z|[+-]\\d{2}:\\d{2}))?)?)?");

  private final SearchParameterRegistry registry;
  private final ZoneId zone;

  public SearchParser(SearchParameterRegistry registry, ZoneId zone) {
    this.registry = registry;
    this.zone = zone;
  }

  /** Parser với tham số chuẩn (08–10) và giờ Việt Nam. */
  public static SearchParser standard() {
    return new SearchParser(SearchParameterRegistry.standard(), DEFAULT_ZONE);
  }

  /** {@code HttpServletRequest.getParameterMap()} → dạng {@link #parse} nhận. */
  public static Map<String, List<String>> fromServlet(Map<String, String[]> params) {
    Map<String, List<String>> out = new LinkedHashMap<>();
    params.forEach((k, v) -> out.put(k, Arrays.asList(v)));
    return out;
  }

  public SearchQuery parse(String resourceType, Map<String, List<String>> params) {
    Map<String, SearchParamDef> defs = registry.params(resourceType);
    List<Criterion> criteria = new ArrayList<>();
    int count = DEFAULT_COUNT;
    String pageToken = null;
    for (var e : params.entrySet()) {
      switch (e.getKey()) {
        case "_count" -> count = count(single(e.getValue()));
        case "_page_token" -> pageToken = single(e.getValue());
        default -> {
          SearchParamDef def = defs.get(e.getKey());
          if (def == null) {
            throw new ShpException("unsupported-parameter");
          }
          for (String value : e.getValue()) {
            criteria.add(criterion(def, value));
          }
        }
      }
    }
    return new SearchQuery(resourceType, List.copyOf(criteria), count, pageToken);
  }

  private Criterion criterion(SearchParamDef def, String raw) {
    if (raw == null || raw.isBlank()) {
      throw invalid();
    }
    return switch (def.type()) {
      case TOKEN -> new TokenCriterion(def.name(), split(raw).stream().map(this::token).toList());
      case REFERENCE ->
          new ReferenceCriterion(
              def.name(),
              def.target(),
              split(raw).stream().map(v -> referenceId(def.target(), v)).toList());
      case DATE -> date(def.name(), raw);
      case STRING -> string(def.name(), raw);
    };
  }

  private static List<String> split(String raw) {
    List<String> parts = Arrays.asList(raw.split(",", -1));
    if (parts.stream().anyMatch(String::isEmpty)) {
      throw invalid();
    }
    return parts;
  }

  /**
   * {@code code}, {@code system|code}, {@code system|} (mọi mã của system); {@code |code} = code.
   */
  private Token token(String value) {
    int bar = value.indexOf('|');
    if (bar < 0) {
      return new Token(null, value);
    }
    String system = value.substring(0, bar);
    String code = value.substring(bar + 1);
    if (code.indexOf('|') >= 0 || (system.isEmpty() && code.isEmpty())) {
      throw invalid();
    }
    return new Token(system.isEmpty() ? null : system, code.isEmpty() ? null : code);
  }

  private static String referenceId(String target, String value) {
    int slash = value.indexOf('/');
    String id = value;
    if (slash >= 0) {
      if (!value.substring(0, slash).equals(target)) {
        throw invalid();
      }
      id = value.substring(slash + 1);
    }
    if (!ID.matcher(id).matches()) {
      throw invalid();
    }
    return id;
  }

  private DateCriterion date(String param, String raw) {
    String prefix = "eq";
    String value = raw;
    if (raw.length() > 2 && Character.isLetter(raw.charAt(0))) {
      prefix = raw.substring(0, 2);
      value = raw.substring(2);
    }
    if (UNSUPPORTED_PREFIXES.contains(prefix)) {
      throw new ShpException("unsupported-parameter");
    }
    Instant[] range = range(value);
    return switch (prefix) {
      case "eq" -> new DateCriterion(param, range[0], range[1]);
      case "ge" -> new DateCriterion(param, range[0], null);
      case "gt" -> new DateCriterion(param, range[1], null);
      case "le" -> new DateCriterion(param, null, range[1]);
      case "lt" -> new DateCriterion(param, null, range[0]);
      default -> throw invalid();
    };
  }

  /** Khoảng {@code [start, end)} mà giá trị phủ theo độ chính xác: năm, tháng, ngày, phút, giây… */
  private Instant[] range(String value) {
    Matcher m = DATE.matcher(value);
    if (!m.matches()) {
      throw invalid();
    }
    try {
      int year = Integer.parseInt(m.group(1));
      if (m.group(2) == null) {
        ZonedDateTime start = LocalDate.of(year, 1, 1).atStartOfDay(zone);
        return new Instant[] {start.toInstant(), start.plusYears(1).toInstant()};
      }
      int month = Integer.parseInt(m.group(2));
      if (m.group(3) == null) {
        ZonedDateTime start = LocalDate.of(year, month, 1).atStartOfDay(zone);
        return new Instant[] {start.toInstant(), start.plusMonths(1).toInstant()};
      }
      int day = Integer.parseInt(m.group(3));
      if (m.group(4) == null) {
        ZonedDateTime start = LocalDate.of(year, month, day).atStartOfDay(zone);
        return new Instant[] {start.toInstant(), start.plusDays(1).toInstant()};
      }
      String seconds = m.group(6);
      String fraction = m.group(7);
      int nanos = fraction == null ? 0 : Integer.parseInt((fraction + "00000000").substring(0, 9));
      OffsetDateTime start =
          LocalDateTime.of(
                  year,
                  month,
                  day,
                  Integer.parseInt(m.group(4)),
                  Integer.parseInt(m.group(5)),
                  seconds == null ? 0 : Integer.parseInt(seconds),
                  nanos)
              .atOffset("Z".equals(m.group(8)) ? ZoneOffset.UTC : ZoneOffset.of(m.group(8)));
      OffsetDateTime end =
          seconds == null
              ? start.plusMinutes(1)
              : fraction == null
                  ? start.plusSeconds(1)
                  : start.plusNanos((long) Math.pow(10, 9 - fraction.length()));
      return new Instant[] {start.toInstant(), end.toInstant()};
    } catch (DateTimeException e) {
      throw invalid();
    }
  }

  private static StringCriterion string(String param, String raw) {
    String value = raw.strip();
    if (value.length() > MAX_STRING) {
      throw invalid();
    }
    return new StringCriterion(param, value);
  }

  private static int count(String value) {
    if (!value.matches("\\d{1,9}") || Integer.parseInt(value) < 1) {
      throw invalid();
    }
    return Math.min(Integer.parseInt(value), MAX_COUNT);
  }

  private static String single(List<String> values) {
    if (values.size() != 1 || values.getFirst() == null || values.getFirst().isBlank()) {
      throw invalid();
    }
    return values.getFirst();
  }

  private static ShpException invalid() {
    return new ShpException("validation-failed");
  }
}
