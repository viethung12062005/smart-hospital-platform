package org.shp.audit.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import java.time.Duration;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.shp.audit.AuditEvents;
import org.shp.audit.AuditTestContainers;
import org.shp.audit.infra.AuditSpool;
import org.shp.messaging.PoisonEventException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;

/**
 * The audit consumer end to end on real MongoDB (replica set) and Azurite: the bus is simulated by
 * calling the same consumer function Spring Cloud Stream binds to {@code shp-events}.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "shp.auth.authority=https://idp.test/realms/shp",
      "shp.auth.audience=shp-api",
      "shp.audit-blob.container=audit-it",
      "shp.audit-blob.instance=it-0",
      "shp.audit-blob.create-container-if-missing=true",
      "shp.audit-batch.max-records=3",
      "shp.audit-batch.max-age=2s",
      "shp.audit-batch.poll-interval=100ms"
    })
@Testcontainers
class AuditPipelineIntegrationTest {

  @Container @ServiceConnection static final MongoDBContainer DB = AuditTestContainers.mongo();
  @Container static final GenericContainer<?> AZURITE = AuditTestContainers.azurite();

  @DynamicPropertySource
  static void blob(DynamicPropertyRegistry registry) {
    registry.add(
        "shp.audit-blob.connection-string", () -> AuditTestContainers.connectionString(AZURITE));
  }

  @Autowired
  @Qualifier("auditEvents")
  Consumer<Message<byte[]>> consumer;

  @Autowired MongoTemplate mongo;
  @Autowired AuditSpool spool;

  private BlobContainerClient container() {
    return new BlobServiceClientBuilder()
        .connectionString(AuditTestContainers.connectionString(AZURITE))
        .buildClient()
        .getBlobContainerClient("audit-it");
  }

  /** Number of times the eventId appears in all batches written so far. */
  private int occurrences(UUID eventId) {
    int n = 0;
    for (var item : container().listBlobs()) {
      String text = container().getBlobClient(item.getName()).downloadContent().toString();
      n += text.split(eventId.toString(), -1).length - 1;
    }
    return n;
  }

  private void deliver(UUID eventId) {
    consumer.accept(MessageBuilder.withPayload(AuditEvents.bytes(eventId)).build());
  }

  @BeforeEach
  void emptySpool() {
    mongo.dropCollection(AuditSpool.SPOOL);
    mongo.dropCollection(AuditSpool.PLAN);
    spool.ensureCollections();
  }

  @Test
  void aValidEventIsBatchedIntoTheWormLogAfterTheMaximumAge() {
    UUID id = UUID.randomUUID();

    deliver(id);

    assertThat(spool.count("it-0")).isEqualTo(1);
    await().atMost(Duration.ofSeconds(20)).until(() -> occurrences(id) == 1);
    await().atMost(Duration.ofSeconds(5)).until(() -> spool.count("it-0") == 0);
  }

  @Test
  void aBatchIsWrittenAsSoonAsTheMaximumRecordCountIsWaiting() {
    // Three records reach max-records (3): they are written together in one batch.
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    UUID c = UUID.randomUUID();
    deliver(a);
    deliver(b);
    deliver(c);

    await().atMost(Duration.ofSeconds(20)).until(() -> occurrences(c) == 1);
    assertThat(occurrences(a)).isEqualTo(1);
    assertThat(occurrences(b)).isEqualTo(1);
  }

  @Test
  void theSameEventDeliveredTwiceIsWrittenOnce() {
    UUID id = UUID.randomUUID();

    deliver(id);
    deliver(id);
    deliver(id);

    assertThat(spool.count("it-0")).isEqualTo(1);
    await().atMost(Duration.ofSeconds(20)).until(() -> occurrences(id) >= 1);
    await().atMost(Duration.ofSeconds(5)).until(() -> spool.count("it-0") == 0);
    // Redelivery after the batch was written (the inbox marker still holds): not written again.
    deliver(id);
    assertThat(spool.count("it-0")).isZero();
    assertThat(occurrences(id)).isEqualTo(1);
  }

  @Test
  void anEventThatBreaksTheSchemaIsRejectedAndNotStored() {
    String bad =
        AuditEvents.json(UUID.randomUUID()).replace("\"action\":\"read\"", "\"action\":\"peek\"");

    assertThatThrownBy(() -> consumer.accept(MessageBuilder.withPayload(bad.getBytes()).build()))
        .isInstanceOf(PoisonEventException.class)
        .hasMessageContaining("/action")
        .hasMessageNotContaining("peek");
    assertThat(spool.count("it-0")).isZero();
  }

  @Test
  void otherEventTypesAreIgnored() {
    String other = AuditEvents.json(UUID.randomUUID()).replace("audit.recorded", "audit.other");
    consumer.accept(MessageBuilder.withPayload(other.getBytes()).build());
    assertThat(spool.count("it-0")).isZero();
  }
}
