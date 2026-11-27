package org.shp.notification.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.shp.notification.infra.FakeOtpChannel;
import org.shp.notification.infra.MongoOtpStore;
import org.shp.web.errors.ShpException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;

/** N8/N9 (AC-RG.4) against a real MongoDB with a movable clock. Synthetic numbers only. */
@Testcontainers
class OtpServiceIntegrationTest {
  @Container
  static final MongoDBContainer DB =
      new MongoDBContainer(
              DockerImageName.parse(System.getenv().getOrDefault("SHP_TEST_MONGO_IMAGE", "mongo:8"))
                  .asCompatibleSubstituteFor("mongo"))
          .withEnv("GLIBC_TUNABLES", "glibc.pthread.rseq=1")
          .withReplicaSet();

  static final String PHONE = "+84900000001";
  static final String OTHER_PHONE = "+84900000002";

  /** A clock the test moves forward. */
  static final class MovableClock extends Clock {
    private Instant now = Instant.parse("2026-11-17T03:00:00Z");

    void advance(Duration d) {
      now = now.plus(d);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }

  static MongoClient client;
  static MongoTemplate mongo;

  final MovableClock clock = new MovableClock();
  final FakeOtpChannel channel = new FakeOtpChannel();
  OtpService otp;

  @BeforeAll
  static void connect() {
    client = MongoClients.create(DB.getReplicaSetUrl());
    mongo = new MongoTemplate(client, "otp-test");
  }

  @AfterAll
  static void close() {
    client.close();
  }

  @BeforeEach
  void reset() {
    mongo.dropCollection(MongoOtpStore.CHALLENGES);
    mongo.dropCollection(MongoOtpStore.SEND_LOG);
    otp =
        new OtpService(
            new MongoOtpStore(mongo),
            List.of(channel),
            clock,
            "test-only-hmac-key-0123456789abcdef"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  OtpService.Sent send(String phone, String ip) {
    return otp.send("register", "phone", phone, "vi", ip);
  }

  static String wrong(String code) {
    return code.equals("000000") ? "000001" : "000000";
  }

  static String codeOf(ShpException e) {
    return e.info().code();
  }

  @Test
  void aCorrectCodeVerifiesOnceAndOnlyOnce() {
    var sent = send(PHONE, null);
    String code = channel.lastCode(PHONE);
    assertThat(code).matches("\\d{6}");
    assertThat(sent.expiresAt()).isEqualTo(clock.instant().plus(OtpService.TTL));

    assertThat(otp.verify(sent.challengeId(), code)).isTrue();
    assertThatThrownBy(() -> otp.verify(sent.challengeId(), code))
        .isInstanceOfSatisfying(
            ShpException.class, e -> assertThat(codeOf(e)).isEqualTo("otp-invalid"));
  }

  @Test
  void onlyTheHashIsStored() {
    var sent = send(PHONE, "203.0.113.7");
    String code = channel.lastCode(PHONE);
    Document stored = mongo.findById(sent.challengeId(), Document.class, MongoOtpStore.CHALLENGES);
    assertThat(stored.toJson()).doesNotContain(code).doesNotContain(PHONE);
    assertThat(stored.getString("codeHash")).hasSize(64);
    var log = mongo.findAll(Document.class, MongoOtpStore.SEND_LOG);
    assertThat(log.toString()).doesNotContain(PHONE).doesNotContain("203.0.113.7");
  }

  @Test
  void aCodeExpiresAfterFiveMinutes() {
    var sent = send(PHONE, null);
    String code = channel.lastCode(PHONE);
    clock.advance(Duration.ofMinutes(5));
    assertThatThrownBy(() -> otp.verify(sent.challengeId(), code))
        .isInstanceOfSatisfying(
            ShpException.class, e -> assertThat(codeOf(e)).isEqualTo("otp-invalid"));
  }

  @Test
  void fiveWrongAttemptsLockTheChallengeForFifteenMinutes() {
    var sent = send(PHONE, null);
    String code = channel.lastCode(PHONE);
    for (int i = 0; i < 4; i++) {
      assertThatThrownBy(() -> otp.verify(sent.challengeId(), wrong(code)))
          .isInstanceOfSatisfying(
              ShpException.class, e -> assertThat(codeOf(e)).isEqualTo("otp-invalid"));
    }
    assertThatThrownBy(() -> otp.verify(sent.challengeId(), wrong(code)))
        .isInstanceOfSatisfying(
            ShpException.class, e -> assertThat(codeOf(e)).isEqualTo("otp-locked"));

    // Even the right code is refused while locked, and no new code can be requested.
    assertThatThrownBy(() -> otp.verify(sent.challengeId(), code))
        .isInstanceOfSatisfying(
            ShpException.class, e -> assertThat(codeOf(e)).isEqualTo("otp-locked"));
    assertThatThrownBy(() -> send(PHONE, null))
        .isInstanceOfSatisfying(
            ShpException.class, e -> assertThat(codeOf(e)).isEqualTo("otp-locked"));
    send(OTHER_PHONE, null); // another number is not affected

    clock.advance(Duration.ofMinutes(15));
    send(PHONE, null); // the lock is over
  }

  @Test
  void aSixthCodeWithinAnHourForOneNumberIsRejected() {
    for (int i = 0; i < OtpService.MAX_PER_DESTINATION; i++) {
      send(PHONE, null);
    }
    assertThatThrownBy(() -> send(PHONE, null))
        .isInstanceOfSatisfying(
            ShpException.class, e -> assertThat(codeOf(e)).isEqualTo("otp-rate-limited"));
    // A rejected request does not use up the quota and other numbers are unaffected.
    send(OTHER_PHONE, null);

    clock.advance(Duration.ofMinutes(61));
    send(PHONE, null);
  }

  @Test
  void aTwentyFirstCodeWithinAnHourFromOneIpIsRejected() {
    for (int i = 0; i < OtpService.MAX_PER_IP; i++) {
      send("+8490000" + String.format("%04d", 100 + i), "198.51.100.9");
    }
    assertThatThrownBy(() -> send("+84900009999", "198.51.100.9"))
        .isInstanceOfSatisfying(
            ShpException.class, e -> assertThat(codeOf(e)).isEqualTo("otp-rate-limited"));
    send("+84900009999", "198.51.100.10");
  }

  @Test
  void anUnknownChallengeIsInvalid() {
    assertThatThrownBy(() -> otp.verify("no-such-challenge", "123456"))
        .isInstanceOfSatisfying(
            ShpException.class, e -> assertThat(codeOf(e)).isEqualTo("otp-invalid"));
  }

  @Test
  void failedDeliveryLeavesNoUsableChallenge() {
    channel.setFailing(true);
    assertThatThrownBy(() -> send(PHONE, null))
        .isInstanceOfSatisfying(
            ShpException.class, e -> assertThat(codeOf(e)).isEqualTo("dependency-unavailable"));
    assertThat(mongo.getCollection(MongoOtpStore.CHALLENGES).countDocuments()).isZero();
  }

  @Test
  void invalidInputIsRejected() {
    assertThatThrownBy(() -> otp.send("nope", "phone", PHONE, "vi", null))
        .isInstanceOfSatisfying(
            ShpException.class, e -> assertThat(codeOf(e)).isEqualTo("validation-failed"));
    assertThatThrownBy(() -> otp.send("register", "fax", PHONE, "vi", null))
        .isInstanceOfSatisfying(
            ShpException.class, e -> assertThat(codeOf(e)).isEqualTo("validation-failed"));
    assertThatThrownBy(() -> otp.send("register", "phone", " ", "vi", null))
        .isInstanceOfSatisfying(
            ShpException.class, e -> assertThat(codeOf(e)).isEqualTo("validation-failed"));
  }
}
