package org.shp.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * The audience rule (ADR-0017) and the {@code /internal} guard, tested without a container: user
 * tokens carry {@code shp-api}, service tokens carry only {@code svc-<target>}, and a service
 * accepts both but nothing else. Python parity: {@code shp_platform.security}.
 */
class ShpJwtTest {
  static final String ISSUER = "http://localhost:18080/realms/shp";
  static final Instant NOW = Instant.parse("2026-11-20T08:00:00Z");
  static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

  static ShpAuthProperties props(String audience) {
    return new ShpAuthProperties(ISSUER, audience, false, Duration.ofSeconds(30), "");
  }

  static Jwt token(String audience, String azp, List<String> roles) {
    var builder =
        Jwt.withTokenValue("token")
            .header("alg", "none")
            .issuer(ISSUER)
            .subject("subject-1")
            .issuedAt(NOW)
            .expiresAt(NOW.plusSeconds(60))
            .audience(List.of(audience));
    if (azp != null) {
      builder.claim("azp", azp);
    }
    if (roles != null) {
      builder.claim("roles", roles);
    }
    return builder.build();
  }

  static OAuth2TokenValidatorResult validate(ShpAuthProperties properties, Jwt jwt) {
    return ShpJwt.validator(properties, CLOCK).validate(jwt);
  }

  @Test
  void acceptsTheServicesOwnAudienceAndTheUserAudience() {
    var clinical = props("svc-clinical");
    assertThat(ShpJwt.acceptedAudiences(clinical))
        .containsExactlyInAnyOrder("svc-clinical", ShpJwt.SHARED_AUDIENCE);
    // a user token (shp-api) and a service token for this service (svc-clinical) both pass
    assertThat(validate(clinical, token("shp-api", "shp-web", List.of("doctor"))).hasErrors())
        .isFalse();
    assertThat(
            validate(
                    clinical, token("svc-clinical", "svc-scheduling", List.of("internal.clinical")))
                .hasErrors())
        .isFalse();
  }

  @Test
  void refusesATokenIssuedForAnotherService() {
    var clinical = props("svc-clinical");
    var other = token("svc-document", "svc-clinical", List.of("internal.document"));
    assertThat(validate(clinical, other).hasErrors()).isTrue();
    assertThat(validate(clinical, other).getErrors())
        .anySatisfy(e -> assertThat(e.getErrorCode()).isEqualTo("invalid_token"));
  }

  @Test
  void theUserAudienceIsAcceptedEvenWhenTheServiceAudienceIsUnset() {
    // gateway: user-facing only, OIDC_AUDIENCE stays shp-api
    assertThat(ShpJwt.acceptedAudiences(props(""))).containsExactly(ShpJwt.SHARED_AUDIENCE);
    assertThat(validate(props(""), token("shp-api", "shp-web", List.of("patient"))).hasErrors())
        .isFalse();
    assertThat(validate(props(""), token("svc-clinical", "svc-ai", List.of())).hasErrors())
        .isTrue();
  }

  @Test
  void requireServiceAcceptsAnAllowedCallerWithTheInternalRole() {
    var jwt = token("svc-clinical", "svc-scheduling", List.of("internal.clinical"));
    assertThat(ShpJwt.isServiceToken(jwt, "clinical", "svc-scheduling", "svc-ai")).isTrue();
    assertThatCode(() -> ShpJwt.requireService(jwt, "clinical", "svc-scheduling"))
        .doesNotThrowAnyException();
  }

  @Test
  void requireServiceRefusesAUserTokenAnotherCallerOrAnotherTarget() {
    var user = token("shp-api", "shp-web", List.of("doctor", "nurse"));
    assertThatThrownBy(() -> ShpJwt.requireService(user, "clinical", "svc-scheduling"))
        .isInstanceOf(AccessDeniedException.class);
    // the internal role of another target is not enough
    var wrongTarget = token("svc-clinical", "svc-scheduling", List.of("internal.document"));
    assertThatThrownBy(() -> ShpJwt.requireService(wrongTarget, "clinical", "svc-scheduling"))
        .isInstanceOf(AccessDeniedException.class);
    // a service that is not in the list of callers of this endpoint
    var wrongCaller = token("svc-clinical", "svc-ml", List.of("internal.clinical"));
    assertThatThrownBy(() -> ShpJwt.requireService(wrongCaller, "clinical", "svc-scheduling"))
        .isInstanceOf(AccessDeniedException.class);
    // a token without azp or without roles is never a service token
    assertThat(
            ShpJwt.isServiceToken(
                token("svc-clinical", null, List.of("internal.clinical")),
                "clinical",
                "svc-scheduling"))
        .isFalse();
    assertThat(
            ShpJwt.isServiceToken(
                token("svc-clinical", "svc-scheduling", null), "clinical", "svc-scheduling"))
        .isFalse();
    assertThat(
            ShpJwt.isServiceToken(
                token("svc-clinical", "svc-scheduling", List.of("internal.clinical")), "clinical"))
        .isFalse();
  }

  @Test
  void acceptedAudiencesHasNoDuplicatesWhenTheServiceAudienceIsTheSharedOne() {
    assertThat(ShpJwt.acceptedAudiences(props(ShpJwt.SHARED_AUDIENCE)))
        .isEqualTo(Set.of(ShpJwt.SHARED_AUDIENCE));
  }
}
