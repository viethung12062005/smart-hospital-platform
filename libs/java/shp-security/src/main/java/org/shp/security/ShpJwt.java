package org.shp.security;

import java.time.Clock;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;

/**
 * Shared JWT rules: issuer, audience, expiry (with clock skew), MFA for staff; converts the {@code
 * roles} claim into authorities; guards {@code /internal} endpoints.
 *
 * <p>Audience is an isolation boundary (ADR-0017): a user (interactive) token carries {@code
 * shp-api}, a service token carries only {@code svc-<target>}. A service therefore accepts its own
 * service audience and {@code shp-api}, and a token issued for another service is refused before
 * any role is looked at.
 */
public final class ShpJwt {
  /** Audience of user (interactive) tokens; every service accepts it as well as its own. */
  public static final String SHARED_AUDIENCE = "shp-api";

  private ShpJwt() {}

  /** Audiences this service accepts: its own ({@code svc-<name>}) and {@code shp-api}. */
  public static Set<String> acceptedAudiences(ShpAuthProperties props) {
    Set<String> accepted = new LinkedHashSet<>();
    if (!props.audience().isBlank()) {
      accepted.add(props.audience());
    }
    accepted.add(SHARED_AUDIENCE);
    return Set.copyOf(accepted);
  }

  public static OAuth2TokenValidator<Jwt> validator(ShpAuthProperties props, Clock clock) {
    var timestamps = new JwtTimestampValidator(props.clockSkew());
    timestamps.setClock(clock);
    Set<String> accepted = acceptedAudiences(props);
    return new DelegatingOAuth2TokenValidator<>(
        timestamps,
        new JwtIssuerValidator(props.authority()),
        ShpJwt::rejectIdToken,
        new JwtClaimValidator<Collection<String>>(
            "aud", aud -> aud != null && aud.stream().anyMatch(accepted::contains)),
        jwt -> mfa(jwt, props));
  }

  private static OAuth2TokenValidatorResult rejectIdToken(Jwt jwt) {
    // Keycloak ID tokens are not API access tokens, even when their audience is shp-api.
    // Other compatible issuers need not supply a typ claim.
    return "ID".equals(jwt.getClaims().get("typ"))
        ? OAuth2TokenValidatorResult.failure(
            new OAuth2Error("invalid_token", "id-token-not-accepted", null))
        : OAuth2TokenValidatorResult.success();
  }

  /** Valid roles in the {@code roles} claim (unknown values dropped, case-insensitive). */
  public static List<String> roles(Jwt jwt) {
    List<String> raw = jwt.getClaimAsStringList("roles");
    return raw == null
        ? List.of()
        : raw.stream()
            .filter(Objects::nonNull)
            .map(r -> r.toLowerCase(Locale.ROOT))
            .filter(Roles.ALL::contains)
            .distinct()
            .toList();
  }

  public static JwtAuthenticationConverter authenticationConverter() {
    var c = new JwtAuthenticationConverter();
    c.setPrincipalClaimName("sub");
    c.setJwtGrantedAuthoritiesConverter(jwt ->
        roles(jwt).stream().map(r -> (GrantedAuthority) new SimpleGrantedAuthority(r)).toList());
    return c;
  }

  /**
   * Whether the token is a service token of an allowed caller for {@code /internal} of {@code
   * service}: {@code azp} is one of {@code callers} and {@code roles} contains {@code
   * internal.<service>}. Python parity: {@code Authenticator.require_service}.
   */
  public static boolean isServiceToken(Jwt jwt, String service, String... callers) {
    String azp = jwt.getClaimAsString("azp");
    if (azp == null || callers.length == 0 || !List.of(callers).contains(azp)) {
      return false;
    }
    List<String> roles = jwt.getClaimAsStringList("roles");
    return roles != null && roles.contains("internal." + service);
  }

  /**
   * Guard for an {@code /internal} endpoint (15 section 1). Use it on every internal endpoint; the
   * gateway additionally never routes {@code /internal/**} from outside.
   *
   * @throws AccessDeniedException when the token is not a service token of an allowed caller
   */
  public static void requireService(Jwt jwt, String service, String... callers) {
    if (!isServiceToken(jwt, service, callers)) {
      throw new AccessDeniedException("service-token-required");
    }
  }

  private static OAuth2TokenValidatorResult mfa(Jwt jwt, ShpAuthProperties props) {
    if (!props.requireMfaForStaff() || roles(jwt).stream().noneMatch(Roles.STAFF::contains)) {
      return OAuth2TokenValidatorResult.success();
    }
    List<String> amr = jwt.getClaimAsStringList("amr");
    boolean hasMfa = amr != null && amr.stream().anyMatch(a -> "mfa".equalsIgnoreCase(a));
    return hasMfa
        ? OAuth2TokenValidatorResult.success()
        : OAuth2TokenValidatorResult.failure(
            new OAuth2Error("invalid_token", "mfa-required", null));
  }
}
