package org.shp.audit.infra;

import com.azure.core.util.BinaryData;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.models.BlobItem;
import com.azure.storage.blob.models.BlobListDetails;
import com.azure.storage.blob.models.BlobRequestConditions;
import com.azure.storage.blob.models.ListBlobsOptions;
import com.azure.storage.blob.options.AppendBlobCreateOptions;
import com.azure.storage.blob.specialized.AppendBlobClient;
import java.io.ByteArrayInputStream;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.shp.audit.domain.AuditBatch;

/**
 * Appends audit batches as NDJSON append blobs to the WORM container and keeps the per-instance
 * hash chain (05-storage-audit-blob §1.2). Blob name: {@code
 * v1/yyyy/MM/dd/HH/<instance>-<sequence>.ndjson} inside the audit container; the hash is stored in
 * the blob metadata {@code batchHash} when the blob is created (metadata cannot change once the
 * immutability policy applies).
 *
 * <p>The chain head (last blob name, hash, sequence) is recovered from the container on first use,
 * so a restarted instance continues its own chain. One instance owns one chain; calls are
 * serialised. The head only advances after the whole batch is written.
 */
public class WormAuditLog {

  /** Metadata key holding the batch hash. */
  public static final String HASH_METADATA = "batchHash";

  private static final DateTimeFormatter HOUR_PATH =
      DateTimeFormatter.ofPattern("yyyy/MM/dd/HH").withZone(ZoneOffset.UTC);
  private static final int MAX_BLOCK_BYTES = 4 * 1024 * 1024;
  private static final String PREFIX = "v1/";

  private final BlobContainerClient container;
  private final String instance;
  private final Clock clock;
  private final Pattern namePattern;

  private boolean headLoaded;
  private String lastBlob;
  private String lastHash = AuditBatch.GENESIS_HASH;
  private long lastSequence;

  public WormAuditLog(BlobContainerClient container, String instance, Clock clock) {
    if (!instance.matches("[A-Za-z0-9_-]+")) {
      throw new IllegalArgumentException("instance may only contain letters, digits, - and _");
    }
    this.container = container;
    this.instance = instance;
    this.clock = clock;
    this.namePattern =
        Pattern.compile("^v1/\\d{4}/\\d{2}/\\d{2}/\\d{2}/" + instance + "-(\\d+)\\.ndjson$");
  }

  /** What a successful append produced. */
  public record Receipt(String blobName, String batchHash, int count) {}

  /**
   * Writes the records as the next batch of this instance's chain.
   *
   * @param recordLines serialized JSON records (no line breaks)
   */
  public synchronized Receipt append(List<String> recordLines) {
    loadHead();
    AuditBatch batch = AuditBatch.of(lastBlob, lastHash, recordLines);
    long sequence = lastSequence + 1;
    String name =
        PREFIX
            + HOUR_PATH.format(ZonedDateTime.now(clock))
            + "/"
            + instance
            + "-"
            + sequence
            + ".ndjson";

    AppendBlobClient blob = container.getBlobClient(name).getAppendBlobClient();
    // Never overwrite an existing batch (If-None-Match: *).
    blob.createWithResponse(
        new AppendBlobCreateOptions()
            .setMetadata(Map.of(HASH_METADATA, batch.batchHash()))
            .setRequestConditions(new BlobRequestConditions().setIfNoneMatch("*")),
        null,
        null);
    byte[] content = batch.content();
    for (int from = 0; from < content.length; from += MAX_BLOCK_BYTES) {
      int to = Math.min(content.length, from + MAX_BLOCK_BYTES);
      blob.appendBlock(new ByteArrayInputStream(content, from, to - from), to - from);
    }

    lastBlob = name;
    lastHash = batch.batchHash();
    lastSequence = sequence;
    return new Receipt(name, batch.batchHash(), batch.count());
  }

  /** Reads the NDJSON content of a batch (used by tests and the integrity tool). */
  public BinaryData read(String blobName) {
    BlobClient blob = container.getBlobClient(blobName);
    return blob.downloadContent();
  }

  /** The current chain head hash ({@link AuditBatch#GENESIS_HASH} before the first batch). */
  public synchronized String headHash() {
    loadHead();
    return lastHash;
  }

  private void loadHead() {
    if (headLoaded) {
      return;
    }
    ListBlobsOptions options =
        new ListBlobsOptions()
            .setPrefix(PREFIX)
            .setDetails(new BlobListDetails().setRetrieveMetadata(true));
    for (BlobItem item : container.listBlobs(options, null)) {
      Matcher m = namePattern.matcher(item.getName());
      if (m.matches()) {
        long sequence = Long.parseLong(m.group(1));
        if (sequence > lastSequence) {
          String hash = item.getMetadata() == null ? null : item.getMetadata().get(HASH_METADATA);
          if (hash == null) {
            throw new IllegalStateException(
                "Chain head " + item.getName() + " has no " + HASH_METADATA + " metadata");
          }
          lastSequence = sequence;
          lastBlob = item.getName();
          lastHash = hash;
        }
      }
    }
    headLoaded = true;
  }
}
