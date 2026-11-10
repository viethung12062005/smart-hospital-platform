package org.shp.web.authz;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Require matrix-based authorization + audit EVERY decision (allow, deny) and processing errors.
 * One line per endpoint, for example {@code @RequireShpAuthz(resource = "Appointment", action =
 * "cancel", context = "appointmentFacts")}.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface RequireShpAuthz {
  /** Resource name in matrix.yaml. */
  String resource();

  /** Action in matrix.yaml. */
  String action();

  /**
   * Name of the {@link ResourceContextResolver} bean that looks up resource facts; empty = no
   * facts.
   */
  String context() default "";

  /**
   * true: denial returns 404 (does not reveal existence, 07 §4); the audit still records denied.
   */
  boolean denyAsNotFound() default false;
}
