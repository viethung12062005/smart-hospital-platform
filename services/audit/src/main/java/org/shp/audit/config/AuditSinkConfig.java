package org.shp.audit.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Audit sink wiring for the audit worker (05-storage-audit-blob §1.3). The Mongo outbox sink is
 * provided by {@code PlatformConfig}; this class only exposes the Log Analytics settings and logs a
 * single startup line (no secrets) stating whether that sink is configured. Calling Azure is
 * AUD-02, not this scaffold.
 */
@Configuration
@EnableConfigurationProperties(AuditSinkProperties.class)
public class AuditSinkConfig {

  private static final Logger log = LoggerFactory.getLogger(AuditSinkConfig.class);

  /** One clear startup line so a missing Log Analytics sink cannot go unnoticed. */
  @Bean
  ApplicationRunner logAnalyticsSinkStatus(AuditSinkProperties props) {
    return args -> {
      if (props.configured()) {
        log.info("Log Analytics audit sink: configured");
      } else {
        log.info(
            "Log Analytics audit sink: disabled (SHP_AUDIT_SINK_WORKSPACE_ID and "
                + "SHP_AUDIT_SINK_ENDPOINT are not set)");
      }
    };
  }
}
