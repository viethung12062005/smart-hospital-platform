package org.shp.clinical.config;

import java.time.Clock;
import java.util.Set;
import org.shp.clinical.api.InternalIdentityFilter;
import org.shp.clinical.app.IdentityAccounts;
import org.shp.clinical.app.ProfileLinks;
import org.shp.clinical.infra.MongoIdentityAccounts;
import org.shp.clinical.infra.MongoProfileLinks;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;

/** Wiring of the identity APIs: the internal lookup filter and the {@code identity_links} index. */
@Configuration
class InternalApiConfig {
  /**
   * Callers of {@code /internal/v1/identity/*} (09 §4): every service that resolves identities.
   * Their workload token carries the role of the target, {@code internal.clinical}, and {@code azp
   * = svc-<caller>} (15 §7).
   */
  @Bean
  FilterRegistrationBean<InternalIdentityFilter> internalIdentityFilter(
      IdentityAccounts accounts,
      ProfileLinks links,
      Clock clock,
      @Value("${shp.clinical.internal-identity.required-role:internal.clinical}")
          String requiredRole,
      @Value(
              "${shp.clinical.internal-identity.allowed-clients:svc-scheduling,svc-document,svc-notification,svc-audit,svc-ai}")
          Set<String> allowedClients) {
    var registration =
        new FilterRegistrationBean<>(
            new InternalIdentityFilter(accounts, links, clock, requiredRole, allowedClients));
    registration.addUrlPatterns("/internal/v1/identity/*");
    // After the Spring Security filter chain (order -100) so the validated token is available.
    registration.setOrder(Ordered.LOWEST_PRECEDENCE - 100);
    return registration;
  }

  /** One account per profile: an idempotent unique index on {@code fhirUser} (03 §2.2). */
  @Bean
  ApplicationRunner identityLinksIndex(MongoTemplate mongo) {
    return args -> {
      if (!mongo.collectionExists(MongoIdentityAccounts.COLLECTION)) {
        mongo.createCollection(MongoIdentityAccounts.COLLECTION);
      }
      mongo
          .indexOps(MongoIdentityAccounts.COLLECTION)
          .createIndex(
              new Index().on("fhirUser", Sort.Direction.ASC).unique().named("ux_fhir_user"));
    };
  }

  /** Indexes of {@code profile_links} (03 §2.2): one link per account and profile. */
  @Bean
  ApplicationRunner profileLinksIndexes(MongoTemplate mongo) {
    return args -> {
      if (!mongo.collectionExists(MongoProfileLinks.COLLECTION)) {
        mongo.createCollection(MongoProfileLinks.COLLECTION);
      }
      var ops = mongo.indexOps(MongoProfileLinks.COLLECTION);
      ops.createIndex(
          new Index()
              .on("accountSub", Sort.Direction.ASC)
              .on("patientId", Sort.Direction.ASC)
              .unique()
              .named("ux_account_patient"));
      ops.createIndex(
          new Index()
              .on("accountSub", Sort.Direction.ASC)
              .on("status", Sort.Direction.ASC)
              .named("ix_account_status"));
      ops.createIndex(
          new Index()
              .on("patientId", Sort.Direction.ASC)
              .on("status", Sort.Direction.ASC)
              .named("ix_patient_status"));
    };
  }
}
