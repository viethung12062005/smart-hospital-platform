package org.shp.data.mongo;

import com.mongodb.client.MongoClients;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.mongodb.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Real MongoDB 8 replica set (Testcontainers) — required for transactions. Override the image with
 * SHP_TEST_MONGO_IMAGE.
 *
 * <p>GLIBC_TUNABLES: some Linux 6.19–7.0.13 kernels have a tcmalloc bug that violates the rseq ABI
 * and makes mongod block its own startup (MongoDB SERVER-121912, no fix — closed as "Gone away");
 * some CI runners use this kernel. `glibc.pthread.rseq=1` is the workaround the community confirms
 * works (https://github.com/kiwiproject/kiwi/issues/1480). Remove once runners have kernel >=
 * 7.0.14.
 */
abstract class MongoTestBase {
  static final MongoDBContainer MONGO =
      new MongoDBContainer(
              DockerImageName.parse(System.getenv().getOrDefault("SHP_TEST_MONGO_IMAGE", "mongo:8"))
                  .asCompatibleSubstituteFor("mongo"))
          .withEnv("GLIBC_TUNABLES", "glibc.pthread.rseq=1")
          .withReplicaSet(); // Testcontainers 2.x does not enable a replica set itself
  // (transactions need a replica
  // set)
  static final MongoTemplate DB;
  static final TransactionTemplate TX;

  static {
    MONGO.start();
    var factory =
        new SimpleMongoClientDatabaseFactory(
            MongoClients.create(MONGO.getReplicaSetUrl("shp")), "shp");
    DB = new MongoTemplate(factory);
    TX = new TransactionTemplate(new MongoTransactionManager(factory));
    for (String c :
        new String[] {
          MongoMessaging.OUTBOX, MongoMessaging.INBOX, MongoMessaging.IDEMPOTENCY, "thing"
        }) {
      if (!DB.collectionExists(c)) {
        DB.createCollection(c); // transactions do not create collections
      }
    }
    MongoMessaging.ensureIndexes(DB);
  }

  @BeforeEach
  void reset() {
    for (String c :
        new String[] {
          MongoMessaging.OUTBOX, MongoMessaging.INBOX, MongoMessaging.IDEMPOTENCY, "thing"
        }) {
      DB.getCollection(c).deleteMany(new Document());
    }
  }

  static long count(String collection) {
    return DB.getCollection(collection).countDocuments();
  }

  static void insertThing(String name) {
    DB.insert(new Document("_id", UUID.randomUUID().toString()).append("name", name), "thing");
  }

  static final class MutableClock extends Clock {
    volatile Instant now;

    MutableClock(Instant start) {
      now = start;
    }

    @Override
    public ZoneId getZone() {
      return ZoneId.of("UTC");
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
}
