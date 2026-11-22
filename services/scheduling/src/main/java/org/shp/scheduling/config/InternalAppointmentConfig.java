package org.shp.scheduling.config;

import java.time.Clock;
import java.util.Set;
import org.shp.events.audit.AuditSink;
import org.shp.scheduling.api.InternalAppointmentFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Registers {@link InternalAppointmentFilter} for S18 {@code GET /internal/v1/appointments/{id}}.
 */
@Configuration
class InternalAppointmentConfig {

  @Bean
  FilterRegistrationBean<InternalAppointmentFilter> internalAppointmentFilter(
      JdbcClient jdbc,
      AuditSink audit,
      Clock clock,
      @Value("${shp.scheduling.internal-appointments.required-role:internal.scheduling}")
          String requiredRole,
      @Value("${shp.scheduling.internal-appointments.allowed-clients:svc-clinical,svc-ml}")
          Set<String> allowedClients) {
    var registration =
        new FilterRegistrationBean<>(
            new InternalAppointmentFilter(jdbc, audit, clock, requiredRole, allowedClients));
    registration.addUrlPatterns("/internal/v1/appointments/*");
    // Run after the Spring Security filter chain (order -100) so the verified JWT is available.
    registration.setOrder(Ordered.LOWEST_PRECEDENCE - 100);
    return registration;
  }
}
