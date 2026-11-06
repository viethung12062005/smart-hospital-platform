package org.shp.web.precondition;

/**
 * Body response mang version của resource (DTO của API ứng dụng). {@link ETagAdvice} đặt {@code
 * ETag: W/"<version()>"}; resource FHIR không cần vì đã có {@code meta.versionId}.
 */
public interface Versioned {
  long version();
}
