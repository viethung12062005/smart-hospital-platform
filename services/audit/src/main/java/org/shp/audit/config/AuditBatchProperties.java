package org.shp.audit.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Batching of audit records into the WORM log ({@code shp.audit-batch.*}, 05-storage-audit-blob
 * §1.2): a batch is written when {@code maxRecords} are waiting or the oldest waiting record is
 * {@code maxAge} old. {@code pollInterval} is how often that condition is checked.
 */
@ConfigurationProperties("shp.audit-batch")
public record AuditBatchProperties(int maxRecords, Duration maxAge, Duration pollInterval) {
  public AuditBatchProperties {
    maxRecords = maxRecords <= 0 ? 5000 : maxRecords;
    maxAge =
        maxAge == null || maxAge.isZero() || maxAge.isNegative() ? Duration.ofSeconds(60) : maxAge;
    pollInterval =
        pollInterval == null || pollInterval.isZero() || pollInterval.isNegative()
            ? Duration.ofSeconds(1)
            : pollInterval;
  }
}
