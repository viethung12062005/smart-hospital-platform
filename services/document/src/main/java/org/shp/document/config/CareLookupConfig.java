package org.shp.document.config;

import org.shp.document.domain.CareLookup;
import org.shp.document.infra.HttpCareLookup;
import org.shp.web.auth.ServiceTokenInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/** Wiring of the care relationship check against the clinical service. */
@Configuration
class CareLookupConfig {
  @Bean
  CareLookup careLookup(
      @Value("${shp.clinical.url}") String clinicalUrl, ServiceTokenInterceptor serviceToken) {
    return new HttpCareLookup(
        RestClient.builder().baseUrl(clinicalUrl).requestInterceptor(serviceToken).build());
  }
}
