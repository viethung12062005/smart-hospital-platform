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
 * MongoDB 8 replica set thật (Testcontainers) — cần cho transaction. Đổi image bằng
 * SHP_TEST_MONGO_IMAGE.
 *
 * <p>GLIBC_TUNABLES: một số kernel Linux 6.19–7.0.13 có lỗi tcmalloc vi phạm ABI rseq khiến mongod
 * tự chặn khởi động (MongoDB SERVER-121912, không có bản vá — closed "Gone away"); một số runner CI
 * dùng kernel này. `glibc.pthread.rseq=1` là cách né được cộng đồng xác nhận hoạt động
 * (https://github.com/kiwiproject/kiwi/issues/1480). Bỏ khi runner có kernel >= 7.0.14.
 */
abstract class MongoTestBase {
  static final MongoDBContainer MONGO =
      new MongoDBContainer(
              DockerImageName.parse(System.getenv().getOrDefault("SHP_TEST_MONGO_IMAGE", "mongo:8"))
                  .asCompatibleSubstituteFor("mongo"))
          .withEnv("GLIBC_TUNABLES", "glibc.pthread.rseq=1")
          .withReplicaSet(); // Testcontainers 2.x không tự bật replica set (transaction cần replica
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
        DB.createCollection(c); // transaction không tự tạo collection
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
