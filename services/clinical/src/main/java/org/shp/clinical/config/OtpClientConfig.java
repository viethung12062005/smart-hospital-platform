package org.shp.clinical.config;

import org.shp.clinical.app.OtpClient;
import org.shp.clinical.infra.HttpOtpClient;
import org.shp.web.auth.ServiceTokenInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/** The client for notification's OTP API (N8, N9), with clinical's workload identity token. */
@Configuration
public class OtpClientConfig {
  @Bean
  OtpClient otpClient(
      @Value("${shp.notification.url}") String notificationUrl,
      ServiceTokenInterceptor serviceToken) {
    return new HttpOtpClient(
        RestClient.builder().baseUrl(notificationUrl).requestInterceptor(serviceToken).build());
  }
}
