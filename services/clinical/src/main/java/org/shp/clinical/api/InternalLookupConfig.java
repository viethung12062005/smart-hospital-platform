package org.shp.clinical.api;

import java.time.Clock;
import java.util.Map;
import java.util.Set;
import org.shp.clinical.api.InternalLookupFilter.Route;
import org.shp.clinical.app.InternalLookups;
import org.shp.events.audit.AuditSink;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Registers {@link InternalLookupFilter} with the callers of 09 §4 for each route. The lists are
 * configurable ({@code shp.clinical.internal.*}) so a new caller needs no code change.
 */
@Configuration
class InternalLookupConfig {

  @Bean
  FilterRegistrationBean<InternalLookupFilter> internalLookupFilter(
      InternalLookups lookups,
      AuditSink audit,
      Clock clock,
      @Value("${shp.clinical.internal.required-role:internal.clinical}") String requiredRole,
      @Value("${shp.clinical.internal.care-clients:svc-document,svc-ai,svc-notification}")
          Set<String> careClients,
      @Value("${shp.clinical.internal.roles-clients:svc-scheduling}") Set<String> rolesClients,
      @Value("${shp.clinical.internal.contact-clients:svc-notification}")
          Set<String> contactClients,
      @Value("${shp.clinical.internal.age-band-clients:svc-scheduling}")
          Set<String> ageBandClients) {
    var filter =
        new InternalLookupFilter(
            lookups,
            audit,
            clock,
            requiredRole,
            Map.of(
                Route.CARE, careClients,
                Route.ROLES, rolesClients,
                Route.CONTACT, contactClients,
                Route.AGE_BAND, ageBandClients));
    var registration = new FilterRegistrationBean<>(filter);
    registration.addUrlPatterns(
        "/internal/v1/care-relationships/*",
        "/internal/v1/practitioners/*",
        "/internal/v1/patients/*");
    // After the Spring Security filter chain (order -100) so the validated token is available.
    registration.setOrder(Ordered.LOWEST_PRECEDENCE - 100);
    return registration;
  }
}
