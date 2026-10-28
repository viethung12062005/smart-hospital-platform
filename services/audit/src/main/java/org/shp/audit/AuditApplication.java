package org.shp.audit;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Nhận và tra cứu audit, chuỗi hash bất biến. */
@SpringBootApplication
public class AuditApplication {
  public static void main(String[] args) {
    SpringApplication.run(AuditApplication.class, args);
  }
}
