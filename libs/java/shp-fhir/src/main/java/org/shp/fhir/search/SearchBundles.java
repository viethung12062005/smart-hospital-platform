package org.shp.fhir.search;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Resource;

/**
 * Build the FHIR {@code Bundle} of a search response (07 §4).
 *
 * <p>The bundle has {@code type=searchset}, one {@code entry} per result with its {@code fullUrl}
 * and {@code search.mode=match}, and two links: {@code self} is the current request URL and {@code
 * next} is the current URL with {@code _page_token} replaced by the token for the next page. Every
 * parameter value is URL-encoded. {@code total} is not set (it is costly; 07 §4 returns it only for
 * {@code _total=accurate}).
 */
public final class SearchBundles {
  /** Link relation of the current page (FHIR link relation code). */
  public static final String SELF = "self";

  /** Link relation of the next page (FHIR link relation code). */
  public static final String NEXT = "next";

  private static final String PAGE_TOKEN = "_page_token";

  private SearchBundles() {}

  /**
   * @param baseUrl absolute URL of the search endpoint including the resource type, for example
   *     {@code https://api.example.org/fhir/Slot}; a trailing slash is ignored
   * @param query the request parameters in request order; values are encoded in the links
   * @param results one page of resources, each with an {@code id}
   * @param nextPageToken token for the next page, or null on the last page
   */
  public static Bundle searchset(
      String baseUrl,
      Map<String, List<String>> query,
      List<? extends Resource> results,
      String nextPageToken) {
    if (baseUrl == null || baseUrl.isBlank()) {
      throw new IllegalArgumentException("baseUrl is required");
    }
    if (query == null) {
      throw new IllegalArgumentException("query is required");
    }
    if (results == null) {
      throw new IllegalArgumentException("results is required");
    }
    String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;

    Bundle bundle = new Bundle();
    bundle.setType(Bundle.BundleType.SEARCHSET);
    bundle.addLink().setRelation(SELF).setUrl(url(base, query, null));
    if (nextPageToken != null && !nextPageToken.isBlank()) {
      bundle.addLink().setRelation(NEXT).setUrl(url(base, query, nextPageToken));
    }

    for (Resource resource : results) {
      String id = resource.getIdElement().getIdPart();
      if (id == null || id.isBlank()) {
        throw new IllegalArgumentException("Search result has no id");
      }
      Bundle.BundleEntryComponent entry = bundle.addEntry();
      entry.setFullUrl(base + "/" + id);
      entry.setResource(resource);
      entry.getSearch().setMode(Bundle.SearchEntryMode.MATCH);
    }
    return bundle;
  }

  /**
   * Serialize the query string. A non-null {@code replacementToken} drops every existing {@code
   * _page_token} and appends the new one; null keeps the query exactly as requested (the self
   * link).
   */
  private static String url(String base, Map<String, List<String>> query, String replacementToken) {
    List<String> parts = new ArrayList<>();
    query.forEach((name, values) -> {
      if (replacementToken != null && PAGE_TOKEN.equals(name)) {
        return;
      }
      for (String value : values) {
        parts.add(encode(name) + "=" + encode(value));
      }
    });
    if (replacementToken != null) {
      parts.add(PAGE_TOKEN + "=" + encode(replacementToken));
    }
    return parts.isEmpty() ? base : base + "?" + String.join("&", parts);
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }
}
