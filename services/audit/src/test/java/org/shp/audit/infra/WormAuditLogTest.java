package org.shp.audit.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.azure.storage.blob.models.BlobStorageException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.shp.audit.domain.AuditBatch;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Hash chain on a real Blob endpoint (Azurite). Azurite does not implement immutability policies,
 * so rejecting edits or deletes of locked blobs (WORM) is NOT tested here: that needs a real
 * storage account (smoke test on UAT, AUD-08). These tests prove the chain, the blob naming, the
 * {@code batchHash} metadata and that an existing batch is never overwritten.
 */
@Testcontainers
class WormAuditLogTest {

  private static final String ACCOUNT = "shp";
  private static final int BLOB_PORT = 10000;
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-11-18T10:15:30Z"), ZoneOffset.UTC);

  // A random account key per run: nothing secret or well-known lives in the repository.
  private static final String KEY = randomKey();

  @Container
  static final GenericContainer<?> AZURITE =
      new GenericContainer<>(
              DockerImageName.parse(
                  System.getenv()
                      .getOrDefault(
                          "SHP_TEST_AZURITE_IMAGE",
                          "mcr.microsoft.com/azure-storage/azurite:3.37.0")))
          .withEnv("AZURITE_ACCOUNTS", ACCOUNT + ":" + KEY)
          .withCommand("azurite-blob", "--blobHost", "0.0.0.0", "--skipApiVersionCheck")
          .withExposedPorts(BLOB_PORT);

  private static BlobServiceClientBuilder builder;

  @BeforeAll
  static void connect() {
    String endpoint =
        "http://" + AZURITE.getHost() + ":" + AZURITE.getMappedPort(BLOB_PORT) + "/" + ACCOUNT;
    String conn =
        "DefaultEndpointsProtocol=http;AccountName="
            + ACCOUNT
            + ";AccountKey="
            + KEY
            + ";BlobEndpoint="
            + endpoint;
    builder = new BlobServiceClientBuilder().connectionString(conn);
  }

  private static String randomKey() {
    byte[] key = new byte[64];
    new SecureRandom().nextBytes(key);
    return Base64.getEncoder().encodeToString(key);
  }

  private BlobContainerClient freshContainer(String name) {
    BlobContainerClient container = builder.buildClient().getBlobContainerClient(name);
    container.createIfNotExists();
    return container;
  }

  private static List<String> records(String prefix, int n) {
    return IntStream.range(0, n)
        .mapToObj(i -> "{\"eventId\":\"" + prefix + "-" + i + "\",\"action\":\"read\"}")
        .toList();
  }

  private static String[] lines(WormAuditLog log, String blob) {
    return log.read(blob).toString().split("\n");
  }

  @Test
  void firstBatchIsAnAppendBlobWithGenesisHeaderAndMatchingHashMetadata() {
    var container = freshContainer("t-first");
    var log = new WormAuditLog(container, "audit-0", CLOCK);
    var recs = records("a", 3);

    var receipt = log.append(recs);

    assertThat(receipt.blobName()).isEqualTo("v1/2026/11/18/10/audit-0-1.ndjson");
    assertThat(receipt.count()).isEqualTo(3);
    var props = container.getBlobClient(receipt.blobName()).getProperties();
    assertThat(props.getBlobType().toString()).isEqualTo("AppendBlob");
    assertThat(props.getMetadata()).containsEntry("batchHash", receipt.batchHash());
    var lines = lines(log, receipt.blobName());
    assertThat(lines[0])
        .isEqualTo(
            "{\"type\":\"batch-header\",\"prevBatch\":null,\"prevHash\":\""
                + "0".repeat(64)
                + "\",\"count\":3}");
    assertThat(Arrays.asList(lines).subList(1, 4)).isEqualTo(recs);
    assertThat(receipt.batchHash()).isEqualTo(AuditBatch.hash(AuditBatch.GENESIS_HASH, recs));
  }

  @Test
  void nextBatchContainsThePreviousBatchHashAndPath() {
    var container = freshContainer("t-chain");
    var log = new WormAuditLog(container, "audit-0", CLOCK);

    var first = log.append(records("a", 2));
    var second = log.append(records("b", 2));
    var third = log.append(records("c", 1));

    assertThat(lines(log, second.blobName())[0])
        .contains("\"prevBatch\":\"" + first.blobName() + "\"")
        .contains("\"prevHash\":\"" + first.batchHash() + "\"")
        .contains("\"count\":2");
    assertThat(lines(log, third.blobName())[0])
        .contains("\"prevBatch\":\"" + second.blobName() + "\"")
        .contains("\"prevHash\":\"" + second.batchHash() + "\"");
    assertThat(second.blobName()).endsWith("audit-0-2.ndjson");
    assertThat(container.getBlobClient(second.blobName()).getProperties().getMetadata())
        .containsEntry("batchHash", AuditBatch.hash(first.batchHash(), records("b", 2)));
    assertThat(log.headHash()).isEqualTo(third.batchHash());
  }

  @Test
  void aRestartedInstanceContinuesItsOwnChainAndIgnoresOtherInstances() {
    var container = freshContainer("t-restart");
    var before = new WormAuditLog(container, "audit-0", CLOCK);
    before.append(records("a", 1));
    var last = before.append(records("b", 1));
    new WormAuditLog(container, "audit-1", CLOCK).append(records("other", 1));

    var after = new WormAuditLog(container, "audit-0", CLOCK);
    var next = after.append(records("c", 1));

    assertThat(next.blobName()).endsWith("audit-0-3.ndjson");
    assertThat(lines(after, next.blobName())[0])
        .contains("\"prevBatch\":\"" + last.blobName() + "\"")
        .contains("\"prevHash\":\"" + last.batchHash() + "\"");
  }

  @Test
  void aBatchLargerThanOneBlockIsWrittenCompleteWithTheRightHash() {
    var container = freshContainer("t-large");
    var log = new WormAuditLog(container, "audit-0", CLOCK);
    String filler = "x".repeat(3000);
    var recs =
        IntStream.range(0, 2500)
            .mapToObj(i -> "{\"eventId\":\"big-" + i + "\",\"note\":\"" + filler + "\"}")
            .toList(); // about 7.5 MB, more than one 4 MiB block

    var receipt = log.append(recs);

    var content = log.read(receipt.blobName()).toBytes();
    assertThat(content.length).isGreaterThan(4 * 1024 * 1024);
    assertThat(new String(content, StandardCharsets.UTF_8).split("\n")).hasSize(2501);
    assertThat(receipt.batchHash()).isEqualTo(AuditBatch.hash(AuditBatch.GENESIS_HASH, recs));
  }

  @Test
  void anExistingBatchIsNeverOverwritten() {
    var container = freshContainer("t-overwrite");
    var writerA = new WormAuditLog(container, "audit-0", CLOCK);
    var writerB = new WormAuditLog(container, "audit-0", CLOCK); // a second pod with the same id
    writerA.headHash();
    writerB.headHash(); // both see an empty chain
    var written = writerA.append(records("a", 1));

    assertThatThrownBy(() -> writerB.append(records("b", 1)))
        .isInstanceOf(BlobStorageException.class);

    assertThat(lines(writerA, written.blobName())[1]).contains("\"eventId\":\"a-0\"");
    assertThat(container.getBlobClient(written.blobName()).getProperties().getMetadata())
        .containsEntry("batchHash", written.batchHash());
  }

  @Test
  void aFailedBlockWriteLeavesTheHeadAndTheRetryUsesAFreshBlob() {
    var container = freshContainer("t-retry");
    var failNext = new java.util.concurrent.atomic.AtomicBoolean();
    var log =
        new WormAuditLog(container, "audit-0", CLOCK) {
          @Override
          void writeBlock(
              com.azure.storage.blob.specialized.AppendBlobClient blob,
              byte[] c,
              int from,
              int to) {
            if (failNext.getAndSet(false)) {
              throw new IllegalStateException("simulated block write failure");
            }
            super.writeBlock(blob, c, from, to);
          }
        };
    var first = log.append(records("a", 1));

    failNext.set(true);
    assertThatThrownBy(() -> log.append(records("b", 1))).isInstanceOf(IllegalStateException.class);
    assertThat(log.headHash()).isEqualTo(first.batchHash());

    var retry = log.append(records("b", 1));

    assertThat(retry.blobName()).endsWith("audit-0-3.ndjson");
    assertThat(lines(log, retry.blobName())[0])
        .contains("\"prevBatch\":\"" + first.blobName() + "\"")
        .contains("\"prevHash\":\"" + first.batchHash() + "\"");
    // The incomplete blob stays: empty content, metadata hash that matches nothing stored.
    var orphan = container.getBlobClient("v1/2026/11/18/10/audit-0-2.ndjson");
    assertThat(orphan.getProperties().getBlobSize()).isZero();
    assertThat(orphan.getProperties().getMetadata()).containsKey("batchHash");
  }
}
