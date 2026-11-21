package org.shp.document.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobContainerClientBuilder;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.models.UserDelegationKey;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.shp.document.config.BlobStoreProperties;
import org.shp.document.config.BlobStoreProperties.SasMode;
import org.shp.document.domain.SignedUrl;

/**
 * The cloud signing path (user delegation SAS), which Azurite cannot serve: the SDK signs offline
 * with a synthetic delegation key, so the link shape (permissions, blob, expiry, key identity) is
 * still verified.
 */
class AzureBlobStoreSasTest {

  private static final Instant NOW = Instant.parse("2026-11-18T08:00:00Z");

  private static AzureBlobStore cloudStore(BlobServiceClient service) {
    var props =
        new BlobStoreProperties(
            "", "https://stsynthetic.blob.core.windows.net", null, false, null, null, null);
    return new AzureBlobStore(service, props, Clock.fixed(NOW, ZoneOffset.UTC));
  }

  private static BlobServiceClient serviceWithDelegationKey() {
    BlobServiceClient service = mock(BlobServiceClient.class);
    BlobContainerClient container =
        new BlobContainerClientBuilder()
            .endpoint("https://stsynthetic.blob.core.windows.net/documents")
            .buildClient();
    when(service.getBlobContainerClient("documents")).thenReturn(container);
    var key =
        new UserDelegationKey()
            .setSignedObjectId("00000000-0000-0000-0000-000000000001")
            .setSignedTenantId("00000000-0000-0000-0000-000000000002")
            .setSignedStart(OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC))
            .setSignedExpiry(OffsetDateTime.ofInstant(NOW.plusSeconds(3600), ZoneOffset.UTC))
            .setSignedService("b")
            .setSignedVersion("2023-11-03")
            .setValue("YWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWE=");
    when(service.getUserDelegationKey(any(), any())).thenReturn(key);
    return service;
  }

  private static Map<String, String> query(SignedUrl link) {
    return Arrays.stream(URI.create(link.url()).getRawQuery().split("&"))
        .map(p -> p.split("=", 2))
        .collect(Collectors.toMap(p -> p[0], p -> p[1]));
  }

  @Test
  void userDelegationUploadLinkIsCreateWriteOnOneBlobForTenMinutes() {
    BlobServiceClient service = serviceWithDelegationKey();

    SignedUrl link = cloudStore(service).uploadUrl("documents", "patients/p1/d1/v1/original.pdf");

    var q = query(link);
    assertThat(q).containsEntry("sp", "cw").containsEntry("sr", "b").containsKey("skoid");
    assertThat(q).containsKey("sig");
    assertThat(link.expiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(10)));
    assertThat(link.url())
        .startsWith(
            "https://stsynthetic.blob.core.windows.net/documents/patients%2Fp1%2Fd1%2Fv1%2Foriginal.pdf?");
    verify(service).getUserDelegationKey(any(), any());
  }

  @Test
  void userDelegationDownloadLinkIsReadOnlyForFiveMinutes() {
    SignedUrl link =
        cloudStore(serviceWithDelegationKey()).downloadUrl("documents", "p/a.pdf", "a.pdf");

    var q = query(link);
    assertThat(q).containsEntry("sp", "r").containsEntry("sr", "b").containsKey("skoid");
    assertThat(link.expiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(5)));
  }

  @Test
  void sasModeAutoFollowsTheCredentialInUse() {
    assertThat(
            new BlobStoreProperties("Account=x", "", null, false, null, null, null)
                .effectiveSasMode())
        .isEqualTo(SasMode.ACCOUNT_KEY);
    assertThat(
            new BlobStoreProperties(
                    "", "https://a.blob.core.windows.net", null, false, null, null, null)
                .effectiveSasMode())
        .isEqualTo(SasMode.USER_DELEGATION);
    assertThat(
            new BlobStoreProperties(
                    "Account=x", "", SasMode.USER_DELEGATION, false, null, null, null)
                .effectiveSasMode())
        .isEqualTo(SasMode.USER_DELEGATION);
  }
}
