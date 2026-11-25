package org.shp.notification.config;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.FirebaseMessaging;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.shp.notification.app.NotificationChannel;
import org.shp.notification.infra.FakeNotificationChannel;
import org.shp.notification.infra.FcmPushChannel;
import org.shp.notification.infra.SmtpEmailChannel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * Selects the real or fake adapter of every channel with {@code
 * shp.notification.channel.<push|email|sms>.mode} (default {@code fake}). Secrets are read only
 * from the environment or a Secret, never from a file in the repository.
 */
@Configuration(proxyBeanMethods = false)
public class NotificationChannelConfig {

  @Bean
  @ConditionalOnProperty(
      name = "shp.notification.channel.push.mode",
      havingValue = "fake",
      matchIfMissing = true)
  NotificationChannel fakePushChannel() {
    return new FakeNotificationChannel("push");
  }

  @Bean
  @ConditionalOnProperty(name = "shp.notification.channel.push.mode", havingValue = "real")
  NotificationChannel fcmPushChannel(
      @Value("${shp.notification.channel.push.credentials-json:}") String credentialsJson)
      throws IOException {
    if (credentialsJson.isBlank()) {
      throw new IllegalStateException(
          "shp.notification.channel.push.credentials-json is required in real mode");
    }
    GoogleCredentials credentials =
        GoogleCredentials.fromStream(
            new ByteArrayInputStream(credentialsJson.getBytes(StandardCharsets.UTF_8)));
    FirebaseOptions options = FirebaseOptions.builder().setCredentials(credentials).build();
    FirebaseApp app =
        FirebaseApp.getApps().isEmpty()
            ? FirebaseApp.initializeApp(options)
            : FirebaseApp.getInstance();
    return FcmPushChannel.of(FirebaseMessaging.getInstance(app));
  }

  @Bean
  @ConditionalOnProperty(
      name = "shp.notification.channel.email.mode",
      havingValue = "fake",
      matchIfMissing = true)
  NotificationChannel fakeEmailChannel() {
    return new FakeNotificationChannel("email");
  }

  @Bean
  @ConditionalOnProperty(name = "shp.notification.channel.email.mode", havingValue = "real")
  NotificationChannel smtpEmailChannel(
      JavaMailSender mailSender, @Value("${shp.notification.channel.email.from:}") String from) {
    return new SmtpEmailChannel(mailSender, from);
  }

  @Bean
  @ConditionalOnProperty(
      name = "shp.notification.channel.sms.mode",
      havingValue = "fake",
      matchIfMissing = true)
  NotificationChannel fakeSmsChannel() {
    return new FakeNotificationChannel("sms");
  }
}
