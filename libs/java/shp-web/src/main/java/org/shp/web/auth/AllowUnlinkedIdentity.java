package org.shp.web.auth;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Gắn vào endpoint (ví dụ đăng ký C1) cho phép tài khoản đã xác thực nhưng CHƯA liên kết. Tài khoản
 * bị khóa vẫn bị từ chối.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface AllowUnlinkedIdentity {}
