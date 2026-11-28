package org.shp.scheduling.config;

import java.net.http.HttpClient;
import java.time.Duration;
import org.shp.scheduling.domain.PatientClient;
import org.shp.scheduling.domain.PractitionerRoleClient;
import org.shp.scheduling.infra.HttpPatientClient;
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
    return new HttpPractitionerRoleClient(clinicalClient(clinicalUrl, serviceToken));
  }

  @Bean
  PatientClient patientClient(
      @Value("${shp.clinical.url}") String clinicalUrl, ServiceTokenInterceptor serviceToken) {
    return new HttpPatientClient(clinicalClient(clinicalUrl, serviceToken));
  }

  private static RestClient clinicalClient(
      String clinicalUrl, ServiceTokenInterceptor serviceToken) {
    HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    var requestFactory = new JdkClientHttpRequestFactory(http);
    requestFactory.setReadTimeout(Duration.ofSeconds(2));
    return RestClient.builder()
        .baseUrl(clinicalUrl)
        .requestFactory(requestFactory)
        .requestInterceptor(serviceToken)
        .build();
  }
}
