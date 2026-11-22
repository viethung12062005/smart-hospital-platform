package org.shp.notification.config;

import org.shp.notification.infra.MongoDeviceTokenStore;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;

/** Indexes of the device token collection (04 §1.2), created idempotently at startup. */
@Configuration
class NotificationIndexes {
  @Bean
  ApplicationRunner deviceTokenIndexes(MongoTemplate mongo) {
    return args -> {
      var ops = mongo.indexOps(MongoDeviceTokenStore.COLLECTION);
      ops.createIndex(new Index().on("token", Sort.Direction.ASC).unique());
      ops.createIndex(new Index().on("ownerRef", Sort.Direction.ASC));
    };
  }
}
