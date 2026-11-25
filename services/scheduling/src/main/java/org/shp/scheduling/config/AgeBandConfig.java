package org.shp.scheduling.config;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import org.shp.events.EventPublisher;
import org.shp.scheduling.app.AgeBandEnrichingPublisher;
import org.shp.scheduling.domain.AgeBandClient;
import org.shp.scheduling.infra.HttpAgeBandClient;
import org.shp.web.auth.ServiceTokenInterceptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Wires the age band lookup (clinical, 09 §4) and wraps the outbox relay's {@link EventPublisher}
 * so {@code appointment-booked} gets its {@code ageBand} when it is published (SCH-23).
 */
@Configuration
class AgeBandConfig {

  @Bean
  AgeBandClient ageBandClient(
      @Value("${shp.clinical.url}") String clinicalUrl, ServiceTokenInterceptor serviceToken) {
    HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    var requestFactory = new JdkClientHttpRequestFactory(http);
    requestFactory.setReadTimeout(Duration.ofSeconds(3));
    return new HttpAgeBandClient(
        RestClient.builder()
            .baseUrl(clinicalUrl)
            .requestFactory(requestFactory)
            .requestInterceptor(serviceToken)
            .build());
  }

  @Bean
  static BeanPostProcessor ageBandEnrichment(
      ObjectProvider<AgeBandClient> client, ObjectProvider<Clock> clock) {
    return new BeanPostProcessor() {
      @Override
      public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (bean instanceof EventPublisher publisher
            && !(bean instanceof AgeBandEnrichingPublisher)) {
          return new AgeBandEnrichingPublisher(publisher, client.getObject(), clock.getObject());
        }
        return bean;
      }
    };
  }
}
