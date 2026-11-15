package org.shp.fhir.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.shp.fhir.search.PageToken.Key;
import org.shp.web.errors.ShpException;

/** Signed keyset page tokens (07 §4, SHR-04). Synthetic data only. */
class PageTokensTest {
  static final byte[] KEY = new byte[32];
  static final byte[] OTHER_KEY = new byte[32];

  static {
    java.util.Arrays.fill(KEY, (byte) 0x41);
    java.util.Arrays.fill(OTHER_KEY, (byte) 0x42);
  }

  static final PageTokens TOKENS = new PageTokens(KEY);
  static final SearchParser PARSER = SearchParser.standard();

  /** {@code "a=1&b=2&a=3"} -> ordered map, like a servlet parameter map. */
  static Map<String, List<String>> q(String query) {
    Map<String, String[]> params = new LinkedHashMap<>();
    if (!query.isEmpty()) {
      for (String pair : query.split("&")) {
        String[] kv = pair.split("=", 2);
        String[] old = params.getOrDefault(kv[0], new String[0]);
        String[] next = java.util.Arrays.copyOf(old, old.length + 1);
        next[old.length] = kv.length > 1 ? kv[1] : "";
        params.put(kv[0], next);
      }
    }
    return SearchParser.fromServlet(params);
  }

  static SearchQuery query(String type, String raw) {
    return PARSER.parse(type, q(raw));
  }

  static List<Key> keyset() {
    return List.of(new Key("start_at", "2026-11-30T17:00:00Z"), new Key("id", "slot-1"));
  }

  /** Craft a token with a valid signature over an arbitrary payload. */
  static String sign(byte[] key, String payload) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key, "HmacSHA256"));
      byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
      Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
      return encoder.encodeToString(bytes) + "." + encoder.encodeToString(mac.doFinal(bytes));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Replace the first character of the payload (part 0) or the signature (part 1). */
  static String tamper(String token, int part) {
    int dot = token.indexOf('.');
    String prefix = part == 0 ? "" : token.substring(0, dot + 1);
    String segment = part == 0 ? token.substring(0, dot) : token.substring(dot + 1);
    char replacement = segment.charAt(0) == 'A' ? 'B' : 'A';
    return prefix + replacement + segment.substring(1);
  }

  static void assertValidationFailed(Runnable call) {
    assertThatThrownBy(call::run)
        .isInstanceOfSatisfying(
            ShpException.class,
            e -> {
              assertThat(e.info().code()).isEqualTo("validation-failed");
              assertThat(e.info().status()).isEqualTo(400);
              assertThat(e.detail()).isNull();
            });
  }

  @Test
  void roundTripKeepsKeysetInTwoBase64UrlParts() {
    SearchQuery query = query("Slot", "specialty=cardiology&start=ge2026-12-01&_count=50");
    String token = TOKENS.encode(query, keyset());

    assertThat(token).containsOnlyOnce(".");
    assertThat(token.split("\\.", -1))
        .hasSize(2)
        .allSatisfy(part -> assertThat(part).isNotBlank().doesNotContain("="));

    PageToken decoded = TOKENS.decode(token, query);
    assertThat(decoded.resourceType()).isEqualTo("Slot");
    assertThat(decoded.filtersHash()).isEqualTo(PageTokens.filtersHash(query));
    assertThat(decoded.keyset()).containsExactlyElementsOf(keyset());
  }

  @Test
  void tamperedPayloadIsRejected() {
    SearchQuery query = query("Slot", "status=free");
    String token = TOKENS.encode(query, keyset());

    assertValidationFailed(() -> TOKENS.decode(tamper(token, 0), query));
  }

  @Test
  void tamperedSignatureIsRejected() {
    SearchQuery query = query("Slot", "status=free");
    String token = TOKENS.encode(query, keyset());

    assertValidationFailed(() -> TOKENS.decode(tamper(token, 1), query));
  }

  @Test
  void truncatedTokenIsRejected() {
    SearchQuery query = query("Slot", "status=free");
    String token = TOKENS.encode(query, keyset());

    assertValidationFailed(() -> TOKENS.decode(token.substring(0, token.length() / 2), query));
    assertValidationFailed(() -> TOKENS.decode(token.substring(0, token.length() - 4), query));
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "abc", "a.", ".b", ".", "a.b.c"})
  void malformedTokenIsRejected(String token) {
    assertValidationFailed(() -> TOKENS.decode(token, query("Slot", "status=free")));
  }

  @Test
  void tokenSignedWithAnotherKeyIsRejected() {
    SearchQuery query = query("Slot", "status=free");
    String token = TOKENS.encode(query, keyset());

    assertValidationFailed(() -> new PageTokens(OTHER_KEY).decode(token, query));
  }

  @Test
  void validSignatureOverMalformedPayloadIsRejected() {
    SearchQuery query = query("Slot", "status=free");

    assertValidationFailed(() -> TOKENS.decode(sign(KEY, "not json"), query));
    assertValidationFailed(() -> TOKENS.decode(sign(KEY, "{\"t\":\"Slot\",\"f\":\"abc\"}"), query));
    assertValidationFailed(() ->
        TOKENS.decode(
            sign(KEY, "{\"t\":\"Slot\",\"f\":\"abc\",\"k\":[{\"n\":\"id\",\"v\":1}]}"), query));
  }

  @Test
  void tokenForAnotherFilterSetIsRejected() {
    String token = TOKENS.encode(query("Slot", "specialty=cardiology"), keyset());

    assertValidationFailed(
        () -> TOKENS.decode(token, query("Slot", "specialty=cardiology&status=free")));
    assertValidationFailed(() -> TOKENS.decode(token, query("Slot", "specialty=ent")));
  }

  @Test
  void tokenForAnotherResourceTypeIsRejected() {
    String token = TOKENS.encode(query("Slot", ""), keyset());

    assertValidationFailed(() -> TOKENS.decode(token, query("Appointment", "")));
  }

  @Test
  void changingCountDoesNotInvalidateTheToken() {
    String token = TOKENS.encode(query("Slot", "status=free&_count=20"), keyset());

    assertThat(TOKENS.decode(token, query("Slot", "status=free&_count=100")).keyset())
        .containsExactlyElementsOf(keyset());
  }

  @Test
  void shortOrMissingKeyIsRejected() {
    assertThatThrownBy(() -> new PageTokens(null)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new PageTokens(new byte[PageTokens.MIN_KEY_BYTES - 1]))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(new PageTokens(new byte[PageTokens.MIN_KEY_BYTES])).isNotNull();
  }

  @Test
  void tokenDoesNotContainTheFilterValues() {
    // Synthetic PII: the payload must expose neither the name nor the phone number.
    SearchQuery query = query("Patient", "name=Nguyen Van A&phone=+84900000001");
    String token = TOKENS.encode(query, keyset());
    String payload =
        new String(
            Base64.getUrlDecoder().decode(token.substring(0, token.indexOf('.'))),
            StandardCharsets.UTF_8);

    assertThat(payload).doesNotContain("Nguyen Van A").doesNotContain("84900000001");
    assertThat(payload).contains("Patient").contains(PageTokens.filtersHash(query));
  }

  @Test
  void filtersHashIgnoresPagingAndSeparatesCriteria() {
    SearchQuery base = query("Slot", "status=free&specialty=cardiology");

    assertThat(PageTokens.filtersHash(query("Slot", "status=free&specialty=cardiology&_count=50")))
        .isEqualTo(PageTokens.filtersHash(base));
    assertThat(PageTokens.filtersHash(query("Slot", "status=free&specialty=ent")))
        .isNotEqualTo(PageTokens.filtersHash(base));
    assertThat(PageTokens.filtersHash(query("Appointment", "")))
        .isNotEqualTo(PageTokens.filtersHash(query("Slot", "")));
  }
}
