package org.shp.audit.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.azure.storage.blob.BlobContainerClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Blob settings bind with safe defaults; a missing configuration fails clearly on first use. */
class AuditBlobPropertiesTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner().withUserConfiguration(AuditBlobConfig.class);

  @Test
  void defaultsAreNotConfiguredWithTheAuditContainer() {
    runner
        .withBean(java.time.Clock.class, java.time.Clock::systemUTC)
        .run(ctx -> {
          var props = ctx.getBean(AuditBlobProperties.class);
          assertThat(props.configured()).isFalse();
          assertThat(props.container()).isEqualTo("audit");
          assertThat(props.instance()).isEqualTo("audit-0");
          assertThat(props.createContainerIfMissing()).isFalse();
        });
  }

  @Test
  void accountUrlAloneCountsAsConfigured() {
    assertThat(
            new AuditBlobProperties("", "https://acct.blob.example", null, null, false)
                .configured())
        .isTrue();
    assertThat(new AuditBlobProperties("x", "", null, null, false).configured()).isTrue();
  }

  @Test
  void firstUseWithoutSettingsFailsWithAClearMessage() {
    runner
        .withBean(java.time.Clock.class, java.time.Clock::systemUTC)
        .run(ctx ->
            assertThatThrownBy(() -> ctx.getBean(BlobContainerClient.class))
                .rootCause()
                .hasMessageContaining("Audit blob storage is not configured"));
  }

  @Test
  void accountUrlBuildsAManagedIdentityClientWithoutCallingAzure() {
    runner
        .withBean(java.time.Clock.class, java.time.Clock::systemUTC)
        .withPropertyValues(
            "shp.audit-blob.account-url=https://stshpauditdemo.blob.core.windows.net")
        .run(ctx -> {
          var client = ctx.getBean(BlobContainerClient.class);
          assertThat(client.getBlobContainerName()).isEqualTo("audit");
          assertThat(client.getAccountName()).isEqualTo("stshpauditdemo");
        });
  }
}
