package org.shp.clinical.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.shp.clinical.app.ObservationCodeValidator;
import org.shp.web.errors.ShpException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The loader runs when the context starts, so the {@code terminology} collection is already
 * populated here. These tests prove the counts match the JSON, the load is idempotent, and the
 * Observation code validator is wired to the loaded catalogue.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"shp.auth.authority=https://idp.test/realms/shp", "shp.auth.audience=shp-api"})
@Testcontainers
class TerminologyLoaderTest {
  @Container @ServiceConnection
  static final MongoDBContainer DB =
      new MongoDBContainer(
              DockerImageName.parse(System.getenv().getOrDefault("SHP_TEST_MONGO_IMAGE", "mongo:8"))
                  .asCompatibleSubstituteFor("mongo"))
          .withEnv(
              "GLIBC_TUNABLES",
              "glibc.pthread.rseq=1") // kernel 6.19-7.0.13: SERVER-121912, see MongoTestBase
          .withReplicaSet();

  @Autowired MongoTemplate mongo;
  @Autowired TerminologyLoader loader;
  @Autowired TerminologyService terminology;
  @Autowired ObservationCodeValidator observationCode;

  @Test
  void loadsEveryCodeSystemWithTheExpectedCounts() {
    assertThat(concepts("icd10-subset")).isEqualTo(444);
    assertThat(concepts("loinc-vital-signs")).isEqualTo(10);
    assertThat(concepts("loinc-document-types")).isEqualTo(6);
    assertThat(mongo.count(new Query(), TerminologyService.COLLECTION)).isEqualTo(7);
  }

  @Test
  void isIdempotentAcrossTwoRuns() throws Exception {
    loader.run(null);
    loader.run(null);

    assertThat(mongo.count(new Query(), TerminologyService.COLLECTION)).isEqualTo(7);
    assertThat(concepts("icd10-subset")).isEqualTo(444);
    assertThat(concepts("loinc-vital-signs")).isEqualTo(10);
  }

  @Test
  void knownCodePassesAndUnknownCodeIsRejected() {
    assertThat(terminology.isKnown("loinc-vital-signs", "8867-4")).isTrue();
    assertThatCode(() -> observationCode.validate("8867-4")).doesNotThrowAnyException();

    assertThat(terminology.isKnown("loinc-vital-signs", "9999-9")).isFalse();
    assertThatThrownBy(() -> observationCode.validate("9999-9"))
        .isInstanceOfSatisfying(
            ShpException.class, e -> assertThat(e.info().code()).isEqualTo("validation-failed"));
  }

  private long concepts(String system) {
    Document doc = mongo.findById(system, Document.class, TerminologyService.COLLECTION);
    assertThat(doc).as("missing terminology document for %s", system).isNotNull();
    return doc.getList("concepts", Document.class).size();
  }
}
