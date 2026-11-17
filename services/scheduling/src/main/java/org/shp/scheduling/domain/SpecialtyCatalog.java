package org.shp.scheduling.domain;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Locale;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Valid medical specialty codes. The codes are read from the shared terminology catalogue {@code
 * contracts/terminology/dist/specialty.json}, which the module's resource configuration embeds in
 * the jar as {@code terminology/specialty.json}, so this class cannot drift from the contract.
 */
public final class SpecialtyCatalog {
  static final String RESOURCE = "terminology/specialty.json";

  /** Code system URL, as stated by the catalogue. */
  public static final String SYSTEM;

  private static final Set<String> CODES;

  static {
    try (InputStream in = SpecialtyCatalog.class.getClassLoader().getResourceAsStream(RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException("Terminology catalogue not on the classpath: " + RESOURCE);
      }
      JsonNode root = JsonMapper.builder().build().readTree(in);
      SYSTEM = root.get("url").asString();
      CODES = Set.copyOf(root.get("concepts").propertyNames());
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot read " + RESOURCE, e);
    }
    if (CODES.isEmpty()) {
      throw new IllegalStateException("Terminology catalogue has no concepts: " + RESOURCE);
    }
  }

  private SpecialtyCatalog() {}

  public static boolean isValid(String code) {
    return code != null && CODES.contains(code.strip().toLowerCase(Locale.ROOT));
  }

  /** The valid codes (immutable). */
  public static Set<String> codes() {
    return CODES;
  }
}
