package org.shp.clinical;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Hồ sơ bệnh nhân, encounter, tìm kiếm lịch sử, liên kết tài khoản (FHIR). */
@SpringBootApplication
public class ClinicalApplication {
  public static void main(String[] args) {
    SpringApplication.run(ClinicalApplication.class, args);
  }
}
