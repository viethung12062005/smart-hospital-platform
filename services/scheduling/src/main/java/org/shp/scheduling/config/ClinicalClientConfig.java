package org.shp.scheduling.config;

import java.net.http.HttpClient;
import java.time.Duration;
import org.shp.scheduling.domain.PractitionerRoleClient;
import org.shp.scheduling.infra.HttpPractitionerRoleClient;
import org.shp.web.auth.ServiceTokenInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** Wires the HTTP client for the clinical service's internal practitioner role lookup API. */
@Configuration
class ClinicalClientConfig {

  @Bean
  PractitionerRoleClient practitionerRoleClient(
      @Value("${shp.clinical.url}") String clinicalUrl, ServiceTokenInterceptor serviceToken) {
    HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    var requestFactory = new JdkClientHttpRequestFactory(http);
    requestFactory.setReadTimeout(Duration.ofSeconds(5));
    RestClient client =
        RestClient.builder()
            .baseUrl(clinicalUrl)
            .requestFactory(requestFactory)
            .requestInterceptor(serviceToken)
            .build();
    return new HttpPractitionerRoleClient(client);
  }
}
