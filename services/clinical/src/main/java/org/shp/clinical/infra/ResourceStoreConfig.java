package org.shp.clinical.infra;

import java.time.Clock;
import org.shp.data.mongo.MongoOutbox;
import org.shp.data.mongo.MongoResourceStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The FHIR resource store of the clinical database (SHR-10). Declared here rather than in {@code
 * config/PlatformConfig}, which only the SA changes.
 */
@Configuration
class ResourceStoreConfig {
  @Bean
  MongoResourceStore resourceStore(
      MongoTemplate mongo, TransactionTemplate tx, MongoOutbox outbox, Clock clock) {
    return new MongoResourceStore(mongo, tx, outbox, clock);
  }
}
