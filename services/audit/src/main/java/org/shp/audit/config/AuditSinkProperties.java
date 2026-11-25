package org.shp.audit.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Query sink configuration ({@code shp.audit-sink.*}) for the audit worker (05-storage-audit-blob
 * §1.3, table {@code SHPAudit_CL}). Everything is empty by default, which means no sink — the
 * service still writes the Blob WORM original. Cloud: Log Analytics through the Logs Ingestion API
 * (workspace id, endpoint and Data Collection Rule id all set). Local: Loki when only {@code
 * lokiUrl} is set.
 *
 * @param workspaceId Log Analytics workspace id (empty = disabled)
 * @param endpoint Logs Ingestion API / Data Collection Rule endpoint (empty = disabled)
 * @param dcrId immutable id of the Data Collection Rule
 * @param stream stream name of the rule, default {@code Custom-SHPAudit_CL}
 * @param lokiUrl base URL of Loki for the local environment (empty = no Loki)
 */
@ConfigurationProperties("shp.audit-sink")
public record AuditSinkProperties(
    String workspaceId, String endpoint, String dcrId, String stream, String lokiUrl) {

  public AuditSinkProperties {
    workspaceId = workspaceId == null ? "" : workspaceId;
    endpoint = endpoint == null ? "" : endpoint;
    dcrId = dcrId == null ? "" : dcrId;
    stream = stream == null || stream.isBlank() ? "Custom-SHPAudit_CL" : stream;
    lokiUrl = lokiUrl == null ? "" : lokiUrl;
  }

  /** Log Analytics is used only when the workspace id, endpoint and rule id are all set. */
  public boolean configured() {
    return !workspaceId.isBlank() && !endpoint.isBlank() && !dcrId.isBlank();
  }

  /** The local Loki sink is used when Log Analytics is not configured and a Loki URL is set. */
  public boolean lokiConfigured() {
    return !configured() && !lokiUrl.isBlank();
  }

  /** Whether any query sink is active. */
  public boolean enabled() {
    return configured() || lokiConfigured();
  }
}
