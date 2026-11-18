package org.shp.document.infra;

import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.models.BlobErrorCode;
import com.azure.storage.blob.models.BlobHttpHeaders;
import com.azure.storage.blob.models.BlobStorageException;
import com.azure.storage.blob.models.UserDelegationKey;
import com.azure.storage.blob.options.BlockBlobSimpleUploadOptions;
import com.azure.storage.blob.sas.BlobSasPermission;
import com.azure.storage.blob.sas.BlobServiceSasSignatureValues;
import com.azure.storage.blob.specialized.BlockBlobClient;
import java.io.ByteArrayInputStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.shp.document.config.BlobStoreProperties;
import org.shp.document.config.BlobStoreProperties.SasMode;
import org.shp.document.domain.BlobStore;
import org.shp.document.domain.SignedUrl;

/**
 * {@link BlobStore} on the Azure Blob SDK. The same code talks to Azure and to Azurite; only the
 * client (connection string or account URL with managed identity) and the SAS signing method
 * differ, both chosen by {@link BlobStoreProperties}.
 */
public class AzureBlobStore implements BlobStore {

  /** Upload link lifetime (05 §2). */
  public static final Duration UPLOAD_TTL = Duration.ofMinutes(10);

  /** Download link lifetime (BR-08). */
  public static final Duration DOWNLOAD_TTL = Duration.ofMinutes(5);

  // Start the validity slightly in the past to tolerate clock skew with the storage service.
  private static final Duration CLOCK_SKEW = Duration.ofMinutes(1);

  private final BlobServiceClient service;
  private final SasMode sasMode;
  private final boolean createContainers;
  private final Clock clock;
  private final Set<String> readyContainers = ConcurrentHashMap.newKeySet();

  public AzureBlobStore(BlobServiceClient service, BlobStoreProperties props, Clock clock) {
    this.service = service;
    this.sasMode = props.effectiveSasMode();
    this.createContainers = props.createContainers();
    this.clock = clock;
  }

  @Override
  public void put(String container, String path, byte[] data, String contentType) {
    BlockBlobClient blob = blob(container, path, true).getBlockBlobClient();
    blob.uploadWithResponse(
        new BlockBlobSimpleUploadOptions(new ByteArrayInputStream(data), data.length)
            .setHeaders(new BlobHttpHeaders().setContentType(contentType)),
        null,
        null);
  }

  @Override
  public byte[] get(String container, String path) {
    try {
      return blob(container, path, false).downloadContent().toBytes();
    } catch (BlobStorageException e) {
      if (isNotFound(e)) {
        throw new BlobNotFoundException(container, path);
      }
      throw e;
    }
  }

  @Override
  public Optional<BlobInfo> stat(String container, String path) {
    try {
      var props = blob(container, path, false).getProperties();
      return Optional.of(new BlobInfo(props.getBlobSize(), props.getContentType()));
    } catch (BlobStorageException e) {
      if (isNotFound(e)) {
        return Optional.empty();
      }
      throw e;
    }
  }

  @Override
  public boolean delete(String container, String path) {
    try {
      blob(container, path, false).delete();
      return true;
    } catch (BlobStorageException e) {
      if (isNotFound(e)) {
        return false;
      }
      throw e;
    }
  }

  @Override
  public SignedUrl uploadUrl(String container, String path) {
    var permission = new BlobSasPermission().setCreatePermission(true).setWritePermission(true);
    return sign(container, path, permission, UPLOAD_TTL, null);
  }

  @Override
  public SignedUrl downloadUrl(String container, String path, String fileName) {
    var permission = new BlobSasPermission().setReadPermission(true);
    String disposition =
        "attachment; filename=\"" + fileName.replaceAll("[\"\\\\\r\n]", "_") + "\"";
    return sign(container, path, permission, DOWNLOAD_TTL, disposition);
  }

  private SignedUrl sign(
      String container,
      String path,
      BlobSasPermission permission,
      Duration ttl,
      String contentDisposition) {
    BlobClient blob = blob(container, path, true);
    // A SAS carries whole seconds; truncating keeps the returned expiry equal to the signed one.
    Instant now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
    OffsetDateTime start = OffsetDateTime.ofInstant(now.minus(CLOCK_SKEW), ZoneOffset.UTC);
    OffsetDateTime expiry = OffsetDateTime.ofInstant(now.plus(ttl), ZoneOffset.UTC);
    var values = new BlobServiceSasSignatureValues(expiry, permission).setStartTime(start);
    if (contentDisposition != null) {
      values.setContentDisposition(contentDisposition);
    }
    String sas;
    if (sasMode == SasMode.USER_DELEGATION) {
      UserDelegationKey key = service.getUserDelegationKey(start, expiry);
      sas = blob.generateUserDelegationSas(values, key);
    } else {
      sas = blob.generateSas(values);
    }
    return new SignedUrl(blob.getBlobUrl() + "?" + sas, expiry.toInstant());
  }

  private BlobClient blob(String container, String path, boolean ensureContainer) {
    BlobContainerClient containerClient = service.getBlobContainerClient(container);
    if (ensureContainer && createContainers && readyContainers.add(container)) {
      try {
        containerClient.createIfNotExists();
      } catch (RuntimeException e) {
        readyContainers.remove(container);
        throw e;
      }
    }
    return containerClient.getBlobClient(path);
  }

  private static boolean isNotFound(BlobStorageException e) {
    return e.getStatusCode() == 404
        || BlobErrorCode.BLOB_NOT_FOUND.equals(e.getErrorCode())
        || BlobErrorCode.CONTAINER_NOT_FOUND.equals(e.getErrorCode());
  }
}
