package org.shp.web.precondition;

/**
 * Response body carrying the resource version (application API DTO). {@link ETagAdvice} sets {@code
 * ETag: W/"<version()>"}; FHIR resources do not need this because they already have {@code
 * meta.versionId}.
 */
public interface Versioned {
  long version();
}
