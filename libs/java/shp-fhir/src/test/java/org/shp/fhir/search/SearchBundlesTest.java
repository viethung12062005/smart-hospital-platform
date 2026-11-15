package org.shp.fhir.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Slot;
import org.junit.jupiter.api.Test;
import org.shp.fhir.FhirJson;

/** searchset Bundle assembly with self/next links (07 §4, SHR-04). */
class SearchBundlesTest {
  static final String BASE = "https://api.example.org/fhir/Slot";
  static final byte[] KEY = new byte[32];

  static {
    java.util.Arrays.fill(KEY, (byte) 0x41);
  }

  static Map<String, List<String>> query(String... pairs) {
    Map<String, List<String>> params = new LinkedHashMap<>();
    for (String pair : pairs) {
      int eq = pair.indexOf('=');
      params
          .computeIfAbsent(pair.substring(0, eq), k -> new ArrayList<>())
          .add(pair.substring(eq + 1));
    }
    return params;
  }

  static List<Slot> slots(String... ids) {
    List<Slot> slots = new ArrayList<>();
    for (String id : ids) {
      Slot slot = new Slot();
      slot.setId(id);
      slots.add(slot);
    }
    return slots;
  }

  static String link(Bundle bundle, String relation) {
    return bundle.getLink().stream()
        .filter(l -> relation.equals(l.getRelation()))
        .map(Bundle.BundleLinkComponent::getUrl)
        .findFirst()
        .orElse(null);
  }

  @Test
  void buildsSearchsetWithSelfAndNextLinks() {
    Bundle bundle =
        SearchBundles.searchset(
            BASE, query("specialty=cardiology", "_count=20"), slots("slot-1", "slot-2"), "next");

    assertThat(bundle.getType()).isEqualTo(Bundle.BundleType.SEARCHSET);
    assertThat(bundle.hasTotal()).isFalse();
    assertThat(bundle.getEntry()).hasSize(2);
    assertThat(bundle.getEntry().getFirst().getFullUrl()).isEqualTo(BASE + "/slot-1");
    assertThat(bundle.getEntry().getFirst().getResource()).isInstanceOf(Slot.class);
    assertThat(bundle.getEntry().getFirst().getSearch().getMode())
        .isEqualTo(Bundle.SearchEntryMode.MATCH);
    assertThat(link(bundle, SearchBundles.SELF))
        .isEqualTo(BASE + "?specialty=cardiology&_count=20");
    assertThat(link(bundle, SearchBundles.NEXT))
        .isEqualTo(BASE + "?specialty=cardiology&_count=20&_page_token=next");
  }

  @Test
  void lastPageHasOnlyTheSelfLink() {
    Bundle bundle = SearchBundles.searchset(BASE, query("status=free"), slots("slot-1"), null);

    assertThat(link(bundle, SearchBundles.SELF)).isEqualTo(BASE + "?status=free");
    assertThat(link(bundle, SearchBundles.NEXT)).isNull();
  }

  @Test
  void nextLinkReplacesTheCurrentPageToken() {
    Bundle bundle =
        SearchBundles.searchset(
            BASE,
            query("status=free", "_page_token=old-token", "_count=20"),
            slots("slot-2"),
            "new-token");

    assertThat(link(bundle, SearchBundles.SELF))
        .isEqualTo(BASE + "?status=free&_page_token=old-token&_count=20");
    assertThat(link(bundle, SearchBundles.NEXT))
        .isEqualTo(BASE + "?status=free&_count=20&_page_token=new-token");
  }

  @Test
  void linkValuesAreUrlEncoded() {
    Bundle bundle =
        SearchBundles.searchset(
            "https://api.example.org/fhir/Patient",
            query(
                "name=Nguyễn Văn A",
                "identifier=urn:shp:mrn|BN-2026-000123",
                "start=ge2026-12-01T08:00+07:00"),
            slots("patient-1"),
            null);

    assertThat(link(bundle, SearchBundles.SELF))
        .isEqualTo(
            "https://api.example.org/fhir/Patient"
                + "?name=Nguy%E1%BB%85n+V%C4%83n+A"
                + "&identifier=urn%3Ashp%3Amrn%7CBN-2026-000123"
                + "&start=ge2026-12-01T08%3A00%2B07%3A00");
  }

  @Test
  void bundleRoundTripsThroughFhirJson() {
    Bundle bundle =
        SearchBundles.searchset(
            BASE, query("status=free", "specialty=cardiology"), slots("slot-1"), "next");

    Bundle parsed = FhirJson.parse(FhirJson.serialize(bundle), Bundle.class);

    assertThat(parsed.getType()).isEqualTo(Bundle.BundleType.SEARCHSET);
    assertThat(parsed.getEntry()).hasSize(1);
    assertThat(parsed.getEntry().getFirst().getFullUrl()).isEqualTo(BASE + "/slot-1");
    assertThat(link(parsed, SearchBundles.SELF))
        .isEqualTo(BASE + "?status=free&specialty=cardiology");
    assertThat(link(parsed, SearchBundles.NEXT))
        .isEqualTo(BASE + "?status=free&specialty=cardiology&_page_token=next");
  }

  @Test
  void followingTheNextLinkDecodesTheKeyset() {
    SearchParser parser = SearchParser.standard();
    SearchQuery query = parser.parse("Slot", queryMap("specialty=cardiology", "_count=20"));
    PageTokens tokens = new PageTokens(KEY);
    List<PageToken.Key> keyset =
        List.of(
            new PageToken.Key("start_at", "2026-12-01T01:00:00Z"),
            new PageToken.Key("id", "slot-2"));
    String nextToken = tokens.encode(query, keyset);

    Bundle bundle =
        SearchBundles.searchset(
            BASE, queryMap("specialty=cardiology", "_count=20"), slots("slot-1"), nextToken);
    String next = link(bundle, SearchBundles.NEXT);
    String token = next.substring(next.indexOf("_page_token=") + "_page_token=".length());

    assertThat(tokens.decode(token, query).keyset()).containsExactlyElementsOf(keyset);
  }

  @Test
  void emptyResultStillHasASelfLinkAndNoEntries() {
    Bundle bundle = SearchBundles.searchset(BASE, Map.of(), List.of(), null);

    assertThat(bundle.getEntry()).isEmpty();
    assertThat(link(bundle, SearchBundles.SELF)).isEqualTo(BASE);
    assertThat(link(bundle, SearchBundles.NEXT)).isNull();
  }

  @Test
  void trailingSlashInBaseUrlIsIgnored() {
    Bundle bundle = SearchBundles.searchset(BASE + "/", Map.of(), slots("slot-1"), null);

    assertThat(bundle.getEntry().getFirst().getFullUrl()).isEqualTo(BASE + "/slot-1");
    assertThat(link(bundle, SearchBundles.SELF)).isEqualTo(BASE);
  }

  @Test
  void resultWithoutIdIsRejected() {
    assertThatThrownBy(() -> SearchBundles.searchset(BASE, Map.of(), List.of(new Slot()), null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  /** Build a servlet-style parameter map from {@code "a=1"} pairs. */
  static Map<String, List<String>> queryMap(String... pairs) {
    Map<String, String[]> params = new LinkedHashMap<>();
    for (String pair : pairs) {
      int eq = pair.indexOf('=');
      String key = pair.substring(0, eq);
      String value = pair.substring(eq + 1);
      String[] old = params.getOrDefault(key, new String[0]);
      String[] next = java.util.Arrays.copyOf(old, old.length + 1);
      next[old.length] = value;
      params.put(key, next);
    }
    return SearchParser.fromServlet(params);
  }
}
