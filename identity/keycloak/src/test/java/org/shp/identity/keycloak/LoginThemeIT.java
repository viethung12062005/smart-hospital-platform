package org.shp.identity.keycloak;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The SHP login theme on a real Keycloak 26.8.2 with the local realm (IAM-03, ADR-0017): Vietnamese
 * by default and English on request, the SHP stylesheet, and sign-in and forgot-password answers
 * that are the same whether or not an account exists.
 */
@Testcontainers(disabledWithoutDocker = true)
class LoginThemeIT {
  // Synthetic credential of a fake test user (no real account behind it): gitleaks flags the
  // "pass-<digits>" shape, so the finding is silenced on this line only.
  private static final String PASSWORD = "synthetic-pass-0001"; // gitleaks:allow
  private static final String INVALID_VI = "Tên đăng nhập hoặc mật khẩu không đúng.";
  private static final String SENT_VI =
      "Nếu thông tin khớp với một tài khoản, bạn sẽ sớm nhận được email hướng dẫn đặt mật khẩu mới.";
  private static KeycloakEnvironment env;

  @BeforeAll
  static void start() {
    env = new KeycloakEnvironment().start();
  }

  @AfterAll
  static void stop() {
    if (env != null) {
      env.close();
    }
  }

  @Test
  void loginPageIsVietnameseByDefaultAndOffersEnglish() throws Exception {
    var b = new Browser(null).openLogin(env.issuer());
    assertThat(b.status()).isEqualTo(200);
    assertThat(b.page())
        .contains("lang=\"vi\"")
        .contains("Bệnh viện Thông minh")
        .contains("Đăng nhập")
        .contains("Tên đăng nhập hoặc email")
        .contains("Quên mật khẩu?")
        .contains("/login/shp/css/shp.css")
        .contains("English");
  }

  @Test
  void loginPageIsEnglishWhenTheBrowserAsksForIt() throws Exception {
    var b = new Browser("en-US,en;q=0.9").openLogin(env.issuer());
    assertThat(b.page())
        .contains("lang=\"en\"")
        .contains("Smart Hospital")
        .contains("Forgot password?")
        .contains("Tiếng Việt");
  }

  @Test
  void theSiteStylesheetIsServedWithTheDesignTokens() throws Exception {
    var b = new Browser(null).openLogin(env.issuer());
    var m = Pattern.compile("href=\"([^\"]*/login/shp/css/shp\\.css)\"").matcher(b.page());
    assertThat(m.find()).isTrue();
    var css =
        KeycloakEnvironment.HTTP.send(
            HttpRequest.newBuilder(URI.create(env.base()).resolve(m.group(1))).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(css.statusCode()).isEqualTo(200);
    assertThat(css.body())
        .contains("--shp-teal: #0f6d62")
        .contains("--pf-v5-global--primary-color--100");
  }

  @Test
  void unknownAccountWrongPasswordAndLockedAccountGetTheSameAnswer() throws Exception {
    String id = env.createUser("theme.lock", "theme.lock@example.test", PASSWORD);

    String unknown = signIn("nobody.here@example.test", PASSWORD);
    String wrong = signIn("theme.lock", "not-the-password-1");
    assertThat(unknown).isEqualTo(wrong).isEqualTo(INVALID_VI);

    for (int i = 0; i < 5; i++) {
      signIn("theme.lock", "not-the-password-" + i);
    }
    var status = env.admin("GET", "/attack-detection/brute-force/users/" + id, null);
    assertThat((Boolean) JsonPath.read(status.body(), "$.disabled")).as("account locked").isTrue();
    var locked = new Browser(null).openLogin(env.issuer());
    locked.submit("kc-form-login", Map.of("username", "theme.lock", "password", PASSWORD));
    assertThat(locked.location()).as("no code issued while locked").isNull();
    assertThat(locked.alert()).isEqualTo(INVALID_VI);
  }

  @Test
  void forgotPasswordAnswersTheSameAndMailsOnlyAnExistingAccount() throws Exception {
    env.createUser("theme.reset", "theme.reset@example.test", PASSWORD);

    var existing = new Browser(null).openLogin(env.issuer()).follow("reset-credentials");
    assertThat(existing.page())
        .contains("lang=\"vi\"")
        .contains("Quên mật khẩu")
        .contains("Nếu thông tin khớp với một tài khoản");
    existing.submit("kc-reset-password-form", Map.of("username", "theme.reset@example.test"));

    var missing = new Browser(null).openLogin(env.issuer()).follow("reset-credentials");
    missing.submit("kc-reset-password-form", Map.of("username", "no.account@example.test"));

    assertThat(existing.status()).isEqualTo(missing.status()).isEqualTo(200);
    assertThat(existing.alert()).isEqualTo(missing.alert()).isEqualTo(SENT_VI);

    String mails = waitForMails(1);
    List<String> to = JsonPath.read(mails, "$.messages[*].To[*].Address");
    List<String> subjects = JsonPath.read(mails, "$.messages[*].Subject");
    assertThat(to).containsExactly("theme.reset@example.test");
    assertThat(subjects).containsExactly("Đặt lại mật khẩu");
  }

  private static String signIn(String username, String password) throws Exception {
    var b = new Browser(null).openLogin(env.issuer());
    b.submit("kc-form-login", Map.of("username", username, "password", password));
    assertThat(b.location()).as("no code issued").isNull();
    return b.alert();
  }

  private static String waitForMails(int count) throws Exception {
    String body = env.mails();
    for (int i = 0; i < 50 && (Integer) JsonPath.read(body, "$.total") < count; i++) {
      Thread.sleep(200);
      body = env.mails();
    }
    // a short grace period: a second (unexpected) mail would show up here
    Thread.sleep(500);
    return env.mails();
  }
}
