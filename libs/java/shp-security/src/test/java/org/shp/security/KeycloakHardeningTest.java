package org.shp.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * IAM-06 against a real Keycloak 26.8.2 with the realm built from identity/realm: password policy
 * (length, username, breached list), Argon2id hashing and temporary lockout after five failures
 * (ADR-0017).
 */
@Testcontainers
class KeycloakHardeningTest {
  static final String STRONG = "correct-horse-battery-staple";

  @Container
  static final GenericContainer<?> KEYCLOAK =
      KeycloakTestSupport.container("dev/chart/shp-local-deps/files/realm-shp.json");

  static String base;
  static String admin;

  @BeforeAll
  static void login() throws Exception {
    base = KeycloakTestSupport.base(KEYCLOAK);
    admin = KeycloakTestSupport.adminToken(base);
  }

  static HttpResponse<String> createUser(String username, String password) throws Exception {
    String body =
        """
        {"username":"%s","enabled":true,"emailVerified":true,"email":"%s@example.test",
         "firstName":"Test","lastName":"User",
         "credentials":[{"type":"password","value":"%s","temporary":false}]}
        """
            .formatted(username, username, password);
    return KeycloakTestSupport.admin("POST", base + "/admin/realms/shp/users", admin, body);
  }

  static String idOf(HttpResponse<String> created) {
    return created.headers().firstValue("Location").orElseThrow().replaceAll(".*/", "");
  }

  static HttpResponse<String> passwordGrant(String username, String password) throws Exception {
    return KeycloakTestSupport.formResponse(
        base + "/realms/shp/protocol/openid-connect/token",
        "grant_type=password&client_id=shp-test&username="
            + username
            + "&password="
            + URLEncoder.encode(password, StandardCharsets.UTF_8));
  }

  @Test
  void passwordPolicyRejectsShortUsernameAndBreachedPasswords() throws Exception {
    // one policy for patients and staff (IAM-10): staff are protected by MFA, not a longer password
    var tooShort = createUser("pol.short", "Abcdefghij1"); // 11 characters
    assertThat(tooShort.statusCode()).isEqualTo(400);
    assertThat(tooShort.body()).contains("Invalid password");
    var containsUsername =
        createUser("pol.username.long", "pol.username.long"); // equal to the username
    assertThat(containsUsername.statusCode()).isEqualTo(400);
    var breached =
        createUser("pol.breached", "Password123456"); // case-insensitive match in the list
    assertThat(breached.statusCode()).isEqualTo(400);
    // Length 12 without digits or symbols is fine (NIST SP 800-63B: no composition rules).
    assertThat(createUser("pol.ok", STRONG).statusCode()).isEqualTo(201);
  }

  @Test
  void passwordsAreStoredWithArgon2id() throws Exception {
    String id = idOf(createUser("hash.user", STRONG));
    String credentials =
        KeycloakTestSupport.adminGet(
            base + "/admin/realms/shp/users/" + id + "/credentials", admin);
    String data = JsonPath.read(credentials, "$[0].credentialData");
    assertThat(JsonPath.<String>read(data, "$.algorithm")).isEqualTo("argon2");
    assertThat(JsonPath.<java.util.List<String>>read(data, "$.additionalParameters.type"))
        .containsExactly("id");
    assertThat(JsonPath.<java.util.List<String>>read(data, "$.additionalParameters.version"))
        .containsExactly("1.3");
  }

  @Test
  void fiveWrongPasswordsLockTheAccountTemporarily() throws Exception {
    String id = idOf(createUser("lock.user", STRONG));
    assertThat(createUser("lock.other", STRONG).statusCode()).isEqualTo(201);
    assertThat(passwordGrant("lock.user", STRONG).statusCode()).isEqualTo(200);
    for (int i = 0; i < 5; i++) {
      assertThat(passwordGrant("lock.user", "wrong-password-" + i).statusCode()).isIn(400, 401);
    }
    // The correct password is now refused as well: the account is locked, not just the wrong
    // guesses.
    var locked = passwordGrant("lock.user", STRONG);
    assertThat(locked.statusCode()).isIn(400, 401);
    assertThat(locked.body()).doesNotContain("access_token");
    var status =
        KeycloakTestSupport.adminGet(
            base + "/admin/realms/shp/attack-detection/brute-force/users/" + id, admin);
    assertThat(JsonPath.<Boolean>read(status, "$.disabled")).isTrue();
    // Temporary, not permanent: the user itself stays enabled and an unrelated account is
    // unaffected.
    assertThat(
            JsonPath.<Boolean>read(
                KeycloakTestSupport.adminGet(base + "/admin/realms/shp/users/" + id, admin),
                "$.enabled"))
        .isTrue();
    assertThat(passwordGrant("lock.other", STRONG).statusCode()).isEqualTo(200);
  }

  @Test
  void realmKeepsTheAuditEventsAndNoPermanentLockout() throws Exception {
    String realm = KeycloakTestSupport.adminGet(base + "/admin/realms/shp", admin);
    assertThat(JsonPath.<Boolean>read(realm, "$.bruteForceProtected")).isTrue();
    assertThat(JsonPath.<Boolean>read(realm, "$.permanentLockout")).isFalse();
    assertThat(JsonPath.<Integer>read(realm, "$.failureFactor")).isEqualTo(5);
    assertThat(JsonPath.<Boolean>read(realm, "$.eventsEnabled")).isTrue();
    assertThat(JsonPath.<Boolean>read(realm, "$.adminEventsEnabled")).isTrue();
    assertThat(JsonPath.<Boolean>read(realm, "$.adminEventsDetailsEnabled"))
        .isFalse(); // no password in admin events
    assertThat(JsonPath.<String>read(realm, "$.sslRequired"))
        .isEqualTo("none"); // local overlay only
  }
}
