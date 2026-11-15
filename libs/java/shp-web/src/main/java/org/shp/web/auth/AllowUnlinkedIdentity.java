package org.shp.web.auth;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Attach to an endpoint (for example C1 registration) to allow an authenticated account that is NOT
 * yet linked. A locked account is still rejected.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface AllowUnlinkedIdentity {}
