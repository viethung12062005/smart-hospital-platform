package org.shp.notification.config;

import java.net.http.HttpClient;
import java.time.Duration;
import org.shp.notification.app.ContactClient;
import org.shp.notification.infra.HttpContactClient;
import org.shp.web.auth.ServiceTokenInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** Wires the HTTP client for the clinical service's internal contact lookup (09 §4). */
@Configuration
class ContactClientConfig {

  @Bean
  ContactClient contactClient(
      @Value("${shp.clinical.url}") String clinicalUrl, ServiceTokenInterceptor serviceToken) {
    var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    var requestFactory = new JdkClientHttpRequestFactory(http);
    requestFactory.setReadTimeout(Duration.ofSeconds(5));
    return new HttpContactClient(
        RestClient.builder()
            .baseUrl(clinicalUrl)
            .requestFactory(requestFactory)
            .requestInterceptor(serviceToken)
            .build());
  }
}
