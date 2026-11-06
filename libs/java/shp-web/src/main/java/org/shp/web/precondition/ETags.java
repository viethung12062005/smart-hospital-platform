package org.shp.web.precondition;

import java.util.regex.Pattern;
import org.shp.web.errors.ShpException;

/** ETag/If-Match của SHP (07 §3): weak ETag {@code W/"<versionId>"}, versionId là số nguyên. */
public final class ETags {
  private static final String DIGITS = "\\d{1,18}";
  private static final Pattern WEAK = Pattern.compile("W/\"(" + DIGITS + ")\"");
  private static final Pattern VERSION = Pattern.compile(DIGITS);

  private ETags() {}

  /** {@code 3} → {@code W/"3"}. */
  public static String format(long version) {
    return "W/\"" + version + "\"";
  }

  /**
   * Version trong {@code meta.versionId}/{@link Versioned#version()}, hoặc null nếu không phải số
   * trong khoảng {@link #format} sinh ra mà {@link #parseIfMatch} đọc lại được. Dùng chung để ETag
   * phát ra luôn dùng được làm If-Match.
   */
  static Long tryParseVersion(Object versionId) {
    String s =
        versionId instanceof Number || versionId instanceof String
            ? versionId.toString().strip()
            : "";
    return VERSION.matcher(s).matches() ? Long.valueOf(s) : null;
  }

  /**
   * Version trong If-Match. Thiếu → 428 {@code precondition-required}; không đúng dạng {@code
   * W/"<số>"} (kể cả {@code *}, ETag mạnh, nhiều giá trị) → 412 {@code version-conflict} vì không
   * khớp được version nào.
   */
  public static long parseIfMatch(String header) {
    if (header == null || header.isBlank()) {
      throw new ShpException("precondition-required");
    }
    var m = WEAK.matcher(header.strip());
    if (!m.matches()) {
      throw new ShpException("version-conflict");
    }
    return Long.parseLong(m.group(1));
  }
}
