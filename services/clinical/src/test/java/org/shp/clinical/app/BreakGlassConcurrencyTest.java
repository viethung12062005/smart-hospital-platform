package org.shp.clinical.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.mongodb.client.MongoClients;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.shp.clinical.infra.MongoClinicalReads;
import org.shp.web.errors.ShpException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class BreakGlassConcurrencyTest {
  @Container
  static final MongoDBContainer DB =
      new MongoDBContainer(
              DockerImageName.parse(System.getenv().getOrDefault("SHP_TEST_MONGO_IMAGE", "mongo:8"))
                  .asCompatibleSubstituteFor("mongo"))
          .withEnv("GLIBC_TUNABLES", "glibc.pthread.rseq=1")
          .withReplicaSet();

  @Test
  void competingStartsAllowOnlyOneAndExpiredSessionCanBeReplaced() throws Exception {
    try (var client = MongoClients.create(DB.getReplicaSetUrl())) {
      var mongo = new MongoTemplate(client, "breakglass_test");
      mongo.insert(new Document("_id", "patient").append("resource", new Document()), "patients");
      var now = Instant.parse("2026-12-03T02:00:00Z");
      var reads = new MongoClinicalReads(mongo);
      var service = new BreakGlassService(mongo, reads, Clock.fixed(now, ZoneOffset.UTC));
      var gate = new CountDownLatch(1);
      try (var pool = Executors.newFixedThreadPool(2)) {
        var first = pool.submit(() -> start(service, gate));
        var second = pool.submit(() -> start(service, gate));
        gate.countDown();
        assertThat(java.util.List.of(first.get(), second.get()))
            .containsExactlyInAnyOrder(201, 409);
      }
      assertThat(reads.breakGlass("doctor", "patient", now)).contains(now.plusSeconds(3600));
      assertThat(reads.breakGlass("doctor", "patient", now.plusSeconds(3600))).isEmpty();
      var later =
          new BreakGlassService(mongo, reads, Clock.fixed(now.plusSeconds(3600), ZoneOffset.UTC));
      assertThat(later.start("doctor", "patient", "Emergency reason for test"))
          .isEqualTo(now.plusSeconds(7200));
      assertThat(mongo.getCollection("break_glass_sessions").countDocuments()).isEqualTo(1);
    }
  }

  private static int start(BreakGlassService service, CountDownLatch gate)
      throws InterruptedException {
    gate.await();
    try {
      service.start("doctor", "patient", "Emergency reason for test");
      return 201;
    } catch (ShpException e) {
      return e.info().status();
    }
  }
}
