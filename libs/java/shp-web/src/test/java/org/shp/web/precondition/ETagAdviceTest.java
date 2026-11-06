package org.shp.web.precondition;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.shp.events.CloudEvents;
import tools.jackson.databind.JsonNode;

/**
 * ETag chỉ được phát khi If-Match đọc lại được đúng version đó (07 §3). Test đơn vị cho {@link
 * ETagAdvice#versionOf}: body FHIR thật đi qua đây dưới cả dạng Map và JsonNode.
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
    // âm hoặc quá 18 chữ số: phát ra thì client không dùng lại được làm If-Match
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
    // Jackson đọc FHIR thành JsonNode; nhánh này từng bị bỏ sót nên ETag mất trên response thật
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
