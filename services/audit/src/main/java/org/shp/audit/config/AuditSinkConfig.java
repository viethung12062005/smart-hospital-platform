package org.shp.audit.config;

import com.azure.identity.DefaultAzureCredentialBuilder;
import org.shp.audit.infra.AuditSink;
import org.shp.audit.infra.LogAnalyticsAuditSink;
import org.shp.audit.infra.LokiAuditSink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

/**
 * Audit sink wiring for the audit worker (05-storage-audit-blob §1.3): the query sink (Log
 * Analytics, or Loki locally) and one startup line (no secrets) stating which one is active.
 */
@Configuration
@EnableConfigurationProperties(AuditSinkProperties.class)
public class AuditSinkConfig {

  private static final Logger log = LoggerFactory.getLogger(AuditSinkConfig.class);

  /** The query sink: Log Analytics when configured, else local Loki. */
  @Bean
  @Lazy
  AuditSink auditQuerySink(AuditSinkProperties props) {
    if (props.configured()) {
      return new LogAnalyticsAuditSink(
          props.endpoint(),
          props.dcrId(),
          props.stream(),
          new DefaultAzureCredentialBuilder().build());
    }
    if (props.lokiConfigured()) {
      return new LokiAuditSink(props.lokiUrl());
    }
    throw new IllegalStateException("No audit query sink is configured");
  }

  /** One clear startup line so a missing query sink cannot go unnoticed. */
  @Bean
  ApplicationRunner logAnalyticsSinkStatus(AuditSinkProperties props) {
    return args -> {
      if (props.configured()) {
        log.info("Log Analytics audit sink: configured");
      } else if (props.lokiConfigured()) {
        log.info("Loki audit sink: configured");
      } else {
        log.info(
            "Audit query sink: disabled (set SHP_AUDIT_SINK_WORKSPACE_ID, SHP_AUDIT_SINK_ENDPOINT "
                + "and SHP_AUDIT_SINK_DCR_ID, or SHP_AUDIT_SINK_LOKI_URL)");
      }
    };
  }
}
