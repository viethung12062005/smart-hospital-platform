package org.shp.security;

import java.time.Clock;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
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
 * Quy tắc JWT dùng chung: issuer, audience, hạn (có độ lệch), MFA cho nhân viên; đổi claim {@code
 * roles} thành quyền. Tương đương {@code AddShpAuthentication} bản .NET.
 */
public final class ShpJwt {
  private ShpJwt() {}

  public static OAuth2TokenValidator<Jwt> validator(ShpAuthProperties props, Clock clock) {
    var timestamps = new JwtTimestampValidator(props.clockSkew());
    timestamps.setClock(clock);
    return new DelegatingOAuth2TokenValidator<>(
        timestamps,
        new JwtIssuerValidator(props.authority()),
        new JwtClaimValidator<Collection<String>>(
            "aud", aud -> aud != null && aud.contains(props.audience())),
        jwt -> mfa(jwt, props));
  }

  /** Role hợp lệ trong claim {@code roles} (bỏ giá trị lạ, không phân biệt hoa thường). */
  public static List<String> roles(Jwt jwt) {
    List<String> raw = jwt.getClaimAsStringList("roles");
    return raw == null
        ? List.of()
        : raw.stream()
            .filter(Objects::nonNull)
            .map(r -> r.toLowerCase(java.util.Locale.ROOT))
            .filter(Roles.ALL::contains)
            .distinct()
            .toList();
  }

  public static JwtAuthenticationConverter authenticationConverter() {
    var c = new JwtAuthenticationConverter();
    c.setPrincipalClaimName("sub");
    c.setJwtGrantedAuthoritiesConverter(
        jwt ->
            roles(jwt).stream()
                .map(r -> (GrantedAuthority) new SimpleGrantedAuthority(r))
                .toList());
    return c;
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
