package org.shp.audit.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Blob WORM container settings ({@code shp.audit-blob.*}, 05-storage-audit-blob §1.2). Exactly one
 * way to reach the account is used: the connection string (local Azurite, dev) wins when set,
 * otherwise the account URL with the pod's managed identity (cloud, no secret).
 *
 * @param connectionString storage connection string (empty = use {@code accountUrl})
 * @param accountUrl blob endpoint such as {@code https://<account>.blob.core.windows.net}
 * @param container audit container name
 * @param instance instance id used in blob names and as the chain key
 * @param createContainerIfMissing create the container at first use (Azurite only; in the cloud
 *     IAC-12 creates it with the immutability policy)
 */
@ConfigurationProperties("shp.audit-blob")
public record AuditBlobProperties(
    String connectionString,
    String accountUrl,
    String container,
    String instance,
    boolean createContainerIfMissing) {
  public AuditBlobProperties {
    connectionString = connectionString == null ? "" : connectionString;
    accountUrl = accountUrl == null ? "" : accountUrl;
    container = container == null || container.isBlank() ? "audit" : container;
    instance = instance == null || instance.isBlank() ? "audit-0" : instance;
  }

  /** True when either a connection string or an account URL is present. */
  public boolean configured() {
    return !connectionString.isBlank() || !accountUrl.isBlank();
  }
}
