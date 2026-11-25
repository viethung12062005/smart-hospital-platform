package org.shp.notification.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.shp.notification.app.NotificationChannel;
import org.shp.notification.infra.FakeNotificationChannel;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class NotificationChannelConfigTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner().withUserConfiguration(NotificationChannelConfig.class);

  @Test
  void defaultsToFakeAdaptersForEveryChannel() {
    runner.run(context -> {
      List<String> names =
          context.getBeansOfType(NotificationChannel.class).values().stream()
              .map(NotificationChannel::channelName)
              .sorted()
              .toList();
      assertThat(names).containsExactly("email", "push", "sms");
      assertThat(context.getBeansOfType(NotificationChannel.class).values())
          .allMatch(FakeNotificationChannel.class::isInstance);
    });
  }

  @Test
  void realPushModeWithoutCredentialsFailsStartup() {
    runner
        .withPropertyValues("shp.notification.channel.push.mode=real")
        .run(context -> assertThat(context).hasFailed());
  }
}
