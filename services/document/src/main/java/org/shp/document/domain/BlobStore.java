package org.shp.document.domain;

import java.util.Optional;

/**
 * Object storage port of the document service (ADR-0008, 05-storage-audit-blob §2). One
 * implementation serves Azure Blob Storage and the local Azurite emulator, so the same code runs
 * everywhere; the signing method of the expiring links is chosen by configuration.
 */
public interface BlobStore {

  /** Writes the blob, replacing an existing one with the same path. */
  void put(String container, String path, byte[] data, String contentType);

  /** Reads the whole blob; throws {@link BlobNotFoundException} when it does not exist. */
  byte[] get(String container, String path);

  /** Size and content type of the blob, or empty when it does not exist. */
  Optional<BlobInfo> stat(String container, String path);

  /** Deletes the blob; returns false when it did not exist. */
  boolean delete(String container, String path);

  /**
   * Link for one direct upload of exactly this blob: create and write permission only, valid for 10
   * minutes (05 §2).
   */
  SignedUrl uploadUrl(String container, String path);

  /**
   * Link for one download of exactly this blob: read permission only, valid for 5 minutes (BR-08),
   * served as an attachment with the given file name.
   */
  SignedUrl downloadUrl(String container, String path, String fileName);

  /** Blob properties the service needs after an upload. */
  record BlobInfo(long size, String contentType) {}

  /** The blob to read does not exist. */
  class BlobNotFoundException extends RuntimeException {
    public BlobNotFoundException(String container, String path) {
      super("Blob not found: " + container + "/" + path);
    }
  }
}
