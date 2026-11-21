package org.shp.document.config;

import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import java.time.Clock;
import org.shp.document.domain.BlobStore;
import org.shp.document.infra.AzureBlobStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

/**
 * Blob storage wiring for the document service (05-storage-audit-blob §2): the {@link
 * BlobServiceClient} and the {@link BlobStore} port that the use cases inject. Both are lazy so the
 * service still starts without storage configured (tests, early local runs); a missing
 * configuration fails fast with a clear message the first time storage is used.
 */
@Configuration
@EnableConfigurationProperties(BlobStoreProperties.class)
public class BlobStorageConfig {

  @Bean
  @Lazy
  BlobServiceClient blobServiceClient(BlobStoreProperties props) {
    if (!props.connectionString().isBlank()) {
      return new BlobServiceClientBuilder()
          .connectionString(props.connectionString())
          .buildClient();
    }
    if (!props.accountUrl().isBlank()) {
      return new BlobServiceClientBuilder()
          .endpoint(props.accountUrl())
          .credential(new DefaultAzureCredentialBuilder().build())
          .buildClient();
    }
    throw new IllegalStateException(
        "Blob storage is not configured: set AZURE_STORAGE_CONNECTION_STRING (local Azurite, "
            + "shared-key workaround) or AZURE_STORAGE_ACCOUNT_URL (cloud, managed identity).");
  }

  @Bean
  @Lazy
  BlobStore blobStore(
      BlobServiceClient client, BlobStoreProperties props, ObjectProvider<Clock> clock) {
    return new AzureBlobStore(client, props, clock.getIfAvailable(Clock::systemUTC));
  }
}
