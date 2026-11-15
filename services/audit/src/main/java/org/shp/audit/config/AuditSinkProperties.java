package org.shp.audit.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Log Analytics sink configuration ({@code shp.audit-sink.*}) for the audit worker
 * (05-storage-audit-blob §1.3, table {@code SHPAudit_CL}). Both values are empty by default, which
 * means the sink is disabled — the service still records to the outbox (Mongo) for the Blob WORM
 * original. AUD-02 implements the Logs Ingestion API on top of these settings; this scaffold only
 * exposes them so they cannot silently be missing.
 *
 * @param workspaceId Log Analytics workspace id (empty = disabled)
 * @param endpoint Logs Ingestion API / Data Collection Rule endpoint (empty = disabled)
 */
@ConfigurationProperties("shp.audit-sink")
public record AuditSinkProperties(String workspaceId, String endpoint) {
  public AuditSinkProperties {
    workspaceId = workspaceId == null ? "" : workspaceId;
    endpoint = endpoint == null ? "" : endpoint;
  }

  /** The Log Analytics sink is configured only when both the workspace id and endpoint are set. */
  public boolean configured() {
    return !workspaceId.isBlank() && !endpoint.isBlank();
  }
}
