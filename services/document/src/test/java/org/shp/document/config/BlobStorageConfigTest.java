package org.shp.document.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.azure.storage.blob.BlobServiceClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** The lazy Blob client builds from the connection string and fails fast when it is missing. */
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
}
