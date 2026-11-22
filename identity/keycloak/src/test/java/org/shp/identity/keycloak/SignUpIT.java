package org.shp.identity.keycloak;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The sign-up flow on a real Keycloak 26.8.2 with the local realm (IAM-08, ADR-0017): phone number
 * or email, terms of use, an ownership code, then the account. Codes come from the test-only
 * provider in {@code shp-keycloak-test-otp.jar}, which plays notification N8/N9 (IAM-04).
 */
@Testcontainers(disabledWithoutDocker = true)
class SignUpIT {
  private static final String PASSPHRASE = "synthetic test phrase 0002";
  private static KeycloakEnvironment env;

  @BeforeAll
  static void start() throws Exception {
    env = new KeycloakEnvironment().start();
    env.allowRegistration();
  }

  @AfterAll
  static void stop() {
    if (env != null) {
      env.close();
    }
  }

  /** Opens the sign-up page from the login page, as a person does. */
  private static Browser signUpPage(String acceptLanguage) throws Exception {
    return new Browser(acceptLanguage).openLogin(env.issuer()).follow("login-actions/registration");
  }

  private static Browser submitIdentifier(String identifier) throws Exception {
    return signUpPage(null)
        .submit(
            "shp-register-identifier-form",
            Map.of("identifier", identifier, "termsAccepted", "on"));
  }

  private static String code(String destination) {
    String[] parts = env.lastCode(destination).split(" ");
    return parts[parts.length - 1];
  }

  @Test
  void signUpIsVietnameseByDefaultAndEnglishOnRequest() throws Exception {
    var vi = signUpPage(null);
    assertThat(vi.status()).isEqualTo(200);
    assertThat(vi.page())
        .contains("lang=\"vi\"")
        .contains("Đăng ký tài khoản")
        .contains("Số điện thoại hoặc email")
        .contains("Điều khoản sử dụng")
        .contains("Tôi đã đọc và đồng ý với Điều khoản sử dụng");
    var en = signUpPage("en");
    assertThat(en.page())
        .contains("lang=\"en\"")
        .contains("Create an account")
        .contains("I have read and accept the Terms of use");
  }

  @Test
  void termsAndAValidIdentifierAreRequiredAndNothingIsSentWithoutThem() throws Exception {
    var noTerms =
        signUpPage(null).submit("shp-register-identifier-form", Map.of("identifier", "0901234590"));
    assertThat(noTerms.page())
        .contains("Bạn cần đồng ý với Điều khoản sử dụng để tiếp tục.")
        .contains("shp-register-identifier-form");
    var badNumber =
        signUpPage(null)
            .submit(
                "shp-register-identifier-form",
                Map.of("identifier", "0123", "termsAccepted", "on"));
    assertThat(badNumber.page())
        .contains("Nhập số di động Việt Nam hoặc địa chỉ email hợp lệ.")
        .doesNotContain("shp-register-code-form");
  }

  @Test
  void phoneSignUpCreatesAVerifiedPatientWhoSignsInWithTheStoredNumber() throws Exception {
    var b = submitIdentifier("0901 234 501");
    assertThat(b.page()).contains("shp-register-code-form").contains("+84901234501");
    assertThat(env.lastCode("+84901234501")).startsWith("register phone vi ");

    b.submit("shp-register-code-form", Map.of("code", code("+84901234501")));
    assertThat(b.page()).contains("shp-register-password-form").contains("Đặt mật khẩu");
    b.submit(
        "shp-register-password-form",
        Map.of("password", PASSPHRASE, "password-confirm", PASSPHRASE));
    assertThat(b.location()).as("signed in after sign-up").startsWith(Browser.REDIRECT);

    String users = env.admin("GET", "/users?username=%2B84901234501&exact=true", null).body();
    assertThat(JsonPath.<List<String>>read(users, "$[*].username")).containsExactly("+84901234501");
    assertThat(JsonPath.<List<List<String>>>read(users, "$[0].attributes.phoneNumber"))
        .isEqualTo(List.of("+84901234501"));
    assertThat(JsonPath.<List<String>>read(users, "$[0].attributes.phoneNumberVerified"))
        .containsExactly("true");
    assertThat(JsonPath.<List<String>>read(users, "$[0].attributes.terms_and_conditions"))
        .hasSize(1);
    String id = JsonPath.read(users, "$[0].id");
    String roles = env.admin("GET", "/users/" + id + "/role-mappings/realm", null).body();
    assertThat(JsonPath.<List<String>>read(roles, "$[*].name")).contains("patient");

    var signIn = new Browser(null).openLogin(env.issuer());
    signIn.submit("kc-form-login", Map.of("username", "+84901234501", "password", PASSPHRASE));
    assertThat(signIn.location())
        .as("signed in with the stored +84 username")
        .startsWith(Browser.REDIRECT);
  }

  @Test
  void emailSignUpCreatesAPatientWithAVerifiedEmail() throws Exception {
    var b = submitIdentifier("New.Patient@Example.test");
    assertThat(env.lastCode("new.patient@example.test")).startsWith("register email vi ");
    b.submit("shp-register-code-form", Map.of("code", code("new.patient@example.test")));
    b.submit(
        "shp-register-password-form",
        Map.of("password", PASSPHRASE, "password-confirm", PASSPHRASE));
    assertThat(b.location()).startsWith(Browser.REDIRECT);
    String users =
        env.admin("GET", "/users?email=new.patient%40example.test&exact=true", null).body();
    assertThat(JsonPath.<List<Boolean>>read(users, "$[*].emailVerified")).containsExactly(true);
  }

  @Test
  void anExistingAccountIsNotRevealedBeforeTheCodeIsVerified() throws Exception {
    env.createUser("taken@example.test", "taken@example.test", PASSPHRASE);

    var taken = submitIdentifier("taken@example.test");
    var fresh = submitIdentifier("fresh@example.test");
    assertThat(taken.status()).isEqualTo(fresh.status()).isEqualTo(200);
    assertThat(visible(taken.page(), "taken@example.test"))
        .isEqualTo(visible(fresh.page(), "fresh@example.test"));

    taken.submit("shp-register-code-form", Map.of("code", "000000"));
    fresh.submit("shp-register-code-form", Map.of("code", "000000"));
    assertThat(taken.alert())
        .isEqualTo(fresh.alert())
        .isEqualTo("Mã xác minh không đúng hoặc đã hết hạn.");
    assertThat(visible(taken.page(), "taken@example.test"))
        .isEqualTo(visible(fresh.page(), "fresh@example.test"));

    // Only the owner, after proving ownership, learns that the account exists.
    taken.submit("shp-register-code-form", Map.of("code", code("taken@example.test")));
    assertThat(taken.page())
        .contains("Bạn đã có tài khoản")
        .contains("reset-credentials")
        .doesNotContain("shp-register-password-form");
    fresh.submit("shp-register-code-form", Map.of("code", code("fresh@example.test")));
    assertThat(fresh.page()).contains("shp-register-password-form");
  }

  @Test
  void wrongCodesLockAndALimitIsShown() throws Exception {
    var b = submitIdentifier("0901234502");
    for (int i = 0; i < 4; i++) {
      b.submit("shp-register-code-form", Map.of("code", "00000" + i));
      assertThat(b.alert()).isEqualTo("Mã xác minh không đúng hoặc đã hết hạn.");
    }
    b.submit("shp-register-code-form", Map.of("code", "000009"));
    assertThat(b.alert())
        .isEqualTo("Bạn đã nhập sai mã quá nhiều lần. Vui lòng thử lại sau 15 phút.");

    var limited = submitIdentifier("rate-limited@example.test");
    assertThat(limited.alert()).isEqualTo("Bạn đã yêu cầu mã quá nhiều lần. Vui lòng thử lại sau.");
  }

  @Test
  void anotherIdentifierStartsOverAndAWeakPasswordIsRefused() throws Exception {
    var b = submitIdentifier("0901234503");
    b.submit("shp-register-code-other", Map.of("change", ""));
    assertThat(b.page())
        .contains("shp-register-identifier-form")
        .doesNotContain("shp-register-code-form");

    b.submit(
        "shp-register-identifier-form", Map.of("identifier", "0901234504", "termsAccepted", "on"));
    b.submit("shp-register-code-form", Map.of("code", code("+84901234504")));
    b.submit(
        "shp-register-password-form",
        Map.of("password", "short-pass", "password-confirm", "short-pass"));
    assertThat(b.page()).contains("shp-register-password-form").contains("12");
    assertThat(b.location()).isNull();
  }

  /** The text a person sees on a page, with the identifier they typed taken out. */
  private static String visible(String page, String identifier) {
    String body = page.replaceAll("(?s).*<body", "<body");
    String text =
        Pattern.compile("(?s)<script.*?</script>|<[^>]+>")
            .matcher(body)
            .replaceAll(" ")
            .replace(identifier, "IDENTIFIER");
    return text.replaceAll("\\s+", " ").strip();
  }
}
