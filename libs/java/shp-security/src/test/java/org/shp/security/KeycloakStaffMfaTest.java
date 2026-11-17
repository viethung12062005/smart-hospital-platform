package org.shp.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jayway.jsonpath.JsonPath;
import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * IAM-10 against a real Keycloak 26.8.2 with the local realm built from identity/realm: staff
 * (doctor, nurse, admin, dpo) must present a TOTP code, the access token then carries {@code amr}
 * containing {@code mfa} (Keycloak's own AMR mapper, reference value set on the OTP step), and
 * {@link ShpJwt} accepts it as staff. A staff user with no second factor is sent to enrol one and
 * the token of that first login is refused as staff. Patients are not forced. TOTP seeds are
 * synthetic and exist only here.
 */
@Testcontainers
class KeycloakStaffMfaTest {
  static final String PASSWORD = "Test-only-password-1";
  static final String SEED = KeycloakTestSupport.TEST_TOTP_SEED;

  @Container
  static final GenericContainer<?> KEYCLOAK =
      KeycloakTestSupport.container("dev/chart/shp-local-deps/files/realm-shp.json");

  static String base;
  static String issuer;
  static String admin;

  @BeforeAll
  static void start() throws Exception {
    base = KeycloakTestSupport.base(KEYCLOAK);
    issuer = base + "/realms/shp";
    admin = KeycloakTestSupport.adminToken(base);
  }

  static HttpResponse<String> passwordGrant(String username, String totp) throws Exception {
    return KeycloakTestSupport.formResponse(
        issuer + "/protocol/openid-connect/token",
        "grant_type=password&client_id=shp-test&username="
            + username
            + "&password="
            + URLEncoder.encode(PASSWORD, StandardCharsets.UTF_8)
            + (totp == null ? "" : "&totp=" + totp));
  }

  static NimbusJwtDecoder decoder(boolean requireMfa) {
    var props = new ShpAuthProperties(issuer, "shp-api", requireMfa, Duration.ofSeconds(30), "");
    NimbusJwtDecoder d =
        NimbusJwtDecoder.withJwkSetUri(issuer + "/protocol/openid-connect/certs").build();
    d.setJwtValidator(ShpJwt.validator(props, Clock.systemUTC()));
    return d;
  }

  static String code() {
    try {
      return KeycloakTestSupport.totp(SEED);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"doctor", "nurse", "admin", "dpo"})
  void everyStaffRoleNeedsTheSecondFactorAndThenCarriesAmrMfa(String role) throws Exception {
    String withOtp = "mfa." + role;
    KeycloakTestSupport.createUser(base, admin, withOtp, role, PASSWORD, SEED);
    String noCode = "mfa.nocode." + role;
    KeycloakTestSupport.createUser(base, admin, noCode, role, PASSWORD, SEED);

    // the password alone is not enough
    assertThat(passwordGrant(noCode, null).statusCode()).isEqualTo(400);
    // the password and a valid code is
    var ok = passwordGrant(withOtp, code());
    assertThat(ok.statusCode()).as(ok.body()).isEqualTo(200);
    String token = JsonPath.read(ok.body(), "$.access_token");
    assertThat(KeycloakTestSupport.claims(token).<List<String>>read("$.amr")).contains("mfa");
    assertThat(ShpJwt.roles(decoder(true).decode(token))).containsExactly(role);
  }

  @Test
  void aWrongCodeIsRefused() throws Exception {
    KeycloakTestSupport.createUser(base, admin, "mfa.wrong", "doctor", PASSWORD, SEED);
    var res = passwordGrant("mfa.wrong", "000000");
    assertThat(res.statusCode()).isEqualTo(400);
    assertThat(res.body()).doesNotContain("access_token");
  }

  @Test
  void aStaffUserWithoutASecondFactorCannotGetAToken() throws Exception {
    KeycloakTestSupport.createUser(base, admin, "mfa.none", "doctor", PASSWORD, null);
    var res = passwordGrant("mfa.none", null);
    assertThat(res.statusCode()).isNotEqualTo(200);
    assertThat(res.body()).doesNotContain("access_token");
  }

  @Test
  void browserLoginOfAStaffUserWithoutOtpForcesEnrolmentAndThatTokenIsNotStaffGrade()
      throws Exception {
    KeycloakTestSupport.createUser(base, admin, "mfa.enrol", "doctor", PASSWORD, null);
    var result = new KeycloakBrowserLogin(issuer).signIn("mfa.enrol", PASSWORD, () -> "unused");
    // the realm asked for a second factor before issuing anything
    assertThat(result.pages()).containsExactly("username-password", "configure-otp");
    // the enrolment login did not present an existing factor: no mfa in amr, so ShpJwt refuses
    // the token as a staff token (the user signs in again and is asked for the new code)
    assertThat(result.accessToken()).isNotNull();
    assertThat(KeycloakTestSupport.claims(result.accessToken()).<List<String>>read("$.amr"))
        .doesNotContain("mfa");
    assertThatThrownBy(() -> decoder(true).decode(result.accessToken()))
        .isInstanceOfSatisfying(
            JwtValidationException.class,
            e ->
                assertThat(e.getErrors())
                    .anySatisfy(err -> assertThat(err.getDescription()).isEqualTo("mfa-required")));
  }

  @Test
  void browserLoginOfAStaffUserWithOtpAsksForTheCodeAndIssuesAnMfaToken() throws Exception {
    KeycloakTestSupport.createUser(base, admin, "mfa.browser", "nurse", PASSWORD, SEED);
    var result =
        new KeycloakBrowserLogin(issuer)
            .signIn("mfa.browser", PASSWORD, KeycloakStaffMfaTest::code);
    assertThat(result.pages()).containsExactly("username-password", "otp");
    assertThat(KeycloakTestSupport.claims(result.accessToken()).<List<String>>read("$.amr"))
        .contains("mfa");
    assertThat(ShpJwt.roles(decoder(true).decode(result.accessToken()))).containsExactly("nurse");
  }

  @Test
  void aPatientIsNotAskedForASecondFactor() throws Exception {
    KeycloakTestSupport.createUser(base, admin, "mfa.patient", "patient", PASSWORD, null);
    var grant = passwordGrant("mfa.patient", null);
    assertThat(grant.statusCode()).as(grant.body()).isEqualTo(200);
    Jwt viaGrant = decoder(true).decode(JsonPath.read(grant.body(), "$.access_token"));
    assertThat(ShpJwt.roles(viaGrant)).containsExactly("patient");

    KeycloakTestSupport.createUser(base, admin, "mfa.patient2", "patient", PASSWORD, null);
    var browser = new KeycloakBrowserLogin(issuer).signIn("mfa.patient2", PASSWORD, () -> "unused");
    assertThat(browser.pages()).containsExactly("username-password");
    assertThat(decoder(true).decode(browser.accessToken())).isNotNull();
  }

  @Test
  void aPatientWhoEnrolledOtpIsAskedForIt() throws Exception {
    KeycloakTestSupport.createUser(base, admin, "mfa.patient.otp", "patient", PASSWORD, SEED);
    assertThat(passwordGrant("mfa.patient.otp", null).statusCode()).isEqualTo(400);
    var ok = passwordGrant("mfa.patient.otp", code());
    assertThat(ok.statusCode()).as(ok.body()).isEqualTo(200);
  }
}
