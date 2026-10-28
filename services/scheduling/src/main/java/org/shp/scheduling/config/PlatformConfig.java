package org.shp.scheduling.config;

import java.time.Clock;
import org.shp.data.postgres.PostgresIdempotency;
import org.shp.data.postgres.PostgresInbox;
import org.shp.data.postgres.PostgresOutbox;
import org.shp.data.postgres.PostgresOutboxAuditSink;
import org.shp.events.audit.AuditSink;
import org.shp.web.auth.HttpIdentityLinkClient;
import org.shp.web.auth.IdentityLinkClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

/**
 * Bean nền tảng do SA cấu hình sẵn (sinh bởi tools/scaffold/java_service.py). Dev KHÔNG cần sửa
 * file này; logic nghiệp vụ đặt ở các gói khác của service.
 */
@Configuration
class PlatformConfig {
  /**
   * Tra identity link qua API nội bộ của clinical (09 §4). Token workload identity: task IAC/SHR
   * (chưa có).
   */
  @Bean
  IdentityLinkClient identityLinkClient(@Value("${shp.clinical.url}") String clinicalUrl) {
    return new HttpIdentityLinkClient(RestClient.builder().baseUrl(clinicalUrl).build());
  }

  @Bean
  PostgresOutbox outbox(JdbcClient jdbc) {
    return new PostgresOutbox(jdbc);
  }

  @Bean
  PostgresInbox inbox(JdbcClient jdbc, TransactionTemplate tx, Clock clock) {
    return new PostgresInbox(jdbc, tx, clock);
  }

  @Bean
  PostgresIdempotency idempotency(JdbcClient jdbc, Clock clock) {
    return new PostgresIdempotency(jdbc, clock);
  }

  /** Audit ghi bền vào outbox (transaction riêng) — phát lên bus bởi relay. */
  @Bean
  AuditSink auditSink(PostgresOutbox outbox, TransactionTemplate tx, Clock clock) {
    return new PostgresOutboxAuditSink(outbox, tx, clock);
  }
}
