package org.shp.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Drives the real Keycloak login pages of the {@code shp-web} client (authorization code with PKCE)
 * the way a browser does, so a test sees which page the realm's browser flow serves. Cookies are
 * kept by hand because Keycloak marks them {@code Secure} even on the plain-HTTP test port.
 */
final class KeycloakBrowserLogin {
  static final String REDIRECT = "http://localhost:5173/cb";
  private static final Pattern FORM_ACTION = Pattern.compile("<form[^>]*action=\"([^\"]+)\"");

  /** The pages the flow served, in order, and the access token when the flow finished. */
  record Result(List<String> pages, String accessToken) {}

  private final HttpClient http =
      HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
  private final Map<String, String> cookies = new LinkedHashMap<>();
  private final String issuer;
  private final String clientId;
  private final String redirect;
  private final List<String> pages = new ArrayList<>();
  private String lastBody = "";
  private String lastLocation;

  KeycloakBrowserLogin(String issuer) {
    this(issuer, "shp-web", REDIRECT);
  }

  /** A browser login through another public client (the prod realm has no `shp-test`). */
  KeycloakBrowserLogin(String issuer, String clientId, String redirect) {
    this.issuer = issuer;
    this.clientId = clientId;
    this.redirect = redirect;
  }

  private void send(HttpRequest.Builder b) throws Exception {
    b.header(
        "Cookie",
        cookies.entrySet().stream()
            .map(e -> e.getKey() + "=" + e.getValue())
            .reduce((x, y) -> x + "; " + y)
            .orElse(""));
    HttpResponse<String> res = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    res.headers()
        .allValues("Set-Cookie")
        .forEach(c -> {
          String kv = c.split(";", 2)[0];
          int i = kv.indexOf('=');
          cookies.put(kv.substring(0, i), kv.substring(i + 1));
        });
    lastBody = res.body();
    lastLocation = res.headers().firstValue("Location").orElse(null);
  }

  private static String unescape(String s) {
    return s.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'");
  }

  private static String form(Map<String, String> f) {
    return f.entrySet().stream()
        .map(e -> e.getKey() + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
        .reduce((x, y) -> x + "&" + y)
        .orElse("");
  }

  /**
   * Signs in; {@code otp} supplies the code for an OTP form, and a "Configure OTP" page is
   * completed with a code computed from the secret the page itself shows (first login of a user who
   * has no second factor).
   */
  Result signIn(String username, String password, Supplier<String> otp) throws Exception {
    byte[] raw = new byte[32];
    new SecureRandom().nextBytes(raw);
    String verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    String challenge =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(
                MessageDigest.getInstance("SHA-256")
                    .digest(verifier.getBytes(StandardCharsets.US_ASCII)));
    send(
        HttpRequest.newBuilder(
                URI.create(
                    issuer
                        + "/protocol/openid-connect/auth?client_id="
                        + clientId
                        + "&response_type=code&scope=openid"
                        + "&redirect_uri="
                        + URLEncoder.encode(redirect, StandardCharsets.UTF_8)
                        + "&code_challenge="
                        + challenge
                        + "&code_challenge_method=S256"))
            .GET());
    for (int step = 0; step < 8; step++) {
      if (lastLocation != null && lastLocation.startsWith(redirect)) {
        String code =
            URLDecoder.decode(
                lastLocation.replaceAll(".*[?&]code=([^&]+).*", "$1"), StandardCharsets.UTF_8);
        String body =
            KeycloakTestSupport.form(
                issuer + "/protocol/openid-connect/token",
                form(
                    Map.of(
                        "grant_type", "authorization_code",
                        "client_id", clientId,
                        "code", code,
                        "redirect_uri", redirect,
                        "code_verifier", verifier)));
        return new Result(pages, JsonPath.read(body, "$.access_token"));
      }
      if (lastLocation != null) {
        send(HttpRequest.newBuilder(URI.create(lastLocation)).GET());
        continue;
      }
      var m = FORM_ACTION.matcher(lastBody);
      assertThat(m.find()).as("a login page with a form: " + lastBody).isTrue();
      URI action = URI.create(unescape(m.group(1)));
      Map<String, String> fields = new LinkedHashMap<>();
      if (lastBody.contains("name=\"username\"")) {
        pages.add("username-password");
        fields.put("username", username);
        fields.put("password", password);
      } else if (lastBody.contains("name=\"totpSecret\"")) {
        pages.add("configure-otp");
        String seed =
            unescape(
                lastBody.replaceAll("(?s).*name=\"totpSecret\"[^>]*value=\"([^\"]+)\".*", "$1"));
        fields.put("totp", KeycloakTestSupport.totp(seed));
        fields.put("totpSecret", seed);
        fields.put("userLabel", "test");
      } else if (lastBody.contains("name=\"otp\"")) {
        pages.add("otp");
        fields.put("otp", otp.get());
      } else {
        pages.add("other");
        return new Result(pages, null);
      }
      send(
          HttpRequest.newBuilder(action)
              .header("Content-Type", "application/x-www-form-urlencoded")
              .POST(HttpRequest.BodyPublishers.ofString(form(fields))));
    }
    return new Result(pages, null);
  }
}
