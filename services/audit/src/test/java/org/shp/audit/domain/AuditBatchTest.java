package org.shp.audit.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Hash chain format and rules of 05-storage-audit-blob §1.2 (pure domain, no I/O). */
class AuditBatchTest {

  private static final List<String> RECORDS =
      List.of(
          "{\"eventId\":\"e-1\",\"action\":\"read\"}", "{\"eventId\":\"e-2\",\"action\":\"read\"}");

  @Test
  void firstBatchHasNullPrevBatchAndGenesisHash() {
    var batch = AuditBatch.of(null, AuditBatch.GENESIS_HASH, RECORDS);

    assertThat(batch.header())
        .isEqualTo(
            "{\"type\":\"batch-header\",\"prevBatch\":null,\"prevHash\":\""
                + "0".repeat(64)
                + "\",\"count\":2}");
    assertThat(batch.batchHash()).matches("[0-9a-f]{64}");
  }

  @Test
  void contentIsHeaderThenOneLinePerRecord() {
    var batch = AuditBatch.of(null, AuditBatch.GENESIS_HASH, RECORDS);

    var lines = new String(batch.content(), StandardCharsets.UTF_8).split("\n", -1);

    assertThat(lines).hasSize(4); // header + 2 records + empty after the final newline
    assertThat(lines[0]).isEqualTo(batch.header());
    assertThat(lines[1]).isEqualTo(RECORDS.get(0));
    assertThat(lines[2]).isEqualTo(RECORDS.get(1));
  }

  @Test
  void nextBatchCarriesThePreviousBatchPathAndHash() {
    var first = AuditBatch.of(null, AuditBatch.GENESIS_HASH, RECORDS);
    var second = AuditBatch.of("v1/2026/11/18/10/audit-0-1.ndjson", first.batchHash(), RECORDS);

    assertThat(second.header())
        .contains("\"prevBatch\":\"v1/2026/11/18/10/audit-0-1.ndjson\"")
        .contains("\"prevHash\":\"" + first.batchHash() + "\"");
    assertThat(second.batchHash()).isNotEqualTo(first.batchHash());
  }

  @Test
  void hashIsSha256OfPrevHashPlusRecordLines() {
    // Independent value: printf '<64 zeros>a\n' | sha256sum
    var batch = AuditBatch.of(null, AuditBatch.GENESIS_HASH, List.of("a"));

    assertThat(batch.batchHash())
        .isEqualTo(AuditBatch.hash(AuditBatch.GENESIS_HASH, List.of("a")))
        .isEqualTo("bb6594bc93932e8c75c5b9ad119d1ad1ba55221e56e83fbdca389283aefa2e69");
  }

  @Test
  void changingARecordOrThePrevHashChangesTheBatchHash() {
    var base = AuditBatch.hash(AuditBatch.GENESIS_HASH, RECORDS);

    assertThat(AuditBatch.hash(AuditBatch.GENESIS_HASH, List.of(RECORDS.get(0), "{\"x\":1}")))
        .isNotEqualTo(base);
    assertThat(AuditBatch.hash("1".repeat(64), RECORDS)).isNotEqualTo(base);
  }

  @Test
  void rejectsInvalidInput() {
    assertThatThrownBy(() -> AuditBatch.of(null, AuditBatch.GENESIS_HASH, List.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> AuditBatch.of(null, AuditBatch.GENESIS_HASH, List.of("a\nb")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> AuditBatch.of(null, "abc", RECORDS))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> AuditBatch.of("v1/x.ndjson", AuditBatch.GENESIS_HASH, RECORDS))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> AuditBatch.of("bad\"path", "1".repeat(64), RECORDS))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
