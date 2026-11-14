package org.shp.web.precondition;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Require {@code If-Match: W/"<versionId>"} for state-changing operations other than PUT ({@code
 * $cancel}, {@code $reschedule}, {@code $check-in}, {@code $finish}; 07 §3). Every PUT endpoint
 * already requires it, so this annotation is not needed there. The handler compares versions with
 * {@link PreconditionInterceptor#checkVersion}.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface RequireIfMatch {}
