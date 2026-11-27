package org.shp.notification.config;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.shp.notification.api.InternalOtpFilter;
import org.shp.notification.app.OtpChannel;
import org.shp.notification.app.OtpService;
import org.shp.notification.infra.FakeOtpChannel;
import org.shp.notification.infra.MongoOtpStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;

/** Wiring of the OTP service (N8/N9): channels, the HMAC key, the internal filter, the indexes. */
@Configuration
class OtpConfig {
  private static final Logger LOG = LoggerFactory.getLogger(OtpConfig.class);

  /**
   * Default channel until the real Zalo, SMS and email adapters exist: a fake that sends nothing.
   */
  @Bean
  @ConditionalOnMissingBean(OtpChannel.class)
  FakeOtpChannel fakeOtpChannel() {
    return new FakeOtpChannel();
  }

  /**
   * The HMAC key comes from {@code SHP_OTP_HMAC_KEY} (never from a file). Without it a random
   * per-process key is used, which is fine for one local replica but invalidates challenges on a
   * restart and does not work across replicas, so set it in every shared environment.
   */
  @Bean
  OtpService otpService(
      MongoOtpStore store,
      List<OtpChannel> channels,
      Clock clock,
      @Value("${shp.otp.hmac-key:}") String hmacKey) {
    byte[] key;
    if (hmacKey.isBlank()) {
      LOG.warn("shp.otp.hmac-key is not set: using a random key for this process only");
      key = new byte[32];
      new SecureRandom().nextBytes(key);
    } else {
      key = hmacKey.getBytes(StandardCharsets.UTF_8);
    }
    return new OtpService(store, channels, clock, key);
  }

  /** Callers of N8/N9: clinical, and Keycloak's authenticator client. */
  @Bean
  FilterRegistrationBean<InternalOtpFilter> internalOtpFilter(
      OtpService otp,
      @Value("${shp.otp.allowed-clients:svc-clinical,svc-keycloak}") Set<String> allowedClients) {
    var registration = new FilterRegistrationBean<>(new InternalOtpFilter(otp, allowedClients));
    registration.addUrlPatterns("/internal/v1/otp/*");
    // After the Spring Security filter chain (order -100) so the validated token is available.
    registration.setOrder(Ordered.LOWEST_PRECEDENCE - 100);
    return registration;
  }

  /** TTL indexes: challenges live until {@code purgeAt}, send-log rows for one hour. */
  @Bean
  ApplicationRunner otpIndexes(MongoTemplate mongo) {
    return args -> {
      for (String c : new String[] {MongoOtpStore.CHALLENGES, MongoOtpStore.SEND_LOG}) {
        if (!mongo.collectionExists(c)) {
          mongo.createCollection(c);
        }
      }
      var challenges = mongo.indexOps(MongoOtpStore.CHALLENGES);
      challenges.createIndex(
          new Index()
              .on("purgeAt", Sort.Direction.ASC)
              .expire(Duration.ZERO)
              .named("ttl_purge_at"));
      challenges.createIndex(
          new Index()
              .on("destHash", Sort.Direction.ASC)
              .on("lockedUntil", Sort.Direction.ASC)
              .named("ix_dest_lock"));
      var log = mongo.indexOps(MongoOtpStore.SEND_LOG);
      log.createIndex(
          new Index()
              .on("createdAt", Sort.Direction.ASC)
              .expire(Duration.ofHours(1))
              .named("ttl_created_at"));
      log.createIndex(
          new Index()
              .on("destHash", Sort.Direction.ASC)
              .on("createdAt", Sort.Direction.ASC)
              .named("ix_dest_time"));
      log.createIndex(
          new Index()
              .on("ipHash", Sort.Direction.ASC)
              .on("createdAt", Sort.Direction.ASC)
              .named("ix_ip_time"));
    };
  }
}
