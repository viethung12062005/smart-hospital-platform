package org.shp.web;

import java.time.Clock;
import org.shp.authz.AuthorizationEngine;
import org.shp.authz.AuthzMatrix;
import org.shp.events.audit.AuditSink;
import org.shp.security.ShpAuthProperties;
import org.shp.security.ShpJwt;
import org.shp.servicedefaults.ServiceInfo;
import org.shp.web.auth.IdentityInterceptor;
import org.shp.web.auth.IdentityLinkClient;
import org.shp.web.auth.IdentityResolver;
import org.shp.web.authz.AuthzInterceptor;
import org.shp.web.errors.ErrorCatalog;
import org.shp.web.errors.ErrorWriter;
import org.shp.web.errors.ShpExceptionHandler;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.PropertySource;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.SupplierJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Cấu hình web dùng chung cho mọi service Spring MVC: xác thực JWT, tra danh tính, phân quyền theo
 * ma trận, audit, lỗi chuẩn. Service chỉ cần cung cấp bean {@link IdentityLinkClient} và {@link
 * AuditSink}.
 */
@AutoConfiguration
@EnableConfigurationProperties(ShpAuthProperties.class)
@PropertySource("classpath:shp-web-defaults.properties")
@Import(ShpExceptionHandler.class)
public class ShpWebAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  Clock shpClock() {
    return Clock.systemUTC();
  }

  @Bean
  @ConditionalOnMissingBean
  AuthorizationEngine shpAuthorizationEngine(Clock clock) {
    return new AuthorizationEngine(AuthzMatrix.loadEmbedded(), clock);
  }

  @Bean
  @ConditionalOnMissingBean
  IdentityResolver shpIdentityResolver(IdentityLinkClient client, Clock clock) {
    return new IdentityResolver(client, clock);
  }

  /**
   * Giải mã JWT lười (chỉ tải cấu hình OIDC khi có token đầu tiên) để service khởi động được khi
   * IdP chưa sẵn.
   */
  @Bean
  @ConditionalOnMissingBean
  JwtDecoder shpJwtDecoder(ShpAuthProperties props, Clock clock) {
    if (props.authority().isBlank() || props.audience().isBlank()) {
      throw new IllegalStateException(
          "Thiếu shp.auth.authority/audience (hoặc OIDC_AUTHORITY, OIDC_AUDIENCE)");
    }
    return new SupplierJwtDecoder(
        () -> {
          var decoder = (NimbusJwtDecoder) JwtDecoders.fromIssuerLocation(props.authority());
          decoder.setJwtValidator(ShpJwt.validator(props, clock));
          return decoder;
        });
  }

  @Bean
  @ConditionalOnMissingBean
  SecurityFilterChain shpSecurityFilterChain(HttpSecurity http) throws Exception {
    http.csrf(c -> c.disable())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            a ->
                a.requestMatchers("/health", "/health/**", "/info", "/prometheus", "/error")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .oauth2ResourceServer(
            o ->
                o.jwt(j -> j.jwtAuthenticationConverter(ShpJwt.authenticationConverter()))
                    .authenticationEntryPoint(
                        (req, res, ex) ->
                            ErrorWriter.write(
                                req, res, ErrorCatalog.get("unauthenticated"), null, null))
                    .accessDeniedHandler(
                        (req, res, ex) ->
                            ErrorWriter.write(req, res, ErrorCatalog.get("forbidden"), null, null)))
        .exceptionHandling(
            e ->
                e.authenticationEntryPoint(
                    (req, res, ex) ->
                        ErrorWriter.write(
                            req, res, ErrorCatalog.get("unauthenticated"), null, null)))
        .httpBasic(b -> b.disable())
        .formLogin(f -> f.disable());
    return http.build();
  }

  @Bean
  WebMvcConfigurer shpInterceptors(
      IdentityResolver resolver,
      AuthorizationEngine engine,
      ObjectProvider<AuditSink> sink,
      Clock clock,
      ServiceInfo info,
      BeanFactory beans) {
    return new WebMvcConfigurer() {
      @Override
      public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new IdentityInterceptor(resolver)).order(0);
        registry
            .addInterceptor(
                new AuthzInterceptor(engine, sink.getObject(), clock, info.name(), beans))
            .order(1);
      }
    };
  }
}
