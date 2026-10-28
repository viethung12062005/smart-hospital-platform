package org.shp.data.postgres;

import java.util.UUID;

/**
 * {@code replay=true}: yêu cầu đã được xử lý với {@code resourceId} — trả lại kết quả cũ, KHÔNG xử
 * lý lại.
 */
public record IdempotencyClaim(UUID resourceId, boolean replay) {}
