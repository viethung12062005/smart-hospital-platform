package org.shp.scheduling.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Domain record representing an audit / version history entry. Corresponds to table {@code
 * scheduling.resource_history}.
 */
public record ResourceHistoryRecord(
    String resourceType,
    UUID id,
    int versionId,
    Instant lastUpdated,
    String operation,
    String changedBy,
    String content) {

  public ResourceHistoryRecord {
    Objects.requireNonNull(resourceType, "resourceType must not be null");
    Objects.requireNonNull(id, "id must not be null");
    Objects.requireNonNull(lastUpdated, "lastUpdated must not be null");
    Objects.requireNonNull(operation, "operation must not be null");
    Objects.requireNonNull(changedBy, "changedBy must not be null");
    Objects.requireNonNull(content, "content must not be null");
    if (versionId < 1) {
      throw new IllegalArgumentException("versionId must be >= 1");
    }
  }
}
