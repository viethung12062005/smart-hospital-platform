package org.shp.data.mongo.search;

import java.util.List;
import org.bson.Document;

/**
 * One page of stored documents.
 *
 * @param items at most {@code _count} documents
 * @param next cursor for the next page, null when this is the last page
 */
public record SearchPage(List<Document> items, SearchCursor next) {
  public SearchPage {
    items = List.copyOf(items);
  }

  public boolean hasNext() {
    return next != null;
  }
}
