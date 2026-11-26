package org.shp.audit.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.mongodb.client.MongoClients;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.shp.audit.AuditEvents;
import org.shp.audit.AuditTestContainers;
import org.shp.audit.infra.AuditSpool;
import org.shp.audit.infra.LokiAuditSink;
import org.shp.audit.infra.WormAuditLog;
import org.shp.events.CloudEvents;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;

/**
 * Shipping to the query sink (05-storage-audit-blob §1.3, NFR-SEC5) on real MongoDB and Azurite,
 * with a real HTTP server standing in for Loki: a record is sent on the first poll (seconds, not
 * the 60 s batch), and a sink error loses nothing — the records stay, are sent again once the sink
 * is back, and the WORM batch is still written exactly once.
 */
@Testcontainers
class AuditSinkShipperTest {

  @Container static final MongoDBContainer DB = AuditTestContainers.mongo();
  @Container static final GenericContainer<?> AZURITE = AuditTestContainers.azurite();

  private static final Instant T0 = Instant.parse("2026-11-18T10:00:00Z");

  private static MongoTemplate mongo;
  private static BlobContainerClient blobs;
  private static HttpServer loki;
  private static final AtomicInteger STATUS = new AtomicInteger(204);
  private static final List<String> PUSHES = new CopyOnWriteArrayList<>();
  private static int seq;

  @BeforeAll
  static void start() throws Exception {
    var factory =
        new SimpleMongoClientDatabaseFactory(
            MongoClients.create(DB.getReplicaSetUrl()), "audit-shipper-test");
    mongo = new MongoTemplate(factory);
    new AuditSpool(mongo, Clock.systemUTC()).ensureCollections();
    blobs =
        new BlobServiceClientBuilder()
            .connectionString(AuditTestContainers.connectionString(AZURITE))
            .buildClient()
            .getBlobContainerClient("audit-shipper");
    blobs.createIfNotExists();
    loki = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    loki.createContext(
        "/loki/api/v1/push",
        exchange -> {
          String body =
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
          int status = STATUS.get();
          if (status / 100 == 2) {
            PUSHES.add(body);
          }
          exchange.sendResponseHeaders(status, -1);
          exchange.close();
        });
    loki.start();
  }

  @AfterAll
  static void stop() {
    loki.stop(0);
    mongo.getMongoDatabaseFactory().getMongoDatabase().drop();
  }

  private static LokiAuditSink sink() {
    return new LokiAuditSink("http://127.0.0.1:" + loki.getAddress().getPort());
  }

  private static Clock at(Instant t) {
    return Clock.fixed(t, ZoneOffset.UTC);
  }

  private static void spoolOne(AuditSpool spool, String instance, UUID id) {
    spool.add(id.toString(), instance, CloudEvents.parse(AuditEvents.json(id)).data().toString());
  }

  @Test
  void aRecordReachesTheSinkOnTheFirstPollWithoutWaitingForTheBatch() {
    String instance = "ship-" + (seq++);
    PUSHES.clear();
    STATUS.set(204);
    var spool = new AuditSpool(mongo, at(T0));
    UUID id = UUID.randomUUID();
    spoolOne(spool, instance, id);

    int sent = new AuditSinkShipper(spool, sink(), instance, 100).shipDue();

    assertThat(sent).isEqualTo(1);
    assertThat(PUSHES).hasSize(1);
    assertThat(PUSHES.get(0))
        .contains(
            id.toString(), "kc-doctor-demo", "\"service\":\"clinical\"", "\"job\":\"shp-audit\"");
    assertThat(spool.unshipped(instance, 10)).isEmpty();
    assertThat(spool.count(instance)).isEqualTo(1); // still waiting for the WORM batch
  }

  @Test
  void aSinkErrorKeepsTheRecordsAndTheNextPollSendsThem() {
    String instance = "ship-" + (seq++);
    PUSHES.clear();
    var spool = new AuditSpool(mongo, at(T0));
    spoolOne(spool, instance, UUID.randomUUID());
    spoolOne(spool, instance, UUID.randomUUID());
    var shipper = new AuditSinkShipper(spool, sink(), instance, 100);

    STATUS.set(503);
    assertThatThrownBy(shipper::shipDue).hasMessageContaining("HTTP 503");
    assertThat(spool.unshipped(instance, 10)).hasSize(2);

    STATUS.set(204);
    assertThat(shipper.shipDue()).isEqualTo(2);
    assertThat(PUSHES).hasSize(1);
    assertThat(shipper.shipDue()).isZero(); // nothing is sent twice
  }

  @Test
  void aSinkOutageNeverLetsTheBatchFlusherRemoveRecordsTheSinkHasNotAccepted() {
    String instance = "ship-" + (seq++);
    PUSHES.clear();
    var spool = new AuditSpool(mongo, at(T0));
    UUID id = UUID.randomUUID();
    spoolOne(spool, instance, id);
    var worm = new WormAuditLog(blobs, instance, at(T0.plusSeconds(61)));
    var shipper = new AuditSinkShipper(spool, sink(), instance, 100);
    var flusher =
        new AuditBatchFlusher(
            spool, worm, instance, 5000, Duration.ofSeconds(60), at(T0.plusSeconds(61)), shipper);

    STATUS.set(503);
    assertThatThrownBy(flusher::flushIfDue).hasMessageContaining("HTTP 503");
    assertThat(spool.count(instance)).isEqualTo(1); // not lost

    STATUS.set(204);
    assertThat(flusher.flushIfDue()).isEmpty(); // the blob was already written; clean-up done now
    assertThat(spool.count(instance)).isZero();
    assertThat(PUSHES).hasSize(1);
    long blobsWithRecord =
        blobs.listBlobs().stream()
            .filter(b -> b.getName().contains("/" + instance + "-"))
            .filter(b ->
                blobs
                    .getBlobClient(b.getName())
                    .downloadContent()
                    .toString()
                    .contains(id.toString()))
            .count();
    assertThat(blobsWithRecord).isEqualTo(1); // written once
  }
}
