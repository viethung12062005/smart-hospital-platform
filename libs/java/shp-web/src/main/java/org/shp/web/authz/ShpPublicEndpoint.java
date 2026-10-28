package org.shp.web.authz;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Đánh dấu endpoint công khai có chủ đích (không cần phân quyền theo ma trận). */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface ShpPublicEndpoint {}
