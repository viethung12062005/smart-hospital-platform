package org.shp.web.precondition;

import java.util.regex.Pattern;
import org.shp.web.errors.ShpException;

/** ETag/If-Match của SHP (07 §3): weak ETag {@code W/"<versionId>"}, versionId là số nguyên. */
public final class ETags {
  private static final Pattern WEAK = Pattern.compile("W/\"(\\d{1,18})\"");

  private ETags() {}

  /** {@code 3} → {@code W/"3"}. */
  public static String format(long version) {
    return "W/\"" + version + "\"";
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
