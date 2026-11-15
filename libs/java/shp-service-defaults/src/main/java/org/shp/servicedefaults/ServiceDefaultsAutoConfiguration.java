package org.shp.servicedefaults;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Shared configuration: health live/ready, /info, tracing, Prometheus metrics. A service only needs
 * this library on the classpath; no code required.
 */
@AutoConfiguration
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
