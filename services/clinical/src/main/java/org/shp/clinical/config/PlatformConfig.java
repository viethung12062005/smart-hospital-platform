package org.shp.clinical.config;

import java.time.Clock;
import org.shp.data.mongo.MongoIdempotency;
import org.shp.data.mongo.MongoInbox;
import org.shp.data.mongo.MongoMessaging;
import org.shp.data.mongo.MongoOutbox;
import org.shp.data.mongo.MongoOutboxAuditSink;
import org.shp.events.audit.AuditSink;
import org.shp.web.auth.IdentityLinkClient;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Bean nền tảng do SA cấu hình sẵn (sinh bởi tools/scaffold/java_service.py). Dev KHÔNG cần sửa
 * file này; logic nghiệp vụ đặt ở các gói khác của service.
 */
@Configuration
class PlatformConfig {
  /** clinical sở hữu {@code identity_links} nên tra thẳng DB của mình (03 §2). */
  @Bean
  IdentityLinkClient identityLinkClient(MongoTemplate mongo) {
    return new MongoIdentityLinkClient(mongo);
  }

  /** Transaction MongoDB (cần replica set) — Boot không tự tạo. */
  @Bean
  MongoTransactionManager transactionManager(MongoDatabaseFactory factory) {
    return new MongoTransactionManager(factory);
  }

  @Bean
  MongoOutbox outbox(MongoTemplate mongo) {
    return new MongoOutbox(mongo);
  }

  @Bean
  MongoInbox inbox(MongoTemplate mongo, TransactionTemplate tx, Clock clock) {
    return new MongoInbox(mongo, tx, clock);
  }

  @Bean
  MongoIdempotency idempotency(MongoTemplate mongo, Clock clock) {
    return new MongoIdempotency(mongo, clock);
  }

  /** Audit ghi bền vào outbox (transaction riêng) — phát lên bus bởi relay. */
  @Bean
  AuditSink auditSink(MongoOutbox outbox, TransactionTemplate tx, Clock clock) {
    return new MongoOutboxAuditSink(outbox, tx, clock);
  }

  /**
   * Transaction không tự tạo collection: tạo collection nền tảng + index (idempotent) lúc khởi
   * động.
   */
  @Bean
  ApplicationRunner messagingCollections(MongoTemplate mongo) {
    return args -> {
      for (String c :
          new String[] {MongoMessaging.OUTBOX, MongoMessaging.INBOX, MongoMessaging.IDEMPOTENCY}) {
        if (!mongo.collectionExists(c)) {
          mongo.createCollection(c);
        }
      }
      MongoMessaging.ensureIndexes(mongo);
    };
  }
}
