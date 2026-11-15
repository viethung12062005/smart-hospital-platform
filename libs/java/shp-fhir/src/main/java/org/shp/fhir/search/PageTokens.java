package org.shp.fhir.search;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.shp.fhir.search.Criterion.DateCriterion;
import org.shp.fhir.search.Criterion.ReferenceCriterion;
import org.shp.fhir.search.Criterion.StringCriterion;
import org.shp.fhir.search.Criterion.Token;
import org.shp.fhir.search.Criterion.TokenCriterion;
import org.shp.web.errors.ShpException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Encode and decode the opaque {@code _page_token} of keyset pagination (07 §4).
 *
 * <p>The wire format is {@code base64url(payload).base64url(HMAC-SHA256(payload))}. The payload
 * holds the resource type, a hash of the search filters and the keyset of the last record on the
 * page; it never holds the filter values themselves, which may be PII. Because the token is signed,
 * a modified token is rejected with 400 {@code validation-failed} instead of silently returning a
 * different page. The token is bound to the resource type and the filters it was issued for, so it
 * cannot be replayed against another query; {@code _count} is deliberately not bound, so a client
 * may change the page size between pages.
 *
 * <p>The signing key is supplied by the service (never by this library), at least {@value
 * #MIN_KEY_BYTES} bytes, from the environment or a key vault.
 */
public final class PageTokens {
  /** Minimum HMAC-SHA256 key length in bytes. */
  public static final int MIN_KEY_BYTES = 32;

  /** Upper bound on the wire token; a larger value cannot be a token this class issued. */
  private static final int MAX_TOKEN_LENGTH = 4096;

  private static final String ALGORITHM = "HmacSHA256";
  private static final char SEPARATOR = '.';
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
  private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

  private final byte[] key;

  /**
   * @param signingKey HMAC key; at least {@value #MIN_KEY_BYTES} bytes. The caller owns the source
   *     of the key.
   */
  public PageTokens(byte[] signingKey) {
    if (signingKey == null || signingKey.length < MIN_KEY_BYTES) {
      throw new IllegalArgumentException(
          "Signing key must be at least " + MIN_KEY_BYTES + " bytes");
    }
    this.key = signingKey.clone();
  }

  /** Encode the keyset of the last record on the page for the given query. */
  public String encode(SearchQuery query, List<PageToken.Key> keyset) {
    PageToken token = new PageToken(query.resourceType(), filtersHash(query), keyset);
    byte[] payload = payload(token);
    return ENCODER.encodeToString(payload) + SEPARATOR + ENCODER.encodeToString(sign(payload));
  }

  /**
   * Verify and decode a token for the current query.
   *
   * @throws ShpException 400 {@code validation-failed} when the token is malformed, has a bad
   *     signature, or was issued for another resource type or filter set
   */
  public PageToken decode(String token, SearchQuery query) {
    PageToken parsed = read(token);
    if (!parsed.resourceType().equals(query.resourceType())
        || !parsed.filtersHash().equals(filtersHash(query))) {
      throw invalid();
    }
    return parsed;
  }

  /**
   * Stable SHA-256 hash of the resource type and filters of a query. Criteria order is significant
   * (the parser preserves the request order); {@code _count} and {@code _page_token} are not part
   * of the filters.
   */
  public static String filtersHash(SearchQuery query) {
    StringBuilder canonical = new StringBuilder();
    field(canonical, query.resourceType());
    for (Criterion criterion : query.criteria()) {
      switch (criterion) {
        case TokenCriterion c -> {
          canonical.append("T;");
          field(canonical, c.param());
          canonical.append(c.anyOf().size()).append(';');
          for (Token token : c.anyOf()) {
            field(canonical, token.system());
            field(canonical, token.code());
          }
        }
        case ReferenceCriterion c -> {
          canonical.append("R;");
          field(canonical, c.param());
          field(canonical, c.targetType());
          canonical.append(c.anyOfIds().size()).append(';');
          for (String id : c.anyOfIds()) {
            field(canonical, id);
          }
        }
        case DateCriterion c -> {
          canonical.append("D;");
          field(canonical, c.param());
          field(canonical, c.from() == null ? null : c.from().toString());
          field(canonical, c.to() == null ? null : c.to().toString());
        }
        case StringCriterion c -> {
          canonical.append("S;");
          field(canonical, c.param());
          field(canonical, c.value());
        }
      }
    }
    return HexFormat.of()
        .formatHex(digest("SHA-256", canonical.toString().getBytes(StandardCharsets.UTF_8)));
  }

  private PageToken read(String token) {
    if (token == null || token.length() > MAX_TOKEN_LENGTH) {
      throw invalid();
    }
    int dot = token.indexOf(SEPARATOR);
    if (dot <= 0 || dot != token.lastIndexOf(SEPARATOR) || dot == token.length() - 1) {
      throw invalid();
    }
    byte[] payload;
    byte[] signature;
    try {
      payload = DECODER.decode(token.substring(0, dot));
      signature = DECODER.decode(token.substring(dot + 1));
    } catch (IllegalArgumentException e) {
      throw invalid();
    }
    if (!MessageDigest.isEqual(sign(payload), signature)) {
      throw invalid();
    }
    return parsePayload(payload);
  }

  private static byte[] payload(PageToken token) {
    ObjectNode root = JSON.createObjectNode();
    root.put("t", token.resourceType());
    root.put("f", token.filtersHash());
    ArrayNode keys = root.putArray("k");
    for (PageToken.Key key : token.keyset()) {
      ObjectNode item = keys.addObject();
      item.put("n", key.name());
      item.put("v", key.value());
    }
    try {
      return JSON.writeValueAsBytes(root);
    } catch (JacksonException e) {
      throw new IllegalStateException("Cannot serialize page token", e);
    }
  }

  private static PageToken parsePayload(byte[] payload) {
    try {
      JsonNode root = JSON.readTree(payload);
      if (root == null || !root.isObject()) {
        throw invalid();
      }
      String resourceType = text(root.get("t"));
      String filtersHash = text(root.get("f"));
      JsonNode keysNode = root.get("k");
      if (resourceType == null || filtersHash == null || keysNode == null || !keysNode.isArray()) {
        throw invalid();
      }
      List<PageToken.Key> keyset = new ArrayList<>();
      for (JsonNode item : keysNode) {
        if (!item.isObject()) {
          throw invalid();
        }
        String name = text(item.get("n"));
        String value = text(item.get("v"));
        if (name == null || value == null) {
          throw invalid();
        }
        keyset.add(new PageToken.Key(name, value));
      }
      return new PageToken(resourceType, filtersHash, keyset);
    } catch (JacksonException | IllegalArgumentException e) {
      throw invalid();
    }
  }

  private static String text(JsonNode node) {
    return node != null && node.isString() ? node.asString() : null;
  }

  private byte[] sign(byte[] payload) {
    try {
      Mac mac = Mac.getInstance(ALGORITHM);
      mac.init(new SecretKeySpec(key, ALGORITHM));
      return mac.doFinal(payload);
    } catch (GeneralSecurityException e) {
      // The JVM always provides HmacSHA256; a failure here is a configuration error, not a client
      // error.
      throw new IllegalStateException("HmacSHA256 is unavailable", e);
    }
  }

  private static byte[] digest(String algorithm, byte[] value) {
    try {
      return MessageDigest.getInstance(algorithm).digest(value);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(algorithm + " is unavailable", e);
    }
  }

  /** Length-prefixed field so concatenation cannot be ambiguous. */
  private static void field(StringBuilder out, String value) {
    if (value == null) {
      out.append('~');
    } else {
      out.append(value.length()).append(':').append(value);
    }
    out.append(';');
  }

  private static ShpException invalid() {
    return new ShpException("validation-failed");
  }
}
