package org.shp.fhir.search;

import java.util.List;

/**
 * Decoded contents of an opaque {@code _page_token}: the resource type and a hash of the filters
 * the token was issued for, plus the keyset of the last record on the page.
 *
 * <p>The token is signed but not encrypted. It must not contain filter values (a name or phone
 * number is PII): {@code filtersHash} is the only representation of the query.
 */
public record PageToken(String resourceType, String filtersHash, List<Key> keyset) {

  public PageToken {
    if (resourceType == null || resourceType.isBlank()) {
      throw new IllegalArgumentException("resourceType is required");
    }
    if (filtersHash == null || filtersHash.isBlank()) {
      throw new IllegalArgumentException("filtersHash is required");
    }
    if (keyset == null) {
      throw new IllegalArgumentException("keyset is required");
    }
    keyset = List.copyOf(keyset);
  }

  /** One named value of the keyset, for example {@code start_at} = {@code 2026-12-01T01:00:00Z}. */
  public record Key(String name, String value) {
    public Key {
      if (name == null || name.isBlank()) {
        throw new IllegalArgumentException("keyset key name is required");
      }
      if (value == null) {
        throw new IllegalArgumentException("keyset key value is required");
      }
    }
  }
}
