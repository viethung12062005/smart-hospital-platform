package org.shp.web.precondition;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Bắt buộc {@code If-Match: W/"<versionId>"} cho thao tác đổi trạng thái không phải PUT ({@code
 * $cancel}, {@code $reschedule}, {@code $check-in}, {@code $finish}; 07 §3). Mọi endpoint PUT đã
 * bắt buộc sẵn, không cần chú thích này. Handler so version bằng {@link
 * PreconditionInterceptor#checkVersion}.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface RequireIfMatch {}
