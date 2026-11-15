package org.shp.document.config;

import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

/**
 * Blob storage wiring for the document service (05-storage-audit-blob §2). It only exposes the
 * typed {@link BlobStoreProperties} and a lazily built {@link BlobServiceClient}; uploading, SAS
 * signing and malware scanning are implemented by DOC-02 on top of this bean.
 */
@Configuration
@EnableConfigurationProperties(BlobStoreProperties.class)
public class BlobStorageConfig {

  /**
   * Builds the client lazily so the scaffold still starts without a connection string (local dev,
   * tests). A missing connection string fails fast with a clear message the first time a caller
   * actually needs Blob storage, instead of surfacing a confusing SDK error inside an upload.
   */
  @Bean
  @Lazy
  BlobServiceClient blobServiceClient(BlobStoreProperties props) {
    if (props.connectionString().isBlank()) {
      throw new IllegalStateException(
          "Missing AZURE_STORAGE_CONNECTION_STRING: the document service cannot reach Blob "
              + "storage. Set it in the chart secret (cloud) or dev/.local/connection.env "
              + "(local Azurite).");
    }
    return new BlobServiceClientBuilder().connectionString(props.connectionString()).buildClient();
  }
}
