package org.shp.document.config;

import java.net.http.HttpClient;
import java.time.Duration;
import org.shp.document.domain.CareLookup;
import org.shp.document.infra.HttpCareLookup;
import org.shp.web.auth.ServiceTokenInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Wiring of the care relationship check against the clinical service. The check runs in the
 * authorization path of every doctor upload, so it is bounded: a clinical service that does not
 * connect or answer within the timeouts ends the request as {@code dependency-unavailable} (fail
 * closed) instead of holding a request thread.
 */
@Configuration
class CareLookupConfig {
  static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
  static final Duration READ_TIMEOUT = Duration.ofSeconds(2);

  @Bean
  CareLookup careLookup(
      @Value("${shp.clinical.url}") String clinicalUrl, ServiceTokenInterceptor serviceToken) {
    HttpClient http = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
    var requestFactory = new JdkClientHttpRequestFactory(http);
    requestFactory.setReadTimeout(READ_TIMEOUT);
    return new HttpCareLookup(
        RestClient.builder()
            .baseUrl(clinicalUrl)
            .requestFactory(requestFactory)
            .requestInterceptor(serviceToken)
            .build());
  }
}
