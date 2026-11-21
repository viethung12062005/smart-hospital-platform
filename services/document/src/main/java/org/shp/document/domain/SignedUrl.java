package org.shp.document.domain;

import java.time.Instant;

/**
 * A shared-access-signature link to one blob.
 *
 * @param url full URL including the signature; never log it
 * @param expiresAt instant after which the storage service rejects the link
 */
public record SignedUrl(String url, Instant expiresAt) {
  @Override
  public String toString() {
    return "SignedUrl[expiresAt=" + expiresAt + "]";
  }
}
