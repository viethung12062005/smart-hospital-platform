package org.shp.audit.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.mongodb.client.MongoClients;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.shp.audit.AuditEvents;
import org.shp.audit.AuditTestContainers;
import org.shp.audit.infra.AuditSpool;
import org.shp.audit.infra.WormAuditLog;
import org.shp.data.mongo.MongoInbox;
import org.shp.data.mongo.MongoMessaging;
import org.shp.events.CloudEvent;
import org.shp.events.CloudEvents;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;

/**
 * Failure behaviour of the intake and the batch flusher on real MongoDB and Azurite: a failing
 * spool write or a failing or interrupted blob write loses no record and writes none twice.
 */
@Testcontainers
class AuditBatchFlusherTest {

  @Container static final MongoDBContainer DB = AuditTestContainers.mongo();
  @Container static final GenericContainer<?> AZURITE = AuditTestContainers.azurite();

  private static final Instant T0 = Instant.parse("2026-11-18T10:00:00Z");

  private static MongoTemplate mongo;
  private static MongoTransactionManager txManager;
  private static BlobContainerClient blobs;
  private static int seq;

  @BeforeAll
  static void connect() {
    var factory =
        new SimpleMongoClientDatabaseFactory(
            MongoClients.create(DB.getReplicaSetUrl()), "audit-flusher-test");
    mongo = new MongoTemplate(factory);
    txManager = new MongoTransactionManager(factory);
    MongoMessaging.ensureIndexes(mongo, MongoMessaging.Store.INBOX);
    if (!mongo.collectionExists(MongoMessaging.INBOX)) {
      mongo.createCollection(MongoMessaging.INBOX);
    }
    new AuditSpool(mongo, Clock.systemUTC()).ensureCollections();
    blobs =
        new BlobServiceClientBuilder()
            .connectionString(AuditTestContainers.connectionString(AZURITE))
            .buildClient()
            .getBlobContainerClient("audit-flusher");
    blobs.createIfNotExists();
  }

  @AfterAll
  static void close() {
    mongo.getMongoDatabaseFactory().getMongoDatabase().drop();
  }

  /** A fresh chain (own instance id) per test. */
  private static String newInstance() {
    return "flush-" + (seq++);
  }

  private static Clock at(Instant t) {
    return Clock.fixed(t, ZoneOffset.UTC);
  }

  private static CloudEvent event(UUID id) {
    return CloudEvents.parse(AuditEvents.json(id));
  }

  private static int occurrences(String instance, UUID id) {
    int n = 0;
    for (var item : blobs.listBlobs()) {
      if (item.getName().contains("/" + instance + "-")) {
        String text = blobs.getBlobClient(item.getName()).downloadContent().toString();
        n += text.split(id.toString(), -1).length - 1;
      }
    }
    return n;
  }

  @Test
  void aFailedSpoolWriteRollsBackTheInboxMarkerSoARedeliveryIsProcessed() {
    String instance = newInstance();
    var inbox = new MongoInbox(mongo, new TransactionTemplate(txManager), Clock.systemUTC());
    AtomicBoolean fail = new AtomicBoolean(true);
    var spool =
        new AuditSpool(mongo, Clock.systemUTC()) {
          @Override
          public void add(String eventId, String instance, String line) {
            super.add(eventId, instance, line);
            if (fail.getAndSet(false)) {
              throw new IllegalStateException("simulated failure after the spool write");
            }
          }
        };
    var intake = new AuditIntake(spool, instance);
    UUID id = UUID.randomUUID();

    assertThatThrownBy(
            () -> inbox.handleOnce("audit", event(id), () -> intake.onRecorded(event(id))))
        .isInstanceOf(RuntimeException.class);
    assertThat(spool.count(instance)).isZero(); // rolled back with the marker

    // The bus redelivers: now it goes through, once.
    assertThat(inbox.handleOnce("audit", event(id), () -> intake.onRecorded(event(id)))).isTrue();
    assertThat(inbox.handleOnce("audit", event(id), () -> intake.onRecorded(event(id)))).isFalse();
    assertThat(spool.count(instance)).isEqualTo(1);
  }

  @Test
  void aBlobErrorKeepsTheRecordsAndTheNextRunWritesThemOnce() {
    String instance = newInstance();
    var spool = new AuditSpool(mongo, at(T0));
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    new AuditIntake(spool, instance).onRecorded(event(a));
    new AuditIntake(spool, instance).onRecorded(event(b));
    AtomicBoolean down = new AtomicBoolean(true);
    var worm =
        new WormAuditLog(blobs, instance, at(T0)) {
          @Override
          public synchronized Receipt append(List<String> lines) {
            if (down.get()) {
              throw new IllegalStateException("simulated storage outage");
            }
            return super.append(lines);
          }
        };
    var flusher =
        new AuditBatchFlusher(
            spool, worm, instance, 5000, Duration.ofSeconds(60), at(T0.plusSeconds(61)));

    assertThatThrownBy(flusher::flushIfDue).hasMessageContaining("outage");
    assertThat(spool.count(instance)).isEqualTo(2);
    assertThat(occurrences(instance, a)).isZero();

    down.set(false);
    var receipt = flusher.flushIfDue();

    assertThat(receipt).isPresent();
    assertThat(receipt.get().count()).isEqualTo(2);
    assertThat(occurrences(instance, a)).isEqualTo(1);
    assertThat(occurrences(instance, b)).isEqualTo(1);
    assertThat(spool.count(instance)).isZero();
    assertThat(spool.plan(instance)).isEmpty();
  }

  @Test
  void aCrashAfterTheBlobWriteDoesNotWriteTheBatchAgain() {
    String instance = newInstance();
    var spool = new AuditSpool(mongo, at(T0));
    UUID a = UUID.randomUUID();
    new AuditIntake(spool, instance).onRecorded(event(a));
    var crashing =
        new WormAuditLog(blobs, instance, at(T0)) {
          @Override
          public synchronized Receipt append(List<String> lines) {
            super.append(lines);
            throw new IllegalStateException("simulated crash after the blob was written");
          }
        };
    Clock due = at(T0.plusSeconds(61));
    assertThatThrownBy(() ->
            new AuditBatchFlusher(spool, crashing, instance, 5000, Duration.ofSeconds(60), due)
                .flushIfDue())
        .hasMessageContaining("crash");
    assertThat(spool.plan(instance)).isPresent();
    assertThat(occurrences(instance, a)).isEqualTo(1);

    // New process: a new WormAuditLog reloads the chain head from the container.
    var restarted = new WormAuditLog(blobs, instance, at(T0));
    var flusher =
        new AuditBatchFlusher(spool, restarted, instance, 5000, Duration.ofSeconds(60), due);
    assertThat(flusher.flushIfDue()).isEmpty();

    assertThat(occurrences(instance, a)).isEqualTo(1);
    assertThat(spool.count(instance)).isZero();
    assertThat(spool.plan(instance)).isEmpty();
  }

  @Test
  void aBatchIsNotDueBeforeTheMaximumAgeOrCount() {
    String instance = newInstance();
    var spool = new AuditSpool(mongo, at(T0));
    new AuditIntake(spool, instance).onRecorded(event(UUID.randomUUID()));
    var worm = new WormAuditLog(blobs, instance, at(T0));
    var flusher =
        new AuditBatchFlusher(
            spool, worm, instance, 3, Duration.ofSeconds(60), at(T0.plusSeconds(30)));

    assertThat(flusher.flushIfDue()).isEmpty();
    assertThat(spool.count(instance)).isEqualTo(1);

    // The count limit makes it due at once, and a batch never holds more than the limit.
    new AuditIntake(spool, instance).onRecorded(event(UUID.randomUUID()));
    new AuditIntake(spool, instance).onRecorded(event(UUID.randomUUID()));
    new AuditIntake(spool, instance).onRecorded(event(UUID.randomUUID()));
    var receipt = flusher.flushIfDue();
    assertThat(receipt).isPresent();
    assertThat(receipt.get().count()).isEqualTo(3);
    assertThat(spool.count(instance)).isEqualTo(1);
  }

  @Test
  void anEventWhoseRecordIdDiffersFromTheEnvelopeIdIsRejected() {
    String instance = newInstance();
    var spool = new AuditSpool(mongo, at(T0));
    CloudEvent e = event(UUID.randomUUID());
    CloudEvent wrong =
        new CloudEvent(
            e.specVersion(),
            UUID.randomUUID().toString(),
            e.source(),
            e.type(),
            e.subject(),
            e.time(),
            e.dataContentType(),
            e.dataSchema(),
            e.traceParent(),
            e.data());
    assertThatThrownBy(() -> new AuditIntake(spool, instance).onRecorded(wrong))
        .hasMessageContaining("/eventId");
    assertThat(spool.count(instance)).isZero();
  }
}
