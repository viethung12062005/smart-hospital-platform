package org.shp.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.shp.web.auth.HttpIdentityLinkClient;
import org.shp.web.auth.IdentityLink;
import org.shp.web.auth.IdentityResolver;
import org.shp.web.auth.ShpAuthProperties;
import org.shp.web.auth.ShpJwt;
import org.shp.web.errors.ErrorCatalog;
import org.shp.web.errors.ErrorInfo;
import org.shp.web.errors.ShpException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class UnitTest {
  static final class MutableClock extends Clock {
    Instant now = Instant.parse("2026-11-20T08:00:00Z");

    @Override
    public ZoneId getZone() {
      return ZoneId.of("UTC");
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }

  @Test
  void linkIsCachedForSixtySecondsThenRefreshed() {
    var links = new TestApp.FakeLinks();
    links.links.put("s", new IdentityLink("Patient", "p1", false));
    var clock = new MutableClock();
    var resolver = new IdentityResolver(links, clock);
    resolver.resolve("s");
    clock.now = clock.now.plusSeconds(59);
    resolver.resolve("s");
    assertThat(links.calls).isEqualTo(1);
    links.links.put("s", new IdentityLink("Patient", "p1", true)); // admin khóa tài khoản
    clock.now = clock.now.plusSeconds(2);
    assertThat(resolver.resolve("s")).hasValueSatisfying(l -> assertThat(l.disabled()).isTrue());
    assertThat(links.calls).isEqualTo(2);
  }

  @Test
  void missingLinkIsCachedOnlyBriefly() {
    var links = new TestApp.FakeLinks();
    var clock = new MutableClock();
    var resolver = new IdentityResolver(links, clock);
    assertThat(resolver.resolve("s")).isEmpty();
    links.links.put("s", new IdentityLink("Patient", "p1", false)); // vừa đăng ký xong
    clock.now = clock.now.plusSeconds(6);
    assertThat(resolver.resolve("s")).isPresent();
  }

  /** Mỗi kịch bản một server giả (MockRestServiceServer không cho thêm kỳ vọng sau khi đã gọi). */
  private static HttpIdentityLinkClient clientReturning(
      String path, HttpStatus status, String body) {
    var builder = RestClient.builder().baseUrl("http://clinical");
    var server = MockRestServiceServer.bindTo(builder).build();
    var response =
        body == null
            ? withStatus(status)
            : withStatus(status).contentType(MediaType.APPLICATION_JSON).body(body);
    server.expect(requestTo("http://clinical/internal/v1/identity/" + path)).andRespond(response);
    return new HttpIdentityLinkClient(builder.build());
  }

  @Test
  void httpClientMapsResponses() {
    assertThat(
            clientReturning(
                    "kc%7Cuser",
                    HttpStatus.OK,
                    "{\"fhirUser\":\"Practitioner/abc\",\"roles\":[\"doctor\"],\"disabled\":false}")
                .find("kc|user"))
        .hasValue(new IdentityLink("Practitioner", "abc", false));
    assertThat(clientReturning("x", HttpStatus.NOT_FOUND, null).find("x")).isEmpty();
    assertThatThrownBy(
            () ->
                clientReturning(
                        "x", HttpStatus.OK, "{\"fhirUser\":\"Device/1\",\"disabled\":false}")
                    .find("x"))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> clientReturning("x", HttpStatus.SERVICE_UNAVAILABLE, null).find("x"))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void mfaCanBeDisabledForTheLocalRealm() {
    var jwt =
        Jwt.withTokenValue("t")
            .header("alg", "none")
            .issuer(TestApp.ISSUER)
            .audience(List.of("shp-api"))
            .subject("doc")
            .claim("roles", List.of("doctor"))
            .issuedAt(Instant.now().minusSeconds(10))
            .expiresAt(Instant.now().plusSeconds(600))
            .build();
    var strict =
        ShpJwt.validator(
            new ShpAuthProperties(TestApp.ISSUER, "shp-api", true, Duration.ofSeconds(30)),
            Clock.systemUTC());
    var local =
        ShpJwt.validator(
            new ShpAuthProperties(TestApp.ISSUER, "shp-api", false, Duration.ofSeconds(30)),
            Clock.systemUTC());
    assertThat(strict.validate(jwt).hasErrors()).isTrue();
    assertThat(local.validate(jwt).hasErrors()).isFalse();
  }

  @Test
  void unknownCodeCannotBeThrown() {
    assertThatThrownBy(() -> new ShpException("made-up-code"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void catalogMatchesTheErrorTableInTheDesignDocument() throws Exception {
    Path dir = Path.of("").toAbsolutePath();
    while (dir != null
        && !Files.exists(
            dir.resolve("docs/03-architecture/detailed-design/07-api-conventions.md"))) {
      dir = dir.getParent();
    }
    assertThat(dir).isNotNull();
    String text =
        Files.readString(dir.resolve("docs/03-architecture/detailed-design/07-api-conventions.md"));
    String section = text.substring(text.indexOf("## 5. Lỗi"), text.indexOf("## 6."));
    List<Map.Entry<String, Integer>> documented = new ArrayList<>();
    var row = Pattern.compile("(?m)^\\| (\\d{3}) \\|[^|]*\\|(.*)\\|\\s*$").matcher(section);
    while (row.find()) {
      var code = Pattern.compile("`([a-z-]+)`").matcher(row.group(2));
      while (code.find()) {
        documented.add(Map.entry(code.group(1), Integer.parseInt(row.group(1))));
      }
    }
    assertThat(documented).isNotEmpty();
    documented.forEach(
        d ->
            assertThat(ErrorCatalog.get(d.getKey()).status())
                .as(d.getKey())
                .isEqualTo(d.getValue()));
    assertThat(
            ErrorCatalog.all().stream()
                .map(ErrorInfo::code)
                .filter(c -> !c.equals("internal-error"))
                .sorted())
        .containsExactlyElementsOf(documented.stream().map(Map.Entry::getKey).sorted().toList());
  }
}
