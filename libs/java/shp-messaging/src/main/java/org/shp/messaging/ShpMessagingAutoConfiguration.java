package org.shp.messaging;

import org.shp.events.EventPublisher;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.cloud.stream.config.BindingServiceConfiguration;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.context.annotation.Bean;

/** Real {@link EventPublisher} for the service outbox relay: Spring Cloud Stream. */
@AutoConfiguration(after = BindingServiceConfiguration.class)
public class ShpMessagingAutoConfiguration {
  @Bean
  @ConditionalOnBean(StreamBridge.class)
  @ConditionalOnMissingBean(EventPublisher.class)
  StreamEventPublisher shpEventPublisher(StreamBridge bridge) {
    return new StreamEventPublisher(bridge);
  }
}
