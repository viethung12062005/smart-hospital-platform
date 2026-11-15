package org.shp.fhir.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.shp.fhir.search.Criterion.DateCriterion;
import org.shp.fhir.search.Criterion.ReferenceCriterion;
import org.shp.fhir.search.Criterion.StringCriterion;
import org.shp.fhir.search.Criterion.Token;
import org.shp.fhir.search.Criterion.TokenCriterion;
import org.shp.web.errors.ShpException;

/** Parser tham số tìm kiếm FHIR (07 §4, SHR-03). Ngày không có giờ hiểu theo giờ Việt Nam (+07). */
class SearchParserTest {
  final SearchParser parser = SearchParser.standard();

  /** {@code "a=1&b=2&a=3"} → map giữ thứ tự, giá trị lặp gom lại (giống servlet). */
  static Map<String, List<String>> q(String query) {
    Map<String, String[]> m = new LinkedHashMap<>();
    for (String pair : query.split("&")) {
      String[] kv = pair.split("=", 2);
      String[] old = m.getOrDefault(kv[0], new String[0]);
      String[] next = java.util.Arrays.copyOf(old, old.length + 1);
      next[old.length] = kv.length > 1 ? kv[1] : "";
      m.put(kv[0], next);
    }
    return SearchParser.fromServlet(m);
  }

  SearchQuery parse(String type, String query) {
    return parser.parse(type, q(query));
  }

  DateCriterion date(String type, String query) {
    return (DateCriterion) parse(type, query).criteria().getFirst();
  }

  static void assertError(String code, Runnable call) {
    assertThatThrownBy(call::run)
        .isInstanceOfSatisfying(
            ShpException.class,
            e -> {
              assertThat(e.info().code()).isEqualTo(code);
              assertThat(e.info().status()).isEqualTo(400);
              assertThat(e.detail()).isNull(); // không lặp lại giá trị gửi lên (có thể là PII)
            });
  }

  static Instant t(String s) {
    return Instant.parse(s);
  }

  // ---------- date: độ chính xác ----------
  @ParameterizedTest(name = "{0}")
  @CsvSource({
    "2026,                          2025-12-31T17:00:00Z,     2026-12-31T17:00:00Z",
    "2026-11,                       2026-10-31T17:00:00Z,     2026-11-30T17:00:00Z",
    "2026-02,                       2026-01-31T17:00:00Z,     2026-02-28T17:00:00Z",
    "2026-12-03,                    2026-12-02T17:00:00Z,     2026-12-03T17:00:00Z",
    "2026-12-03T08:15+07:00,        2026-12-03T01:15:00Z,     2026-12-03T01:16:00Z",
    "2026-12-03T01:15:30Z,          2026-12-03T01:15:30Z,     2026-12-03T01:15:31Z",
    "2026-12-03T01:15:30.5Z,        2026-12-03T01:15:30.500Z, 2026-12-03T01:15:30.600Z",
    "2026-12-03T01:15:30.123-05:00, 2026-12-03T06:15:30.123Z, 2026-12-03T06:15:30.124Z"
  })
  void eqCoversTheWholePrecisionRange(String value, String from, String to) {
    var c = date("Slot", "start=" + value);
    assertThat(c.param()).isEqualTo("start");
    assertThat(c.from()).isEqualTo(t(from));
    assertThat(c.to()).isEqualTo(t(to));
    assertThat(date("Slot", "start=eq" + value)).isEqualTo(c);
  }

  @Test
  void prefixesMapToOpenRanges() {
    // 2026-11 (+07) = [2026-10-31T17:00Z, 2026-11-30T17:00Z)
    Instant monthStart = t("2026-10-31T17:00:00Z");
    Instant monthEnd = t("2026-11-30T17:00:00Z");
    assertThat(date("Slot", "start=ge2026-11"))
        .isEqualTo(new DateCriterion("start", monthStart, null));
    assertThat(date("Slot", "start=gt2026-11"))
        .isEqualTo(new DateCriterion("start", monthEnd, null));
    assertThat(date("Slot", "start=le2026-11"))
        .isEqualTo(new DateCriterion("start", null, monthEnd));
    assertThat(date("Slot", "start=lt2026-11"))
        .isEqualTo(new DateCriterion("start", null, monthStart));
  }

  @Test
  void repeatedDateParamsAreAnded() {
    // Ví dụ của 08 S8: start=ge2026-12-01&start=lt2026-12-08
    var query = parse("Slot", "specialty=cardiology&start=ge2026-12-01&start=lt2026-12-08");
    assertThat(query.criteria("start"))
        .containsExactly(
            new DateCriterion("start", t("2026-11-30T17:00:00Z"), null),
            new DateCriterion("start", null, t("2026-12-07T17:00:00Z")));
  }

  @Test
  void otherTimeZonesAreSupported() {
    var utc = new SearchParser(SearchParameterRegistry.standard(), java.time.ZoneOffset.UTC);
    var c = (DateCriterion) utc.parse("Slot", q("start=2026-11")).criteria().getFirst();
    assertThat(c.from()).isEqualTo(t("2026-11-01T00:00:00Z"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "start=2026-13",
        "start=2026-02-30",
        "start=26-11-01",
        "start=12/04/2026",
        "start=2026-12-03T08:15", // có giờ mà thiếu múi giờ
        "start=2026-12-03T25:00Z",
        "start=xx2026-11",
        "start=ge",
        "start=2026-11,2026-12",
        "start="
      })
  void invalidDateIsValidationFailed(String query) {
    assertError("validation-failed", () -> parse("Slot", query));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"start=ne2026-11", "start=sa2026-11", "start=eb2026-11", "start=ap2026-11"})
  void unsupportedPrefixIsUnsupportedParameter(String query) {
    assertError("unsupported-parameter", () -> parse("Slot", query));
  }

  // ---------- token ----------
  @Test
  void tokenWithSystemAndCommaIsOr() {
    var c = (TokenCriterion) parse("Appointment", "status=booked,arrived").criteria().getFirst();
    assertThat(c.anyOf()).containsExactly(new Token(null, "booked"), new Token(null, "arrived"));

    var id =
        (TokenCriterion)
            parse("Patient", "identifier=urn:shp:mrn|BN-2026-000123").criteria().getFirst();
    assertThat(id.anyOf()).containsExactly(new Token("urn:shp:mrn", "BN-2026-000123"));

    var anyCode =
        (TokenCriterion) parse("Observation", "code=http://loinc.org|").criteria().getFirst();
    assertThat(anyCode.anyOf()).containsExactly(new Token("http://loinc.org", null));

    var noSystem = (TokenCriterion) parse("Schedule", "active=|true").criteria().getFirst();
    assertThat(noSystem.anyOf()).containsExactly(new Token(null, "true"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"status=", "status=|", "status=booked,", "status=a|b|c", "status=,booked"})
  void invalidTokenIsValidationFailed(String query) {
    assertError("validation-failed", () -> parse("Appointment", query));
  }

  // ---------- reference ----------
  @Test
  void referenceAcceptsTypedOrBareIds() {
    var c =
        (ReferenceCriterion)
            parse("Slot", "practitioner=Practitioner/0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e02,abc.1")
                .criteria()
                .getFirst();
    assertThat(c.targetType()).isEqualTo("Practitioner");
    assertThat(c.anyOfIds()).containsExactly("0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e02", "abc.1");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "practitioner=Patient/0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e01",
        "practitioner=Practitioner/",
        "practitioner=Practitioner/a/_history/2",
        "practitioner=bad id"
      })
  void invalidReferenceIsValidationFailed(String query) {
    assertError("validation-failed", () -> parse("Slot", query));
  }

  @Test
  void referenceIdIsOneTo64Characters() {
    assertThat(parse("Slot", "schedule=x").criteria()).hasSize(1);
    assertThat(parse("Slot", "schedule=" + "a".repeat(64)).criteria()).hasSize(1);
    assertError("validation-failed", () -> parse("Slot", "schedule=" + "a".repeat(65)));
  }

  // ---------- string ----------
  @Test
  void stringIsTrimmedAndBounded() {
    var c = (StringCriterion) parse("Patient", "name=  nguyen va ").criteria().getFirst();
    assertThat(c).isEqualTo(new StringCriterion("name", "nguyen va"));
    assertError("validation-failed", () -> parse("Patient", "name=" + "a".repeat(101)));
    assertError("validation-failed", () -> parse("Patient", "name=   "));
  }

  // ---------- _count, _page_token ----------
  @Test
  void countDefaultsTo20AndIsCappedAt100() {
    assertThat(parse("Slot", "specialty=ent").count()).isEqualTo(20);
    assertThat(parse("Slot", "_count=50").count()).isEqualTo(50);
    assertThat(parse("Slot", "_count=500").count()).isEqualTo(100);
    assertThat(parse("Encounter", "_count=1").count()).isEqualTo(1);
  }

  @ParameterizedTest
  @ValueSource(strings = {"_count=0", "_count=-1", "_count=abc", "_count=", "_count=10&_count=20"})
  void invalidCountIsValidationFailed(String query) {
    assertError("validation-failed", () -> parse("Slot", query));
  }

  @Test
  void pageTokenIsPassedThroughOpaque() {
    var query = parse("Appointment", "patient=p1&_page_token=eyJrIjoxfQ.sig");
    assertThat(query.pageToken()).isEqualTo("eyJrIjoxfQ.sig");
    assertThat(parse("Appointment", "patient=p1").pageToken()).isNull();
    assertError("validation-failed", () -> parse("Appointment", "_page_token="));
  }

  // ---------- tham số lạ → 400 (strict) ----------
  @ParameterizedTest
  @ValueSource(
      strings = {
        "foo=1",
        "name:exact=An", // modifier không hỗ trợ
        "_sort=date",
        "_total=accurate",
        "start=2026-11" // Slot có start, Appointment thì không
      })
  void unknownParameterIsUnsupported(String query) {
    assertError("unsupported-parameter", () -> parse("Appointment", query));
  }

  @Test
  void resourceWithoutSearchParamsOnlyAcceptsPaging() {
    assertThat(parse("Encounter", "_count=5").criteria()).isEmpty();
    assertError("unsupported-parameter", () -> parse("Encounter", "status=finished"));
  }

  @Test
  void criteriaKeepOrderAndQueryIsImmutable() {
    var query = parse("Slot", "status=free&specialty=cardiology&practitioner=p1");
    assertThat(query.resourceType()).isEqualTo("Slot");
    assertThat(query.criteria())
        .extracting(Criterion::param)
        .containsExactly("status", "specialty", "practitioner");
    assertThatThrownBy(() -> query.criteria().clear())
        .isInstanceOf(UnsupportedOperationException.class);
  }
}
