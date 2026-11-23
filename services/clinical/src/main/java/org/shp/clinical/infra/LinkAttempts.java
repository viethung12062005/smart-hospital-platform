package org.shp.clinical.infra;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.HexFormat;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.bson.Document;
import org.shp.events.Uuids;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

/**
 * {@code link_attempts} (03 §2.2): one entry per claim or link request, kept 24 hours, for the
 * limits per account and per phone number. The phone number is stored only as a keyed hash. A claim
 * entry also holds what C28 needs: the OTP {@code challengeId} and the matched record (both empty
 * when nothing matched), the wrong codes so far, and when it was used.
 */
@Repository
public class LinkAttempts implements InitializingBean {
  public static final String COLLECTION = "link_attempts";
  static final Logger LOG = LoggerFactory.getLogger(LinkAttempts.class);

  private final MongoTemplate mongo;
  private final Clock clock;
  private final byte[] hashKey;

  public LinkAttempts(
      MongoTemplate mongo,
      Clock clock,
      @Value("${shp.clinical.link-attempts.hash-key:}") String hashKey) {
    this.mongo = mongo;
    this.clock = clock;
    if (hashKey == null || hashKey.isBlank()) {
      LOG.warn(
          "No shp.clinical.link-attempts.hash-key: phone hashes use a random key and the limits per"
              + " number reset when the service restarts");
      this.hashKey = new byte[32];
      new SecureRandom().nextBytes(this.hashKey);
    } else {
      this.hashKey = Base64.getDecoder().decode(hashKey);
    }
  }

  @Override
  public void afterPropertiesSet() {
    if (!mongo.collectionExists(COLLECTION)) {
      mongo.createCollection(COLLECTION);
    }
    var ops = mongo.indexOps(COLLECTION);
    ops.createIndex(
        new Index()
            .on("accountSub", Sort.Direction.ASC)
            .on("at", Sort.Direction.ASC)
            .named("ix_link_attempts_account_at"));
    ops.createIndex(
        new Index()
            .on("phoneHash", Sort.Direction.ASC)
            .on("at", Sort.Direction.ASC)
            .named("ix_link_attempts_phone_at"));
    ops.createIndex(
        new Index()
            .on("at", Sort.Direction.ASC)
            .expire(1, TimeUnit.DAYS)
            .named("ttl_link_attempts_at"));
  }

  /** HMAC-SHA-256 of the E.164 number, hex. */
  public String phoneHash(String phoneE164) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(hashKey, "HmacSHA256"));
      return HexFormat.of().formatHex(mac.doFinal(phoneE164.getBytes(StandardCharsets.UTF_8)));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("HmacSHA256 is not available", e);
    }
  }

  /** Requests of {@code kind} the account made in the last {@code window}. */
  public long byAccount(String accountSub, String kind, Duration window) {
    return mongo.count(
        Query.query(
            Criteria.where("accountSub")
                .is(accountSub)
                .and("kind")
                .is(kind)
                .and("at")
                .gt(Date.from(clock.instant().minus(window)))),
        COLLECTION);
  }

  /** Codes sent to the number in the last {@code window} (requests that sent nothing excluded). */
  public long codesSentTo(String phoneHash, Duration window) {
    return mongo.count(
        Query.query(
            Criteria.where("phoneHash")
                .is(phoneHash)
                .and("challengeId")
                .ne(null)
                .and("at")
                .gt(Date.from(clock.instant().minus(window)))),
        COLLECTION);
  }

  /** Record a request; {@code patientId} and {@code challengeId} are null when no code was sent. */
  public void record(
      String accountSub, String kind, String phoneHash, String patientId, String challengeId) {
    mongo.insert(
        new Document("_id", Uuids.v7(clock).toString())
            .append("accountSub", accountSub)
            .append("kind", kind)
            .append("phoneHash", phoneHash)
            .append("patientId", patientId)
            .append("challengeId", challengeId)
            .append("failures", 0)
            .append("usedAt", null)
            .append("at", Date.from(clock.instant())),
        COLLECTION);
  }

  /** The account's newest unused request of {@code kind} made within {@code validFor}. */
  public Optional<Document> latestOpen(String accountSub, String kind, Duration validFor) {
    Query q =
        Query.query(
                Criteria.where("accountSub")
                    .is(accountSub)
                    .and("kind")
                    .is(kind)
                    .and("usedAt")
                    .is(null)
                    .and("at")
                    .gt(Date.from(clock.instant().minus(validFor))))
            .with(Sort.by(Sort.Direction.DESC, "at", "_id"))
            .limit(1);
    return Optional.ofNullable(mongo.findOne(q, Document.class, COLLECTION));
  }

  /** Count one more wrong code; returns the new total. */
  public int fail(String id) {
    Document d =
        mongo.findAndModify(
            Query.query(Criteria.where("_id").is(id)),
            new Update().inc("failures", 1),
            FindAndModifyOptions.options().returnNew(true),
            Document.class,
            COLLECTION);
    return d == null ? Integer.MAX_VALUE : d.getInteger("failures", 0);
  }

  /** Mark the request used so its code cannot link twice; false when it was used already. */
  public boolean use(String id) {
    Instant now = clock.instant();
    return mongo
            .updateFirst(
                Query.query(Criteria.where("_id").is(id).and("usedAt").is(null)),
                new Update().set("usedAt", Date.from(now)),
                COLLECTION)
            .getModifiedCount()
        == 1;
  }
}
