package org.shp.web.precondition;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Bắt buộc {@code Idempotency-Key} (07 §3: {@code $book}, {@code $cancel}, {@code $reschedule}, D1,
 * AI4, AI6, AI8). Thiếu → 428 {@code idempotency-key-required}; sai định dạng → 400. Handler lấy
 * key bằng {@link PreconditionInterceptor#idempotencyKey} rồi truyền cho {@code
 * PostgresIdempotency} hoặc {@code MongoIdempotency}.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface RequireIdempotencyKey {}
