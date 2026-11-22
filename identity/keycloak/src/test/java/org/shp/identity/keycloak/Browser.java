package org.shp.identity.keycloak;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Drives Keycloak's login pages the way a browser does: follows redirects, keeps cookies by hand
 * (Keycloak marks them {@code Secure} even on the plain-HTTP test port, which {@code CookieManager}
 * would then withhold) and posts the forms the pages serve.
 */
final class Browser {
  static final String REDIRECT = "http://localhost:5173/cb";

  private final HttpClient http =
      HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
  private final Map<String, String> cookies = new LinkedHashMap<>();
  private final String acceptLanguage;
  private int status;
  private String page = "";
  private String location;
  private URI current;

  /** {@code acceptLanguage} null sends no Accept-Language header (the realm default applies). */
  Browser(String acceptLanguage) {
    this.acceptLanguage = acceptLanguage;
  }

  /**
   * Opens the authorization endpoint of {@code shp-web} (code flow with PKCE) and its login page.
   */
  Browser openLogin(String issuer) throws Exception {
    return open(
        issuer
            + "/protocol/openid-connect/auth?client_id=shp-web&response_type=code&scope=openid"
            + "&redirect_uri="
            + URLEncoder.encode(REDIRECT, StandardCharsets.UTF_8)
            + "&code_challenge=E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGjSstw-cM&code_challenge_method=S256");
  }

  Browser open(String url) throws Exception {
    send(HttpRequest.newBuilder(resolve(url)).GET());
    return this;
  }

  /** Follows the first link whose address contains {@code part}. */
  Browser follow(String part) throws Exception {
    var m =
        Pattern.compile("<a[^>]*href=\"([^\"]*" + Pattern.quote(part) + "[^\"]*)\"").matcher(page);
    assertThat(m.find()).as("link to " + part + " in " + page).isTrue();
    return open(unescape(m.group(1)));
  }

  /** Posts the fields to the action of the form with the given id. */
  Browser submit(String formId, Map<String, String> fields) throws Exception {
    var m =
        Pattern.compile("<form[^>]*id=\"" + Pattern.quote(formId) + "\"[^>]*action=\"([^\"]+)\"")
            .matcher(page);
    assertThat(m.find()).as("form " + formId + " in " + page).isTrue();
    String body =
        fields.entrySet().stream()
            .map(e -> e.getKey() + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
            .reduce((a, b) -> a + "&" + b)
            .orElse("");
    send(
        HttpRequest.newBuilder(resolve(unescape(m.group(1))))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(body)));
    return this;
  }

  int status() {
    return status;
  }

  String page() {
    return page;
  }

  /** Where the last flow ended when it left Keycloak (the client redirect), else null. */
  String location() {
    return location;
  }

  /** The text of the page's alert (error or info message), without markup. */
  String alert() {
    var m =
        Pattern.compile("(?s)class=\"[^\"]*kc-feedback-text[^\"]*\"[^>]*>(.*?)</span>")
            .matcher(page);
    if (!m.find()) {
      m = Pattern.compile("(?s)class=\"pf-v5-c-alert__title[^\"]*\"[^>]*>(.*?)</").matcher(page);
      if (!m.find()) {
        return null;
      }
    }
    return unescape(m.group(1).replaceAll("<[^>]+>", "").trim());
  }

  private void send(HttpRequest.Builder b) throws Exception {
    for (int hop = 0; hop < 10; hop++) {
      if (acceptLanguage != null) {
        b.header("Accept-Language", acceptLanguage);
      }
      if (!cookies.isEmpty()) {
        b.header(
            "Cookie",
            cookies.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .reduce((x, y) -> x + "; " + y)
                .orElseThrow());
      }
      HttpResponse<String> res = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
      current = res.request().uri();
      res.headers()
          .allValues("Set-Cookie")
          .forEach(c -> {
            String kv = c.split(";", 2)[0];
            int i = kv.indexOf('=');
            cookies.put(kv.substring(0, i), kv.substring(i + 1));
          });
      status = res.statusCode();
      page = res.body();
      location = res.headers().firstValue("Location").orElse(null);
      if (location == null || location.startsWith(REDIRECT)) {
        return;
      }
      b =
          HttpRequest.newBuilder(URI.create(res.request().uri().resolve(location).toString()))
              .GET();
    }
    throw new IllegalStateException("too many redirects");
  }

  /** Pages link with absolute paths (no host); resolve them against the page they are on. */
  private URI resolve(String url) {
    return current == null ? URI.create(url) : current.resolve(url);
  }

  static String unescape(String s) {
    return s.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'");
  }
}
