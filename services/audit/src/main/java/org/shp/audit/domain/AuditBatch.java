package org.shp.audit.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One batch of audit records and its place in the hash chain (05-storage-audit-blob §1.2).
 *
 * <p>The blob content is NDJSON: a {@code batch-header} line followed by one line per record.
 * {@code batchHash = SHA-256(prevHash + record lines)} where {@code prevHash} is the lowercase hex
 * string of the previous batch of the same instance and every record line is taken with its
 * terminating {@code \n}. The header is not part of the hash input (it already carries {@code
 * prevHash}), so the hash can be recomputed from the records alone. The first batch of an instance
 * has no previous batch: {@code prevBatch} is {@code null} and {@code prevHash} is {@link
 * #GENESIS_HASH}.
 *
 * <p>Pure domain code: no Spring, no I/O.
 */
public final class AuditBatch {

  /** {@code prevHash} of the first batch of an instance: 64 zeros. */
  public static final String GENESIS_HASH = "0".repeat(64);

  private static final Pattern SAFE_PATH = Pattern.compile("[A-Za-z0-9_./-]+");
  private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");
  private static final Pattern HEADER =
      Pattern.compile(
          "^\\{\"type\":\"batch-header\",\"prevBatch\":(null|\"[A-Za-z0-9_./-]+\"),"
              + "\"prevHash\":\"([0-9a-f]{64})\",\"count\":(\\d+)\\}$");

  private final String prevBatch;
  private final String prevHash;
  private final List<String> records;
  private final String batchHash;

  private AuditBatch(String prevBatch, String prevHash, List<String> records) {
    this.prevBatch = prevBatch;
    this.prevHash = prevHash;
    this.records = List.copyOf(records);
    this.batchHash = hash(prevHash, this.records);
  }

  /**
   * Builds a batch.
   *
   * @param prevBatch blob name of the previous batch of this instance, or {@code null} for the
   *     first one
   * @param prevHash {@code batchHash} of the previous batch, or {@link #GENESIS_HASH}
   * @param records serialized JSON records, one per element, without line breaks
   */
  public static AuditBatch of(String prevBatch, String prevHash, List<String> records) {
    if (records.isEmpty()) {
      throw new IllegalArgumentException("A batch needs at least one record");
    }
    if (prevBatch != null && !SAFE_PATH.matcher(prevBatch).matches()) {
      throw new IllegalArgumentException("prevBatch contains characters that are not allowed");
    }
    if (!HASH.matcher(prevHash).matches()) {
      throw new IllegalArgumentException("prevHash must be 64 lowercase hex characters");
    }
    if ((prevBatch == null) != GENESIS_HASH.equals(prevHash)) {
      throw new IllegalArgumentException("prevBatch is null exactly for the genesis hash");
    }
    for (String record : records) {
      if (record == null
          || record.isBlank()
          || record.indexOf('\n') >= 0
          || record.indexOf('\r') >= 0) {
        throw new IllegalArgumentException("Each record must be one non-empty line of JSON");
      }
    }
    return new AuditBatch(prevBatch, prevHash, records);
  }

  /** SHA-256 over {@code prevHash} followed by every record line and its {@code \n}. */
  public static String hash(String prevHash, List<String> records) {
    MessageDigest digest = sha256();
    digest.update(prevHash.getBytes(StandardCharsets.UTF_8));
    for (String record : records) {
      digest.update(record.getBytes(StandardCharsets.UTF_8));
      digest.update((byte) '\n');
    }
    return HexFormat.of().formatHex(digest.digest());
  }

  /**
   * Tells whether stored NDJSON is a whole batch with the given hash: the header is readable, its
   * {@code count} equals the number of record lines and the hash recomputed from those lines equals
   * {@code expectedBatchHash}. Content cut by an interrupted append block is not complete.
   */
  public static boolean isComplete(String ndjson, String expectedBatchHash) {
    if (ndjson == null || expectedBatchHash == null || !ndjson.endsWith("\n")) {
      return false;
    }
    String[] lines = ndjson.split("\n", -1);
    if (lines.length < 3) { // header, at least one record, and the empty tail after the last \n
      return false;
    }
    Matcher header = HEADER.matcher(lines[0]);
    if (!header.matches()) {
      return false;
    }
    List<String> records = Arrays.asList(lines).subList(1, lines.length - 1);
    int count;
    try {
      count = Integer.parseInt(header.group(3));
    } catch (NumberFormatException e) {
      return false;
    }
    boolean genesis = "null".equals(header.group(1));
    String prevHash = header.group(2);
    return records.size() == count
        && records.stream().noneMatch(String::isEmpty)
        && genesis == GENESIS_HASH.equals(prevHash)
        && hash(prevHash, records).equals(expectedBatchHash);
  }

  /** The header line (no trailing newline). */
  public String header() {
    return "{\"type\":\"batch-header\",\"prevBatch\":"
        + (prevBatch == null ? "null" : "\"" + prevBatch + "\"")
        + ",\"prevHash\":\""
        + prevHash
        + "\",\"count\":"
        + records.size()
        + "}";
  }

  /** Full NDJSON content: header line, then the record lines, each ended by {@code \n}. */
  public byte[] content() {
    StringBuilder sb = new StringBuilder(header()).append('\n');
    for (String record : records) {
      sb.append(record).append('\n');
    }
    return sb.toString().getBytes(StandardCharsets.UTF_8);
  }

  public String prevBatch() {
    return prevBatch;
  }

  public String prevHash() {
    return prevHash;
  }

  public int count() {
    return records.size();
  }

  public String batchHash() {
    return batchHash;
  }

  private static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is required by the JDK", e);
    }
  }
}
