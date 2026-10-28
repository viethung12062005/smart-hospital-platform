package org.shp.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * API gateway: định tuyến theo 07-api-conventions §1, kiểm JWT (lớp 1 của 15-authorization). Không
 * quyết định quyền nghiệp vụ.
 */
@SpringBootApplication
@ConfigurationPropertiesScan("org.shp.security")
public class GatewayApplication {
  public static void main(String[] args) {
    SpringApplication.run(GatewayApplication.class, args);
  }
}
