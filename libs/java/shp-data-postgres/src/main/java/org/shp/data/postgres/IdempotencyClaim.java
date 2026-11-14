package org.shp.data.postgres;

import java.util.UUID;

/**
 * {@code replay=true}: the request was already processed with {@code resourceId} — return the old
 * result, do NOT process it again.
 */
public record IdempotencyClaim(UUID resourceId, boolean replay) {}
