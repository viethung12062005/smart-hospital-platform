package org.shp.data.mongo.search;

/**
 * Position of the last item returned by a page: a keyset on {@code _id}, which is unique and
 * time-ordered (UUIDv7). The API boundary encodes this position in the opaque, signed {@code
 * _page_token} (SHR-04); the executor never interprets that token itself.
 *
 * @param afterId {@code _id} of the last item of the previous page
 */
public record SearchCursor(String afterId) {
  public SearchCursor {
    if (afterId == null || afterId.isBlank()) {
      throw new IllegalArgumentException("afterId must not be blank");
    }
  }
}
