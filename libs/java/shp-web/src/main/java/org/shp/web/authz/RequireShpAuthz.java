package org.shp.web.authz;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Bắt buộc phân quyền theo ma trận + ghi audit cho MỌI quyết định (cho phép, từ chối) và lỗi khi xử
 * lý. Một dòng cho mỗi endpoint, ví dụ {@code @RequireShpAuthz(resource = "Appointment", action =
 * "cancel", context = "appointmentFacts")}.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface RequireShpAuthz {
  /** Tên resource trong matrix.yaml. */
  String resource();

  /** Hành động trong matrix.yaml. */
  String action();

  /**
   * Tên bean {@link ResourceContextResolver} tra dữ kiện về tài nguyên; rỗng = không có dữ kiện.
   */
  String context() default "";

  /** true: từ chối trả 404 (không lộ sự tồn tại, 07 §4); audit vẫn ghi denied. */
  boolean denyAsNotFound() default false;
}
