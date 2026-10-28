package org.shp.data.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.shp.web.errors.ShpException;
import org.shp.web.idempotency.IdempotencyKeys;

class IdempotencyTest extends PostgresTestBase {
  static final String KEY = "7b0e6c52-1111-4f3a-9d11-aaaaaaaaaaaa";
  final MutableClock clock = new MutableClock(Instant.parse("2026-11-20T02:00:00Z"));
  final PostgresIdempotency idem = new PostgresIdempotency(JDBC, clock);

  @BeforeEach
  void resetClock() {
    clock.now = Instant.parse("2026-11-20T02:00:00Z");
  }

  static byte[] hash(String json) {
    return IdempotencyKeys.hashBody(json.getBytes(StandardCharsets.UTF_8));
  }

  IdempotencyClaim claim(String subject, String key, String op, String body, boolean commit) {
    return TX.execute(
        s -> {
          var c = idem.claim(subject, key, op, hash(body));
          if (!commit) {
            s.setRollbackOnly();
          }
          return c;
        });
  }

  IdempotencyClaim claim() {
    return claim("sub-1", KEY, "Appointment/$book", "{\"slotId\":\"s1\"}", true);
  }

  @Test
  void firstRequestClaimsAndSameRequestReplaysTheSameResource() {
    var first = claim();
    assertThat(first.replay()).isFalse();
    var again = claim();
    assertThat(again.replay()).isTrue();
    assertThat(again.resourceId()).isEqualTo(first.resourceId());
  }

  @Test
  void sameKeyWithADifferentBodyOrOperationIs422() {
    claim();
    assertThatThrownBy(
            () -> claim("sub-1", KEY, "Appointment/$book", "{\"slotId\":\"OTHER\"}", true))
        .isInstanceOfSatisfying(
            ShpException.class,
            e -> {
              assertThat(e.info().code()).isEqualTo("idempotency-key-reused");
              assertThat(e.info().status()).isEqualTo(422);
            });
    assertThatThrownBy(
            () -> claim("sub-1", KEY, "Appointment/$cancel", "{\"slotId\":\"s1\"}", true))
        .isInstanceOf(ShpException.class);
  }

  @Test
  void reorderedPropertiesAndWhitespaceStillCountAsTheSameRequest() {
    claim("sub-1", KEY, "op", "{\"a\":1,\"b\":{\"x\":1,\"y\":2}}", true);
    assertThat(
            claim("sub-1", KEY, "op", "{ \"b\": { \"y\": 2, \"x\": 1 },\n \"a\": 1 }", true)
                .replay())
        .isTrue();
  }

  @Test
  void keysAreScopedPerCaller() {
    var a = claim("alice", KEY, "op", "{}", true);
    var b = claim("bob", KEY, "op", "{}", true);
    assertThat(b.replay()).isFalse();
    assertThat(b.resourceId()).isNotEqualTo(a.resourceId());
  }

  @Test
  void parallelRequestsWithOneKeyProduceExactlyOneNewClaim() throws Exception {
    List<Future<IdempotencyClaim>> fs = new ArrayList<>();
    try (var pool = Executors.newFixedThreadPool(20)) {
      for (int i = 0; i < 20; i++) {
        fs.add(pool.submit(() -> claim()));
      }
      List<IdempotencyClaim> claims = new ArrayList<>();
      for (var f : fs) {
        claims.add(f.get());
      }
      assertThat(claims.stream().filter(c -> !c.replay()).count()).isEqualTo(1);
      assertThat(claims.stream().map(IdempotencyClaim::resourceId).distinct().count()).isEqualTo(1);
    }
    assertThat(count("SELECT count(*) FROM idempotency_key")).isEqualTo(1);
  }

  @Test
  void rolledBackClaimCanBeClaimedAgain() {
    assertThat(claim("sub-1", KEY, "op", "{}", false).replay()).isFalse();
    assertThat(claim("sub-1", KEY, "op", "{}", true).replay()).isFalse();
  }

  @Test
  void keyExpiresAfter24Hours() {
    var first = claim();
    clock.now = clock.now.plusSeconds(25 * 3600);
    var later = claim();
    assertThat(later.replay()).isFalse();
    assertThat(later.resourceId()).isNotEqualTo(first.resourceId());
  }

  @Test
  void claimOutsideATransactionIsRefused() {
    assertThatThrownBy(() -> idem.claim("s", KEY, "op", hash("{}")))
        .isInstanceOf(IllegalStateException.class);
  }

  @ParameterizedTest
  @CsvSource(
      value = {
        "NULL, idempotency-key-required",
        "'', idempotency-key-required",
        "short, validation-failed",
        "has space in the key, validation-failed"
      },
      nullValues = "NULL")
  void badHeaderIsRejected(String header, String code) {
    assertThatThrownBy(() -> IdempotencyKeys.require(header))
        .isInstanceOfSatisfying(
            ShpException.class, e -> assertThat(e.info().code()).isEqualTo(code));
  }

  @Test
  void validHeaderIsAccepted() {
    assertThat(IdempotencyKeys.require(KEY)).isEqualTo(KEY);
  }

  @Test
  void bodyHashIsStableForNonJsonAndDiffersForDifferentValues() {
    assertThat(IdempotencyKeys.hashBody("not json".getBytes(StandardCharsets.UTF_8)))
        .isEqualTo(IdempotencyKeys.hashBody("not json".getBytes(StandardCharsets.UTF_8)));
    assertThat(hash("{\"a\":1}")).isNotEqualTo(hash("{\"a\":2}"));
  }
}
