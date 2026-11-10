package org.shp.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * API gateway: routes per 07-api-conventions §1, validates JWT (layer 1 of 15-authorization). Does
 * not decide business authorization.
 */
@SpringBootApplication
@ConfigurationPropertiesScan("org.shp.security")
public class GatewayApplication {
  public static void main(String[] args) {
    SpringApplication.run(GatewayApplication.class, args);
  }
}
