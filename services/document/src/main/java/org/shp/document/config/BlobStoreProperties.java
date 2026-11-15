package org.shp.document.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Blob storage configuration ({@code shp.blob.*}). Binds the account connection string (from {@code
 * AZURE_STORAGE_CONNECTION_STRING}, supplied by the chart secret in the cloud and by the local
 * Azurite in dev) and the three container names of the document store (05-storage-audit-blob §2):
 * {@code documents}, {@code quarantine} and {@code kb}.
 *
 * <p>This is configuration only: SAS signing and malware scanning are DOC-02, not this scaffold.
 *
 * @param connectionString storage account connection string (empty = Blob storage is not wired yet)
 * @param documentsContainer container for patient documents
 * @param quarantineContainer container for files quarantined after a malware finding
 * @param kbContainer container for the RAG knowledge base originals (ai service)
 */
@ConfigurationProperties("shp.blob")
public record BlobStoreProperties(
    String connectionString,
    String documentsContainer,
    String quarantineContainer,
    String kbContainer) {
  public BlobStoreProperties {
    connectionString = connectionString == null ? "" : connectionString;
    documentsContainer = documentsContainer == null ? "documents" : documentsContainer;
    quarantineContainer = quarantineContainer == null ? "quarantine" : quarantineContainer;
    kbContainer = kbContainer == null ? "kb" : kbContainer;
  }
}
