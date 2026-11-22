package org.shp.document.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.azure.storage.blob.BlobServiceClient;
import org.junit.jupiter.api.Test;
import org.shp.document.domain.BlobStore;
import org.shp.document.infra.AzureBlobStore;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** The lazy Blob client and BlobStore build from either credential and fail fast without one. */
class BlobStorageConfigTest {

  // Synthetic, non-secret connection string in the same shape as the local Azurite value
  // (dev/scripts/deploy.sh). AccountKey is base64 of 64 'a' bytes; building a client never
  // connects.
  private static final String CONNECTION_STRING =
      "DefaultEndpointsProtocol=http;AccountName=devstoreaccount1;"
          + "AccountKey=YWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYQ==;"
          + "BlobEndpoint=http://127.0.0.1:10000/devstoreaccount1";

  @Test
  void buildsBlobServiceClientWhenTheConnectionStringIsPresent() {
    new ApplicationContextRunner()
        .withUserConfiguration(BlobStorageConfig.class)
        .withPropertyValues("shp.blob.connection-string=" + CONNECTION_STRING)
        .run(ctx -> assertThat(ctx.getBean(BlobServiceClient.class)).isNotNull());
  }

  @Test
  void missingConnectionStringFailsFastWithAClearMessage() {
    new ApplicationContextRunner()
        .withUserConfiguration(BlobStorageConfig.class)
        .run(ctx ->
            assertThatThrownBy(() -> ctx.getBean(BlobServiceClient.class))
                .rootCause()
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AZURE_STORAGE_CONNECTION_STRING"));
  }

  @Test
  void blobStoreIsInjectableFromTheConnectionString() {
    new ApplicationContextRunner()
        .withUserConfiguration(BlobStorageConfig.class)
        .withPropertyValues("shp.blob.connection-string=" + CONNECTION_STRING)
        .run(ctx -> assertThat(ctx.getBean(BlobStore.class)).isInstanceOf(AzureBlobStore.class));
  }

  @Test
  void accountUrlBuildsTheClientWithManagedIdentityWithoutAKey() {
    new ApplicationContextRunner()
        .withUserConfiguration(BlobStorageConfig.class)
        .withPropertyValues("shp.blob.account-url=https://stsynthetic.blob.core.windows.net")
        .run(ctx -> {
          assertThat(ctx.getBean(BlobServiceClient.class).getAccountUrl())
              .isEqualTo("https://stsynthetic.blob.core.windows.net");
          assertThat(ctx.getBean(BlobStore.class)).isInstanceOf(AzureBlobStore.class);
        });
  }

  @Test
  void missingConfigurationFailsFastWhenTheStoreIsFirstUsed() {
    new ApplicationContextRunner()
        .withUserConfiguration(BlobStorageConfig.class)
        .run(ctx ->
            assertThatThrownBy(() -> ctx.getBean(BlobStore.class))
                .rootCause()
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AZURE_STORAGE_ACCOUNT_URL"));
  }
}
