package org.shp.clinical.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.bson.Document;
import org.hl7.fhir.r4.model.PractitionerRole;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.shp.clinical.infra.TerminologyService;
import org.shp.web.errors.ShpException;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.data.mongodb.core.MongoTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * CLN-02 against the REAL terminology catalogue: {@link ProfileValidatorTest} stubs the terminology
 * service with two codes, so it cannot show that every catalogued specialty and role is accepted or
 * that the validator expects the same code-system URLs as the catalogue. This test feeds the real
 * {@code contracts/terminology} files (as embedded in the clinical jar) through the real {@link
 * TerminologyService} and the validator.
 */
class ProfileValidatorCatalogueTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-11-17T00:00:00Z"), ZoneOffset.UTC);

  private static final ProfileValidator VALIDATOR = new ProfileValidator(realTerminology(), CLOCK);

  private static JsonNode catalogue(String system) throws IOException {
    Resource file =
        new PathMatchingResourcePatternResolver()
            .getResource("classpath:terminology/" + system + ".json");
    assertThat(file.exists()).as("terminology/" + system + ".json on the classpath").isTrue();
    return JSON.readTree(file.getInputStream());
  }

  /** The real service, fed with documents of the shape the loader stores, from the real files. */
  private static TerminologyService realTerminology() {
    try {
      List<Document> documents = new ArrayList<>();
      for (Resource file :
          new PathMatchingResourcePatternResolver().getResources("classpath*:terminology/*.json")) {
        String name = file.getFilename();
        JsonNode root = JSON.readTree(file.getInputStream());
        List<Document> concepts = new ArrayList<>();
        root.get("concepts")
            .properties()
            .forEach(e ->
                concepts.add(
                    new Document("code", e.getKey()).append("label", e.getValue().asString())));
        documents.add(
            new Document()
                .append("_id", name.substring(0, name.length() - ".json".length()))
                .append("concepts", concepts));
      }
      MongoTemplate mongo = mock(MongoTemplate.class);
      when(mongo.findAll(Document.class, TerminologyService.COLLECTION)).thenReturn(documents);
      TerminologyService service = new TerminologyService(mongo);
      service.refresh();
      return service;
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  static Stream<String> specialtyCodes() throws IOException {
    List<String> codes = new ArrayList<>();
    catalogue("specialty").get("concepts").properties().forEach(e -> codes.add(e.getKey()));
    return codes.stream();
  }

  static Stream<String> roleCodes() throws IOException {
    List<String> codes = new ArrayList<>();
    catalogue("practitioner-role").get("concepts").properties().forEach(e -> codes.add(e.getKey()));
    return codes.stream();
  }

  private static PractitionerRole role(String roleCode, String specialtyCode) throws IOException {
    PractitionerRole role = ProfileValidatorTest.role();
    var roleCoding = role.getCodeFirstRep().getCodingFirstRep();
    roleCoding.setSystem(catalogue("practitioner-role").get("url").asString());
    roleCoding.setCode(roleCode);
    var specialtyCoding = role.getSpecialtyFirstRep().getCodingFirstRep();
    specialtyCoding.setSystem(catalogue("specialty").get("url").asString());
    specialtyCoding.setCode(specialtyCode);
    return role;
  }

  @ParameterizedTest(name = "specialty {0} is accepted")
  @MethodSource("specialtyCodes")
  void everyCataloguedSpecialtyIsAccepted(String code) {
    assertThatCode(() -> VALIDATOR.validate(role("doctor", code))).doesNotThrowAnyException();
  }

  @ParameterizedTest(name = "role {0} is accepted")
  @MethodSource("roleCodes")
  void everyCataloguedPractitionerRoleIsAccepted(String code) {
    assertThatCode(() -> VALIDATOR.validate(role(code, "cardiology"))).doesNotThrowAnyException();
  }

  @org.junit.jupiter.api.Test
  void theCatalogueHasTheTwelveDesignedSpecialties() throws IOException {
    assertThat(specialtyCodes()).hasSize(12);
  }

  @org.junit.jupiter.api.Test
  void aSpecialtyOutsideTheCatalogueIsRejected() {
    assertThatThrownBy(() -> VALIDATOR.validate(role("doctor", "astrology")))
        .isInstanceOfSatisfying(
            ShpException.class, e -> assertThat(e.info().code()).isEqualTo("validation-failed"));
  }
}
