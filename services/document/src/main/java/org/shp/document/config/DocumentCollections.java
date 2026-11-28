package org.shp.document.config;

import java.time.Duration;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;

/**
 * Collections and indexes of the upload flow (03-db-clinical-document §3), created idempotently.
 */
@Configuration
class DocumentCollections {
  @Bean
  ApplicationRunner uploadCollections(MongoTemplate mongo) {
    return args -> {
      for (String c : new String[] {"document_references", "upload_sessions"}) {
        if (!mongo.collectionExists(c)) {
          mongo.createCollection(c);
        }
      }
      mongo
          .indexOps("upload_sessions")
          .createIndex(
              new Index()
                  .on("expiresAt", Sort.Direction.ASC)
                  .named("ttl_upload_sessions")
                  .expire(Duration.ZERO));
      mongo
          .indexOps("document_references")
          .createIndex(
              new Index()
                  .on("createdBy", Sort.Direction.ASC)
                  .on("createdAt", Sort.Direction.DESC)
                  .named("ix_document_created_by"));
    };
  }
}
