package org.shp.fhir.search;

import java.util.List;

/**
 * A parsed search query.
 *
 * @param criteria các tiêu chí (AND)
 * @param count the number of results per page ({@code _count}: default 20, maximum 100)
 * @param pageToken the opaque {@code _page_token} of the next page, null on the first page (decoded
 *     in SHR-04)
 */
public record SearchQuery(
    String resourceType, List<Criterion> criteria, int count, String pageToken) {

  /** The criteria of one parameter, in the order they appeared. */
  public List<Criterion> criteria(String param) {
    return criteria.stream().filter(c -> c.param().equals(param)).toList();
  }
}
