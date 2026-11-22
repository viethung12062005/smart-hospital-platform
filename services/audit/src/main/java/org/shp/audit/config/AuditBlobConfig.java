package org.shp.audit.config;

import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import java.time.Clock;
import org.shp.audit.infra.WormAuditLog;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

/**
 * Wires the WORM audit log (05-storage-audit-blob §1.2). Beans are lazy so the service still starts
 * without storage settings; the first use then fails with a clear message.
 */
@Configuration
@EnableConfigurationProperties(AuditBlobProperties.class)
public class AuditBlobConfig {

  /** Connection string (Azurite) or account URL + managed identity (cloud). */
  @Bean
  @Lazy
  BlobContainerClient auditContainerClient(AuditBlobProperties props) {
    BlobServiceClientBuilder builder = new BlobServiceClientBuilder();
    if (!props.connectionString().isBlank()) {
      builder.connectionString(props.connectionString());
    } else if (!props.accountUrl().isBlank()) {
      builder.endpoint(props.accountUrl()).credential(new DefaultAzureCredentialBuilder().build());
    } else {
      throw new IllegalStateException(
          "Audit blob storage is not configured: set AZURE_STORAGE_CONNECTION_STRING (local "
              + "Azurite) or SHP_AUDIT_BLOB_ACCOUNT_URL (managed identity, cloud).");
    }
    BlobContainerClient container = builder.buildClient().getBlobContainerClient(props.container());
    if (props.createContainerIfMissing()) {
      container.createIfNotExists();
    }
    return container;
  }

  @Bean
  @Lazy
  WormAuditLog wormAuditLog(
      BlobContainerClient auditContainerClient, AuditBlobProperties props, Clock clock) {
    return new WormAuditLog(auditContainerClient, props.instance(), clock);
  }
}
