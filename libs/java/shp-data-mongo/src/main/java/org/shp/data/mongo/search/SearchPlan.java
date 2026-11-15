package org.shp.data.mongo.search;

import org.springframework.data.mongodb.core.query.Query;

/**
 * A compiled search: the collection, the Spring Data query (including sort, limit and hint) and the
 * hinted index name ({@code null} when the catalog has no suitable index).
 *
 * @param collection Mongo collection the query runs on
 * @param query compiled query, limited to {@code _count + 1} to detect the next page
 * @param index hinted index, may be null
 */
public record SearchPlan(String collection, Query query, String index) {}
