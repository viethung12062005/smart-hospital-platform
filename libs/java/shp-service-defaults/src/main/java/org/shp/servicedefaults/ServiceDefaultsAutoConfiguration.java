package org.shp.servicedefaults;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.PropertySource;

/**
 * Tương đương {@code AddServiceDefaults} của bản .NET: health live/ready, /info, tracing, metric
 * Prometheus. Service chỉ cần có thư viện này trên classpath; không cần code.
 */
@AutoConfiguration
@PropertySource("classpath:shp-defaults.properties")
public class ServiceDefaultsAutoConfiguration {

  @Bean
  ServiceInfo shpServiceInfo(
      @Value("${spring.application.name:unknown}") String name,
      @Value("${shp.version:dev}") String version,
      @Value("${GIT_SHA:unknown}") String gitSha) {
    return new ServiceInfo(name, version, gitSha);
  }

  @Bean
  InfoContributor shpInfoContributor(ServiceInfo info) {
    return builder ->
        builder
            .withDetail("name", info.name())
            .withDetail("version", info.version())
            .withDetail("gitSha", info.gitSha());
  }
}
