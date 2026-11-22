package org.shp.document.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Blob storage configuration ({@code shp.blob.*}, 05-storage-audit-blob §2).
 *
 * <p>Two ways to reach the account, one code path:
 *
 * <ul>
 *   <li>{@code connectionString} ({@code AZURE_STORAGE_CONNECTION_STRING}): local Azurite, and the
 *       shared-key workaround where managed identity is not available yet.
 *   <li>{@code accountUrl} ({@code AZURE_STORAGE_ACCOUNT_URL}): the cloud account, authenticated
 *       with {@code DefaultAzureCredential} (workload/managed identity, no key).
 * </ul>
 *
 * The connection string wins when both are set.
 *
 * @param connectionString storage account connection string (empty = not used)
 * @param accountUrl blob endpoint such as {@code https://<account>.blob.core.windows.net}
 * @param sasMode how links are signed: {@code AUTO} (account key with a connection string, user
 *     delegation with an account URL), {@code ACCOUNT_KEY} or {@code USER_DELEGATION}
 * @param createContainers create a missing container on first use (local Azurite starts empty;
 *     cloud containers come from Terraform, so keep it off there)
 * @param documentsContainer container for patient documents
 * @param quarantineContainer container for files quarantined after a malware finding
 * @param kbContainer container for the RAG knowledge base originals (ai service)
 */
@ConfigurationProperties("shp.blob")
public record BlobStoreProperties(
    String connectionString,
    String accountUrl,
    SasMode sasMode,
    boolean createContainers,
    String documentsContainer,
    String quarantineContainer,
    String kbContainer) {

  /** Signing method of the expiring links. */
  public enum SasMode {
    AUTO,
    ACCOUNT_KEY,
    USER_DELEGATION
  }

  public BlobStoreProperties {
    connectionString = connectionString == null ? "" : connectionString;
    accountUrl = accountUrl == null ? "" : accountUrl;
    sasMode = sasMode == null ? SasMode.AUTO : sasMode;
    documentsContainer = documentsContainer == null ? "documents" : documentsContainer;
    quarantineContainer = quarantineContainer == null ? "quarantine" : quarantineContainer;
    kbContainer = kbContainer == null ? "kb" : kbContainer;
  }

  /** True when a connection string or an account URL is configured. */
  public boolean configured() {
    return !connectionString.isBlank() || !accountUrl.isBlank();
  }

  /** The signing method after resolving {@code AUTO}. */
  public SasMode effectiveSasMode() {
    if (sasMode != SasMode.AUTO) {
      return sasMode;
    }
    return connectionString.isBlank() ? SasMode.USER_DELEGATION : SasMode.ACCOUNT_KEY;
  }
}
