package org.shp.clinical.config;

import java.util.Set;
import org.shp.clinical.api.InternalIdentityFilter;
import org.shp.clinical.app.IdentityAccounts;
import org.shp.clinical.infra.MongoIdentityAccounts;
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
   * Services allowed to call {@code /internal/v1/identity/*}: every service that resolves
   * identities (09 §4 "every service"). The {@code roles} claim of their workload token carries
   * {@code internal.<service>}.
   */
  @Bean
  FilterRegistrationBean<InternalIdentityFilter> internalIdentityFilter(
      IdentityAccounts accounts,
      @Value(
              "${shp.clinical.internal-identity.allowed-roles:internal.scheduling,internal.document,internal.notification,internal.audit,internal.ai,internal.ml,internal.gateway}")
          Set<String> allowedRoles) {
    var registration =
        new FilterRegistrationBean<>(new InternalIdentityFilter(accounts, allowedRoles));
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
}
