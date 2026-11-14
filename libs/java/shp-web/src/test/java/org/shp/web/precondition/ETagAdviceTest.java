package org.shp.web.precondition;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.shp.events.CloudEvents;
import tools.jackson.databind.JsonNode;

/**
 * An ETag is only emitted when If-Match can read that exact version back (07 §3). Unit tests for
 * {@link ETagAdvice#versionOf}: real FHIR bodies pass through here as both Map and JsonNode.
 */
class ETagAdviceTest {
  private static JsonNode json(String s) {
    return CloudEvents.JSON.readTree(s);
  }

  private static Versioned versioned(long version) {
    return () -> version;
  }

  @Test
  void versionedDtoUsesItsVersion() {
    assertThat(ETagAdvice.versionOf(versioned(7))).isEqualTo(7L);
    assertThat(ETagAdvice.versionOf(versioned(0))).isEqualTo(0L);
  }

  @Test
  void versionThatIfMatchWouldRejectPublishesNoETag() {
    // negative or more than 18 digits: if emitted, the client could not reuse it as If-Match
    assertThat(ETagAdvice.versionOf(versioned(-1))).isNull();
    assertThat(ETagAdvice.versionOf(versioned(1_000_000_000_000_000_000L))).isNull();
  }

  @Test
  void fhirMapReadsMetaVersionIdAsNumberOrString() {
    assertThat(ETagAdvice.versionOf(Map.of("meta", Map.of("versionId", 5)))).isEqualTo(5L);
    assertThat(ETagAdvice.versionOf(Map.of("meta", Map.of("versionId", "5")))).isEqualTo(5L);
    assertThat(ETagAdvice.versionOf(Map.of("meta", Map.of("versionId", "abc")))).isNull();
    assertThat(ETagAdvice.versionOf(Map.of("resourceType", "Patient"))).isNull();
  }

  @Test
  void fhirJsonNodeReadsMetaVersionId() {
    // Jackson reads FHIR into JsonNode; this branch was once missed, so ETags disappeared on real
    // responses
    assertThat(ETagAdvice.versionOf(json("{\"meta\":{\"versionId\":5}}"))).isEqualTo(5L);
    assertThat(ETagAdvice.versionOf(json("{\"meta\":{\"versionId\":\"5\"}}"))).isEqualTo(5L);
    assertThat(ETagAdvice.versionOf(json("{\"meta\":{\"versionId\":null}}"))).isNull();
    assertThat(ETagAdvice.versionOf(json("{\"meta\":{\"versionId\":-1}}"))).isNull();
    assertThat(ETagAdvice.versionOf(json("{\"resourceType\":\"Patient\"}"))).isNull();
  }

  @Test
  void bundleAndListBodiesHaveNoVersion() {
    assertThat(ETagAdvice.versionOf(Map.of("resourceType", "Bundle"))).isNull();
    assertThat(ETagAdvice.versionOf(json("[{\"meta\":{\"versionId\":5}}]"))).isNull();
    assertThat(ETagAdvice.versionOf(null)).isNull();
  }
}
