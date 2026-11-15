package org.shp.data.mongo;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * A service creates only the platform collections it writes: the audit, clinical and notification
 * services never accept Idempotency-Key requests, so they must not create {@code idempotency_keys}
 * (design 03 §2, follow-up recorded in the DoD audit for PR #55).
 */
class MongoMessagingIndexesTest extends MongoTestBase {

  @Test
  void onlyTheRequestedStoresAreCreated() {
    var fresh = "shp_only_outbox";
    var template =
        new org.springframework.data.mongodb.core.MongoTemplate(
            new org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory(
                com.mongodb.client.MongoClients.create(MONGO.getReplicaSetUrl(fresh)), fresh));

    MongoMessaging.ensureIndexes(template, MongoMessaging.Store.OUTBOX, MongoMessaging.Store.INBOX);

    Set<String> created = new HashSet<>(template.getCollectionNames());
    assertThat(created)
        .contains(MongoMessaging.OUTBOX, MongoMessaging.INBOX)
        .doesNotContain(MongoMessaging.IDEMPOTENCY);

    // The TTL index of the inbox is in place, so the store behaves as designed.
    var ttl =
        template.indexOps(MongoMessaging.INBOX).getIndexInfo().stream()
            .filter(i -> "ttl_inbox_14d".equals(i.getName()))
            .findFirst();
    assertThat(ttl).isPresent();
    assertThat(ttl.orElseThrow().getExpireAfter()).isNotNull();

    template.getDb().drop();
  }

  @Test
  void idempotencyIsCreatedOnlyWhenAsked() {
    var fresh = "shp_with_idempotency";
    var template =
        new org.springframework.data.mongodb.core.MongoTemplate(
            new org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory(
                com.mongodb.client.MongoClients.create(MONGO.getReplicaSetUrl(fresh)), fresh));

    MongoMessaging.ensureIndexes(
        template, MongoMessaging.Store.OUTBOX, MongoMessaging.Store.IDEMPOTENCY);

    Set<String> created = new HashSet<>(template.getCollectionNames());
    assertThat(created)
        .contains(MongoMessaging.OUTBOX, MongoMessaging.IDEMPOTENCY)
        .doesNotContain(MongoMessaging.INBOX);

    template.getDb().drop();
  }
}
