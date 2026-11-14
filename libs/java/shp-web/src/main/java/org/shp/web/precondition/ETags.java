package org.shp.web.precondition;

import java.util.regex.Pattern;
import org.shp.web.errors.ShpException;

/** SHP's ETag/If-Match (07 §3): weak ETag {@code W/"<versionId>"}, versionId is an integer. */
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
   * Version in {@code meta.versionId}/{@link Versioned#version()}, or null if it is not a number in
   * the range produced by {@link #format} that {@link #parseIfMatch} can read back. Shared so
   * emitted ETags can always be used as If-Match.
   */
  static Long tryParseVersion(Object versionId) {
    String s =
        versionId instanceof Number || versionId instanceof String
            ? versionId.toString().strip()
            : "";
    return VERSION.matcher(s).matches() ? Long.valueOf(s) : null;
  }

  /**
   * Version in If-Match. Missing → 428 {@code precondition-required}; wrong form {@code
   * W/"<number>"} (including {@code *}, strong ETags, multiple values) → 412 {@code
   * version-conflict} because it matches no version.
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
