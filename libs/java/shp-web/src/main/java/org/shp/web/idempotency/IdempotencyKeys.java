package org.shp.web.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.shp.events.CloudEvents;
import org.shp.web.errors.ShpException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

/** Phần không phụ thuộc DB của Idempotency-Key (07 §3): kiểm header và băm body chuẩn hóa. */
public final class IdempotencyKeys {
  public static final String HEADER = "Idempotency-Key";

  private IdempotencyKeys() {}

  /**
   * Thiếu → 428 {@code idempotency-key-required}; sai → 400 {@code validation-failed} (8–128 ký tự
   * in được, không khoảng trắng).
   */
  public static String require(String header) {
    if (header == null || header.isBlank()) {
      throw new ShpException("idempotency-key-required");
    }
    if (header.length() < 8
        || header.length() > 128
        || header.chars().anyMatch(c -> c <= ' ' || c > '~')) {
      throw new ShpException(
          "validation-failed", "Idempotency-Key phải dài 8–128 ký tự, không chứa khoảng trắng.");
    }
    return header;
  }

  /**
   * SHA-256 của body chuẩn hóa (JSON: sắp xếp tên thuộc tính, bỏ khoảng trắng; không phải JSON: băm
   * nguyên byte).
   */
  public static byte[] hashBody(byte[] body) {
    byte[] canonical;
    try {
      JsonNode node = CloudEvents.JSON.readTree(body);
      canonical =
          CloudEvents.JSON.writeValueAsString(canonicalize(node)).getBytes(StandardCharsets.UTF_8);
    } catch (JacksonException e) {
      canonical = body;
    }
    try {
      return MessageDigest.getInstance("SHA-256").digest(canonical);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static Object canonicalize(JsonNode n) {
    if (n.isObject()) {
      Map<String, Object> m = new TreeMap<>();
      n.properties().forEach(e -> m.put(e.getKey(), canonicalize(e.getValue())));
      return m;
    }
    if (n.isArray()) {
      List<Object> l = new ArrayList<>();
      n.forEach(x -> l.add(canonicalize(x)));
      return l;
    }
    return n;
  }
}
