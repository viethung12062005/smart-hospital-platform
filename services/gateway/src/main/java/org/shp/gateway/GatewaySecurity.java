package org.shp.gateway;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import org.shp.security.ShpAuthProperties;
import org.shp.security.ShpJwt;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoders;
import org.springframework.security.oauth2.jwt.SupplierReactiveJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverterAdapter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Lớp 1 ở gateway: token phải hợp lệ (chữ ký, issuer, audience, hạn, MFA nhân viên) cho mọi đường
 * dẫn được định tuyến. {@code /internal/**} không bao giờ đi qua gateway. Service vẫn kiểm lại
 * token (phòng thủ nhiều lớp).
 */
@Configuration
class GatewaySecurity {

  @Bean
  @ConditionalOnMissingBean
  Clock clock() {
    return Clock.systemUTC();
  }

  @Bean
  @ConditionalOnMissingBean
  ReactiveJwtDecoder jwtDecoder(ShpAuthProperties props, Clock clock) {
    if (props.authority().isBlank() || props.audience().isBlank()) {
      throw new IllegalStateException(
          "Thiếu shp.auth.authority/audience (hoặc OIDC_AUTHORITY, OIDC_AUDIENCE)");
    }
    return new SupplierReactiveJwtDecoder(
        () -> {
          var d =
              props.jwkSetUri().isBlank()
                  ? (NimbusReactiveJwtDecoder)
                      ReactiveJwtDecoders.fromIssuerLocation(props.authority())
                  : NimbusReactiveJwtDecoder.withJwkSetUri(props.jwkSetUri()).build();
          d.setJwtValidator(ShpJwt.validator(props, clock));
          return d;
        });
  }

  @Bean
  SecurityWebFilterChain gatewaySecurityChain(ServerHttpSecurity http) {
    return http.csrf(ServerHttpSecurity.CsrfSpec::disable)
        .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
        .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
        .authorizeExchange(
            a ->
                a.pathMatchers("/internal/**")
                    .denyAll()
                    .pathMatchers("/health", "/health/**", "/info", "/prometheus")
                    .permitAll()
                    .anyExchange()
                    .authenticated())
        .oauth2ResourceServer(
            o ->
                o.jwt(
                        j ->
                            j.jwtAuthenticationConverter(
                                new ReactiveJwtAuthenticationConverterAdapter(
                                    ShpJwt.authenticationConverter())))
                    .authenticationEntryPoint(
                        (ex, e) ->
                            problem(ex.getResponse(), HttpStatus.UNAUTHORIZED, "unauthenticated"))
                    .accessDeniedHandler(
                        (ex, e) -> problem(ex.getResponse(), HttpStatus.NOT_FOUND, "not-found")))
        .exceptionHandling(
            e ->
                e.authenticationEntryPoint(
                    (ex, err) ->
                        problem(ex.getResponse(), HttpStatus.UNAUTHORIZED, "unauthenticated")))
        .build();
  }

  /** Lỗi theo Problem Details của 07 §5 (gateway không có trang lỗi riêng). */
  static Mono<Void> problem(ServerHttpResponse res, HttpStatus status, String code) {
    res.setStatusCode(status);
    res.getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
    String body =
        "{\"type\":\"https://shp.example.org/errors/%s\",\"title\":\"%s\",\"status\":%d,\"code\":\"%s\"}"
            .formatted(code, code, status.value(), code);
    DataBuffer buf = res.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
    return res.writeWith(Mono.just(buf));
  }
}
