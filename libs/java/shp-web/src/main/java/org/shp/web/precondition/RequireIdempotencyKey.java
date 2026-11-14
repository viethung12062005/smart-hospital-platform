package org.shp.web.precondition;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Require {@code Idempotency-Key} (07 §3: {@code $book}, {@code $cancel}, {@code $reschedule}, D1,
 * AI4, AI6, AI8). Missing → 428 {@code idempotency-key-required}; invalid format → 400. The handler
 * gets the key via {@link PreconditionInterceptor#idempotencyKey} then passes it to {@code
 * PostgresIdempotency} or {@code MongoIdempotency}.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface RequireIdempotencyKey {}
