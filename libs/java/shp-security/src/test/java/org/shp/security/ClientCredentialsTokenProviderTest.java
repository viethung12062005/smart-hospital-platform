package org.shp.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

/** SHR-32: caching, early refresh, failure handling and secrecy of the service token provider. */
class ClientCredentialsTokenProviderTest {
  static final String SECRET = "test-only-secret-value";

  /** A clock the test moves. */
  static final class MovableClock extends Clock {
    Instant now = Instant.parse("2026-11-18T08:00:00Z");

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

  HttpServer server;
  final AtomicInteger calls = new AtomicInteger();
  final List<String> authHeaders = new CopyOnWriteArrayList<>();
  final List<String> bodies = new CopyOnWriteArrayList<>();
  volatile int status = 200;
  volatile long expiresIn = 300;
  volatile String responseBody = null;
  MovableClock clock = new MovableClock();
  ListAppender<ILoggingEvent> logs = new ListAppender<>();

  @BeforeEach
  void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/token",
        ex -> {
          int n = calls.incrementAndGet();
          authHeaders.add(ex.getRequestHeaders().getFirst("Authorization"));
          bodies.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          String body =
              responseBody != null
                  ? responseBody
                  : "{\"access_token\":\"token-" + n + "\",\"expires_in\":" + expiresIn + "}";
          byte[] out = body.getBytes(StandardCharsets.UTF_8);
          ex.getResponseHeaders().add("Content-Type", "application/json");
          ex.sendResponseHeaders(status, out.length);
          ex.getResponseBody().write(out);
          ex.close();
        });
    server.start();
    logs.start();
    ((Logger) LoggerFactory.getLogger(ClientCredentialsTokenProvider.class)).addAppender(logs);
  }

  @AfterEach
  void stop() {
    server.stop(0);
    ((Logger) LoggerFactory.getLogger(ClientCredentialsTokenProvider.class)).detachAppender(logs);
  }

  String endpoint() {
    return "http://127.0.0.1:" + server.getAddress().getPort() + "/token";
  }

  ClientCredentialsTokenProvider provider(String secret, String assertionFile) {
    return new ClientCredentialsTokenProvider(
        new ServiceTokenProperties(endpoint(), "svc-scheduling", secret, assertionFile, null, null),
        clock);
  }

  @Test
  void cacheAndInvalidationAreBoundToTheTarget() {
    var p = provider(SECRET, "");
    assertThat(p.token("clinical")).isEqualTo("token-1");
    assertThat(p.token("ml")).isEqualTo("token-2");
    p.invalidate("ml");
    assertThat(p.token("clinical")).isEqualTo("token-1");
    assertThat(p.token("ml")).isEqualTo("token-3");
    assertThat(bodies).allMatch(b -> b.contains("scope=call-"));
  }

  @Test
  void sendsClientCredentialsWithBasicAuthAndCachesTheToken() {
    var p = provider(SECRET, "");
    assertThat(p.token("clinical")).isEqualTo("token-1");
    assertThat(p.token("clinical")).isEqualTo("token-1");
    assertThat(calls).hasValue(1);
    assertThat(bodies.get(0)).isEqualTo("grant_type=client_credentials&scope=call-clinical");
    String basic =
        new String(
            Base64.getDecoder().decode(authHeaders.get(0).substring("Basic ".length())),
            StandardCharsets.UTF_8);
    assertThat(basic).isEqualTo("svc-scheduling:" + SECRET);
  }

  @Test
  void refreshesBeforeTheTokenExpires() {
    var p = provider(SECRET, "");
    assertThat(p.token("clinical")).isEqualTo("token-1");
    // lifetime 300 s, refresh 30 s early: still cached at +269 s, replaced at +271 s
    clock.now = clock.now.plusSeconds(269);
    assertThat(p.token("clinical")).isEqualTo("token-1");
    clock.now = clock.now.plusSeconds(2);
    assertThat(p.token("clinical")).isEqualTo("token-2");
    assertThat(calls).hasValue(2);
  }

  @Test
  void veryShortLifetimeIsRefreshedAtHalfItsLifeNotOnEveryCall() {
    expiresIn = 10;
    var p = provider(SECRET, "");
    p.token("clinical");
    clock.now = clock.now.plusSeconds(4);
    p.token("clinical");
    assertThat(calls).hasValue(1);
    clock.now = clock.now.plusSeconds(2);
    p.token("clinical");
    assertThat(calls).hasValue(2);
  }

  @Test
  void invalidateForcesANewToken() {
    var p = provider(SECRET, "");
    p.token("clinical");
    p.invalidate("clinical");
    assertThat(p.token("clinical")).isEqualTo("token-2");
  }

  @Test
  void keepsUsingTheCachedTokenWhenARefreshFailsBeforeRealExpiry() {
    var p = provider(SECRET, "");
    p.token("clinical");
    status = 503;
    clock.now = clock.now.plusSeconds(280);
    assertThat(p.token("clinical")).isEqualTo("token-1");
    clock.now = clock.now.plusSeconds(21);
    assertThatThrownBy(() -> p.token("clinical")).isInstanceOf(ServiceTokenException.class);
  }

  @Test
  void failureNeverLeaksTheSecretTheBodyOrAToken() {
    status = 401;
    responseBody = "{\"error\":\"unauthorized_client\",\"echo\":\"" + SECRET + "\"}";
    var p = provider(SECRET, "");
    assertThatThrownBy(() -> p.token("clinical"))
        .isInstanceOf(ServiceTokenException.class)
        .hasMessageContaining("401")
        .hasMessageNotContaining(SECRET);
    status = 200;
    responseBody = null;
    String token = p.token("clinical");
    p.invalidate("clinical");
    p.token("clinical");
    assertThat(logs.list)
        .allSatisfy(e -> {
          assertThat(e.getFormattedMessage()).doesNotContain(SECRET).doesNotContain(token);
        });
    var props = new ServiceTokenProperties(endpoint(), "svc-x", SECRET, "", null, null);
    assertThat(props.toString()).doesNotContain(SECRET);
  }

  @Test
  void rejectsAnAnswerWithoutAnAccessToken() {
    responseBody = "{\"expires_in\":300}";
    assertThatThrownBy(() -> provider(SECRET, "").token("clinical"))
        .isInstanceOf(ServiceTokenException.class);
    responseBody = "not json";
    assertThatThrownBy(() -> provider(SECRET, "").token("clinical"))
        .isInstanceOf(ServiceTokenException.class);
  }

  @Test
  void withoutCredentialsTheCallFailsClearlyAndSendsNothing() {
    var p = provider("", "");
    assertThat(p.hasCredentials()).isFalse();
    assertThatThrownBy(() -> p.token("clinical"))
        .isInstanceOf(ServiceTokenException.class)
        .hasMessageContaining("svc-scheduling");
    assertThat(calls).hasValue(0);
  }

  @Test
  void unreachableEndpointIsAServiceTokenException() {
    server.stop(0);
    assertThatThrownBy(() -> provider(SECRET, "").token("clinical"))
        .isInstanceOf(ServiceTokenException.class);
  }

  @Test
  void projectedTokenIsSentAsClientAssertionAndReReadOnEveryRequest(@TempDir Path dir)
      throws Exception {
    Path file = dir.resolve("token");
    Files.writeString(file, "assertion-one\n");
    var p = provider("", file.toString());
    p.token("clinical");
    Files.writeString(file, "assertion-two");
    p.invalidate("clinical");
    p.token("clinical");
    assertThat(authHeaders).containsOnlyNulls();
    assertThat(bodies.get(0))
        .contains("grant_type=client_credentials", "client_id=svc-scheduling")
        .contains(
            "client_assertion_type=urn%3Aietf%3Aparams%3Aoauth%3Aclient-assertion-type%3Ajwt-bearer")
        .contains("client_assertion=assertion-one")
        .doesNotContain("client_secret");
    assertThat(bodies.get(1)).contains("client_assertion=assertion-two");
  }

  @Test
  void assertionTakesPrecedenceOverTheSecret(@TempDir Path dir) throws Exception {
    Path file = dir.resolve("token");
    Files.writeString(file, "assertion-one");
    provider(SECRET, file.toString()).token("clinical");
    assertThat(authHeaders).containsOnlyNulls();
  }

  @Test
  void missingAssertionFileIsAServiceTokenException(@TempDir Path dir) {
    assertThatThrownBy(() -> provider("", dir.resolve("absent").toString()).token("clinical"))
        .isInstanceOf(ServiceTokenException.class);
  }

  @Test
  void requiresEndpointAndClientId() {
    assertThatThrownBy(() ->
            new ClientCredentialsTokenProvider(
                new ServiceTokenProperties("", "svc-x", SECRET, "", Duration.ZERO, null), clock))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() ->
            new ClientCredentialsTokenProvider(
                new ServiceTokenProperties(endpoint(), "", SECRET, "", null, null), clock))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
