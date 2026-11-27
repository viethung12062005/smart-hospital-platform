package org.shp.document.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.shp.web.errors.ShpException;
import org.springframework.mock.web.MockHttpServletRequest;

/** D1 request checks (10 §2): 400, 415 and 413 are decided before any link is signed. */
class UploadRequestTest {
  static final String PATIENT = "0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e01";
  static final String HASH = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08";

  static String json(String contentType, String size, String type, String fileName) {
    return "{\"patientId\":\"%s\",\"type\":\"%s\",\"title\":\"Synthetic blood test\","
            .formatted(PATIENT, type)
        + "\"fileName\":\"%s\",\"contentType\":\"%s\",\"size\":%s,\"sha256\":\"%s\"}"
            .formatted(fileName, contentType, size, HASH);
  }

  static MockHttpServletRequest request(String body) {
    var req = new MockHttpServletRequest("POST", "/api/v1/documents/uploads");
    req.setContent(body.getBytes(StandardCharsets.UTF_8));
    return req;
  }

  static String codeOf(String body) {
    try {
      UploadRequest.from(request(body));
    } catch (ShpException e) {
      return e.info().code();
    }
    return "accepted";
  }

  @Test
  void acceptsAValidPdf() {
    var r = UploadRequest.from(request(json("application/pdf", "482113", "11502-2", "a.pdf")));
    assertThat(r.patientId()).isEqualTo(UUID.fromString(PATIENT));
    assertThat(r.size()).isEqualTo(482113);
    assertThat(r.hash()).hasSize(32);
  }

  @Test
  void acceptsTheMaximumSize() {
    assertThat(
            UploadRequest.from(request(json("image/png", "20971520", "18748-4", "a.png"))).size())
        .isEqualTo(20_971_520L);
  }

  @Test
  void otherContentTypeIs415() {
    assertThat(codeOf(json("application/zip", "10", "11502-2", "a.zip")))
        .isEqualTo("invalid-file-type");
  }

  @Test
  void tooLargeOrNonPositiveSizeIs413() {
    assertThat(codeOf(json("application/pdf", "20971521", "11502-2", "a.pdf")))
        .isEqualTo("file-too-large");
    assertThat(codeOf(json("application/pdf", "0", "11502-2", "a.pdf")))
        .isEqualTo("file-too-large");
    assertThat(codeOf(json("application/pdf", "-5", "11502-2", "a.pdf")))
        .isEqualTo("file-too-large");
  }

  @Test
  void typeOutsideTheValueSetIs400() {
    assertThat(codeOf(json("application/pdf", "10", "99999-9", "a.pdf")))
        .isEqualTo("validation-failed");
  }

  @Test
  void pathCharactersInTheFileNameAre400() {
    for (String name : new String[] {"../a.pdf", "x/a.pdf", "x\\\\a.pdf"}) {
      assertThat(codeOf(json("application/pdf", "10", "11502-2", name)))
          .isEqualTo("validation-failed");
    }
  }

  @Test
  void malformedBodyOrMissingFieldsAre400() {
    assertThat(codeOf("not json")).isEqualTo("validation-failed");
    assertThat(codeOf("{}")).isEqualTo("validation-failed");
    assertThat(codeOf(json("application/pdf", "\"10\"", "11502-2", "a.pdf")))
        .isEqualTo("validation-failed");
    assertThatThrownBy(() -> UploadRequest.patientOf(request("{\"patientId\":\"x\"}")))
        .isInstanceOf(ShpException.class);
  }

  @Test
  void bodyIsReadOnceAndCached() {
    var req = request(json("application/pdf", "10", "11502-2", "a.pdf"));
    assertThat(UploadRequest.patientOf(req)).isEqualTo(UUID.fromString(PATIENT));
    assertThat(UploadRequest.from(req).fileName()).isEqualTo("a.pdf");
  }
}
