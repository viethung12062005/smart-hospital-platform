package org.shp.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.azure.spring.cloud.autoconfigure.implementation.servicebus.properties.AzureServiceBusProperties;
import java.util.Set;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.shp.events.EventPublisher;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.stream.binder.Binder;
import org.springframework.cloud.stream.binder.BinderFactory;
import org.springframework.cloud.stream.binder.ExtendedBindingProperties;
import org.springframework.cloud.stream.config.BindingServiceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;

/**
 * Wiring of the Service Bus binder (13-events §1.1), WITHOUT a broker: Testcontainers cannot run
 * Azure Service Bus (AMQP 1.0). It proves that, with {@code SHP_MESSAGING_BINDER=servicebus}, the
 * context starts with the binder on the classpath, the default binder and in-process attempts
 * follow the binder, and the consumer/producer binding properties resolve to the values the
 * shp-infra topic and subscription require. It does NOT prove delivery, sessions, the 5-delivery
 * dead-letter or Entra sign-in; that needs the Service Bus emulator or a real namespace.
 */
@SpringBootTest(
    classes = MessagingServiceBusProfileTest.App.class,
    properties = {
      "SHP_MESSAGING_BINDER=servicebus",
      "SHP_SERVICEBUS_NAMESPACE=sb-shp-test",
      "spring.cloud.function.definition=testIn",
      "spring.cloud.stream.bindings.testIn-in-0.destination=shp-events",
      "spring.cloud.stream.bindings.testIn-in-0.group=test-svc",
      // never open a connection: no credential exists in the test
      "spring.cloud.stream.bindings.testIn-in-0.consumer.auto-startup=false",
      "spring.autoconfigure.exclude=org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration"
    })
class MessagingServiceBusProfileTest {
  @Autowired BindingServiceProperties binding;
  @Autowired AzureServiceBusProperties servicebus;
  @Autowired EventPublisher publisher;
  @Autowired ServiceBusProbe probe;

  @Test
  void contextStartsWithTheServiceBusBinderAndTheRealPublisher() {
    assertThat(binding.getDefaultBinder()).isEqualTo("servicebus");
    assertThat(publisher).isInstanceOf(StreamEventPublisher.class);
    assertThat(servicebus.getFullyQualifiedNamespace())
        .isEqualTo("sb-shp-test.servicebus.windows.net");
  }

  @Test
  void brokerOwnsRedeliveryAndInProcessRetryIsOff() {
    assertThat(binding.getConsumerProperties("testIn-in-0").getMaxAttempts()).isEqualTo(1);
  }

  @Test
  void consumerBindingTargetsTheSessionSubscriptionOfTheTopic() {
    assertThat(binding.getBindingDestination("testIn-in-0")).isEqualTo("shp-events");
    assertThat(binding.getGroup("testIn-in-0")).isEqualTo("test-svc");
    assertThat(probe.consumerValue("testIn-in-0", "entityType")).hasToString("TOPIC");
    assertThat(probe.consumerValue("testIn-in-0", "sessionEnabled")).isEqualTo(true);
    assertThat(probe.consumerValue("testIn-in-0", "requeueRejected")).isEqualTo(true);
  }

  @Test
  void producerBindingTargetsTheTopic() {
    assertThat(probe.producerValue(StreamEventPublisher.BINDING, "entityType"))
        .hasToString("TOPIC");
    assertThat(binding.getBindingDestination(StreamEventPublisher.BINDING)).isEqualTo("shp-events");
  }

  @SpringBootConfiguration
  @EnableAutoConfiguration
  static class App {
    @Bean
    Consumer<Message<byte[]>> testIn() {
      return EventConsumers.idempotent(
          "test-svc", Set.of("shp.scheduling.appointment-booked.v1"), (c, e, h) -> false, e -> {});
    }

    @Bean
    ServiceBusProbe probe(BinderFactory factory) {
      // creates the Service Bus binder (its own child context) without connecting to a namespace
      Binder<?, ?, ?> binder = factory.getBinder(null, MessageChannel.class);
      return new ServiceBusProbe((ExtendedBindingProperties<?, ?>) binder);
    }
  }

  /** Reads the extended Service Bus properties the binder will use for a binding. */
  static final class ServiceBusProbe {
    private final ExtendedBindingProperties<?, ?> props;

    ServiceBusProbe(ExtendedBindingProperties<?, ?> props) {
      this.props = props;
    }

    Object consumerValue(String name, String property) {
      return read(props.getExtendedConsumerProperties(name), property);
    }

    Object producerValue(String name, String property) {
      return read(props.getExtendedProducerProperties(name), property);
    }

    private static Object read(Object bean, String property) {
      return new BeanWrapperImpl(bean).getPropertyValue(property);
    }
  }
}
