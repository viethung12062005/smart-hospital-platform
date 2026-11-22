package org.shp.document.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.azure.storage.blob.BlobServiceClientBuilder;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.shp.document.config.BlobStoreProperties;
import org.shp.document.domain.BlobStore.BlobNotFoundException;
import org.shp.document.domain.SignedUrl;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * DOC-02 against a real Azurite (Testcontainers): blob read/write through the shared-key client and
 * the SAS links it issues (permissions, blob scope, expiry), exercised over HTTP.
 */
@Testcontainers
class AzureBlobStoreAzuriteTest {

  // Synthetic account and key (base64 of 64 'a' bytes), not a real credential.
  private static final String ACCOUNT = "shp";
  private static final String KEY =
      "YWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYQ==";
  private static final String CONTAINER = "documents";
  private static final Instant NOW = Instant.now();

  @Container
  static final GenericContainer<?> AZURITE =
      new GenericContainer<>(
              DockerImageName.parse(
                  System.getenv()
                      .getOrDefault(
                          "SHP_TEST_AZURITE_IMAGE",
                          "mcr.microsoft.com/azure-storage/azurite:3.37.0")))
          .withEnv("AZURITE_ACCOUNTS", ACCOUNT + ":" + KEY)
          .withCommand("azurite-blob", "--blobHost", "0.0.0.0", "--skipApiVersionCheck", "--loose")
          .withExposedPorts(10000)
          .waitingFor(Wait.forListeningPort());

  private static final HttpClient HTTP = HttpClient.newHttpClient();
  private static AzureBlobStore store;

  @BeforeAll
  static void connect() {
    store = storeAt(Clock.fixed(NOW, ZoneOffset.UTC));
  }

  private static AzureBlobStore storeAt(Clock clock) {
    String connection =
        "DefaultEndpointsProtocol=http;AccountName="
            + ACCOUNT
            + ";AccountKey="
            + KEY
            + ";BlobEndpoint=http://"
            + AZURITE.getHost()
            + ":"
            + AZURITE.getMappedPort(10000)
            + "/"
            + ACCOUNT;
    var props = new BlobStoreProperties(connection, null, null, true, null, null, null);
    return new AzureBlobStore(
        new BlobServiceClientBuilder().connectionString(connection).buildClient(), props, clock);
  }

  private static int send(HttpRequest request) throws Exception {
    return HTTP.send(request, BodyHandlers.discarding()).statusCode();
  }

  private static Map<String, String> query(SignedUrl link) {
    return Arrays.stream(URI.create(link.url()).getRawQuery().split("&"))
        .map(p -> p.split("=", 2))
        .collect(Collectors.toMap(p -> p[0], p -> URLDecoder.decode(p[1], StandardCharsets.UTF_8)));
  }

  @Test
  void putStatGetAndDeleteRoundTripWithContentType() {
    byte[] data = "synthetic report".getBytes(StandardCharsets.UTF_8);
    store.put(CONTAINER, "patients/p1/d1/v1/original.pdf", data, "application/pdf");

    var info = store.stat(CONTAINER, "patients/p1/d1/v1/original.pdf").orElseThrow();
    assertThat(info.size()).isEqualTo(data.length);
    assertThat(info.contentType()).isEqualTo("application/pdf");
    assertThat(store.get(CONTAINER, "patients/p1/d1/v1/original.pdf")).isEqualTo(data);

    assertThat(store.delete(CONTAINER, "patients/p1/d1/v1/original.pdf")).isTrue();
    assertThat(store.delete(CONTAINER, "patients/p1/d1/v1/original.pdf")).isFalse();
    assertThat(store.stat(CONTAINER, "patients/p1/d1/v1/original.pdf")).isEmpty();
  }

  @Test
  void missingBlobIsReportedNotThrownAsSdkError() {
    assertThat(store.stat(CONTAINER, "no/such/blob.pdf")).isEmpty();
    assertThatThrownBy(() -> store.get(CONTAINER, "no/such/blob.pdf"))
        .isInstanceOf(BlobNotFoundException.class);
    assertThat(store.stat("never-created", "x.pdf")).isEmpty();
  }

  @Test
  void uploadLinkAllowsCreateAndWriteOfExactlyOneBlobForTenMinutes() throws Exception {
    SignedUrl link = store.uploadUrl(CONTAINER, "patients/p2/d2/v1/original.png");
    var q = query(link);

    assertThat(q.get("sp")).isEqualTo("cw");
    assertThat(q.get("sr")).isEqualTo("b");
    assertThat(link.expiresAt())
        .isEqualTo(
            NOW.plus(Duration.ofMinutes(10)).truncatedTo(java.time.temporal.ChronoUnit.SECONDS));
    assertThat(Instant.parse(q.get("se"))).isEqualTo(link.expiresAt());
    assertThat(link.url()).contains("/documents/patients%2Fp2%2Fd2%2Fv1%2Foriginal.png?");
    assertThat(link.toString()).doesNotContain("sig");

    byte[] png = {(byte) 0x89, 'P', 'N', 'G'};
    var put =
        HttpRequest.newBuilder(URI.create(link.url()))
            .header("x-ms-blob-type", "BlockBlob")
            .header("Content-Type", "image/png")
            .PUT(BodyPublishers.ofByteArray(png))
            .build();
    assertThat(send(put)).isEqualTo(201);
    assertThat(store.get(CONTAINER, "patients/p2/d2/v1/original.png")).isEqualTo(png);

    // Not read, not delete, not another blob.
    assertThat(send(HttpRequest.newBuilder(URI.create(link.url())).GET().build())).isEqualTo(403);
    assertThat(send(HttpRequest.newBuilder(URI.create(link.url())).DELETE().build()))
        .isEqualTo(403);
    String other = link.url().replace("original.png", "other.png");
    var putOther =
        HttpRequest.newBuilder(URI.create(other))
            .header("x-ms-blob-type", "BlockBlob")
            .PUT(BodyPublishers.ofByteArray(png))
            .build();
    assertThat(send(putOther)).isEqualTo(403);
  }

  @Test
  void downloadLinkAllowsReadOfExactlyOneBlobForFiveMinutesAsAttachment() throws Exception {
    byte[] data = "synthetic result".getBytes(StandardCharsets.UTF_8);
    store.put(CONTAINER, "patients/p3/d3/v1/original.pdf", data, "application/pdf");
    store.put(CONTAINER, "patients/p3/d3/v1/second.pdf", data, "application/pdf");

    SignedUrl link = store.downloadUrl(CONTAINER, "patients/p3/d3/v1/original.pdf", "result.pdf");
    var q = query(link);
    assertThat(q.get("sp")).isEqualTo("r");
    assertThat(q.get("sr")).isEqualTo("b");
    assertThat(Instant.parse(q.get("se")))
        .isEqualTo(
            NOW.plus(Duration.ofMinutes(5)).truncatedTo(java.time.temporal.ChronoUnit.SECONDS));

    HttpResponse<byte[]> get =
        HTTP.send(
            HttpRequest.newBuilder(URI.create(link.url())).GET().build(),
            BodyHandlers.ofByteArray());
    assertThat(get.statusCode()).isEqualTo(200);
    assertThat(get.body()).isEqualTo(data);
    assertThat(get.headers().firstValue("Content-Disposition"))
        .hasValue("attachment; filename=\"result.pdf\"");

    // Read only: no write, no other blob.
    var put =
        HttpRequest.newBuilder(URI.create(link.url()))
            .header("x-ms-blob-type", "BlockBlob")
            .PUT(BodyPublishers.ofByteArray(data))
            .build();
    assertThat(send(put)).isEqualTo(403);
    String other = link.url().replace("original.pdf", "second.pdf");
    assertThat(send(HttpRequest.newBuilder(URI.create(other)).GET().build())).isEqualTo(403);
  }

  @Test
  void linkIsRejectedByStorageAfterItsExpiry() throws Exception {
    // A store whose clock is one hour behind issues a link that is already expired for Azurite.
    var past = storeAt(Clock.fixed(Instant.now().minus(Duration.ofHours(1)), ZoneOffset.UTC));
    store.put(CONTAINER, "patients/p4/d4/v1/original.pdf", new byte[] {1}, "application/pdf");

    SignedUrl link = past.downloadUrl(CONTAINER, "patients/p4/d4/v1/original.pdf", "a.pdf");

    assertThat(send(HttpRequest.newBuilder(URI.create(link.url())).GET().build())).isEqualTo(403);
  }

  @Test
  void fileNameCannotBreakOutOfTheContentDispositionHeader() {
    SignedUrl link = store.downloadUrl(CONTAINER, "p/a.pdf", "x\".pdf\r\nSet-Cookie: a=b");

    String disposition = query(link).get("rscd");
    assertThat(disposition).doesNotContain("\r").doesNotContain("\n");
    assertThat(disposition).startsWith("attachment; filename=\"");
  }
}
