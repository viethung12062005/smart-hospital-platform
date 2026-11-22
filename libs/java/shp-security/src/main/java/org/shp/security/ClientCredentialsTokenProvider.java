package org.shp.security;

import com.nimbusds.jose.util.JSONObjectUtils;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * OAuth2 client-credentials token for calling another service's {@code /internal/*} API (15 §7,
 * IAM-02). The client proves itself with the projected ServiceAccount token (client assertion,
 * preferred) or with its client secret (HTTP Basic). The token is cached and refreshed {@code
 * refreshBefore} ahead of its expiry; if the refresh fails while the cached token is still valid,
 * the cached token is used until it really expires. Neither the secret, the assertion nor a token
 * is ever logged.
 */
public final class ClientCredentialsTokenProvider implements ServiceTokenProvider {
  static final String ASSERTION_TYPE = "urn:ietf:params:oauth:client-assertion-type:jwt-bearer";
  private static final Logger LOG = LoggerFactory.getLogger(ClientCredentialsTokenProvider.class);

  private final URI endpoint;
  private final String clientId;
  private final String clientSecret;
  private final Path assertionFile;
  private final Duration refreshBefore;
  private final Duration timeout;
  private final HttpClient http;
  private final Clock clock;
  private static final Pattern SERVICE = Pattern.compile("[a-z][a-z0-9-]*");
  private final ReentrantLock lock = new ReentrantLock();
  private final Map<String, Cached> cached = new ConcurrentHashMap<>();

  private record Cached(String token, Instant refreshAt, Instant expiresAt) {}

  public ClientCredentialsTokenProvider(ServiceTokenProperties props, Clock clock) {
    this(props, clock, HttpClient.newBuilder().connectTimeout(props.timeout()).build());
  }

  ClientCredentialsTokenProvider(ServiceTokenProperties props, Clock clock, HttpClient http) {
    if (props.tokenEndpoint().isBlank()) {
      throw new IllegalArgumentException("shp.auth.client.token-endpoint is not set");
    }
    if (props.clientId().isBlank()) {
      throw new IllegalArgumentException("shp.auth.client.client-id is not set");
    }
    this.endpoint = URI.create(props.tokenEndpoint());
    this.clientId = props.clientId();
    this.clientSecret = props.clientSecret();
    this.assertionFile = props.assertionFile().isBlank() ? null : Path.of(props.assertionFile());
    this.refreshBefore = props.refreshBefore();
    this.timeout = props.timeout();
    this.http = http;
    this.clock = clock;
  }

  /** True when a secret or an assertion file is configured. */
  public boolean hasCredentials() {
    return assertionFile != null || !clientSecret.isEmpty();
  }

  @Override
  public String token(String target) {
    if (!SERVICE.matcher(target).matches()) {
      throw new IllegalArgumentException("Expected one target service name");
    }
    Cached c = cached.get(target);
    Instant now = clock.instant();
    if (c != null && now.isBefore(c.refreshAt())) {
      return c.token();
    }
    lock.lock();
    try {
      c = cached.get(target);
      now = clock.instant();
      if (c != null && now.isBefore(c.refreshAt())) {
        return c.token();
      }
      try {
        c = fetch(now, target);
        cached.put(target, c);
        return c.token();
      } catch (ServiceTokenException e) {
        if (c != null && now.isBefore(c.expiresAt())) {
          LOG.warn("Service token refresh failed; using the cached token until it expires");
          return c.token();
        }
        throw e;
      }
    } finally {
      lock.unlock();
    }
  }

  @Override
  public void invalidate(String target) {
    cached.remove(target);
  }

  private Cached fetch(Instant now, String target) {
    if (!hasCredentials()) {
      throw new ServiceTokenException(
          "No client secret or assertion file is configured for client " + clientId);
    }
    var form = new StringBuilder("grant_type=client_credentials&scope=call-").append(enc(target));
    var request = HttpRequest.newBuilder(endpoint).timeout(timeout);
    request.header("Content-Type", "application/x-www-form-urlencoded");
    request.header("Accept", "application/json");
    if (assertionFile != null) {
      String assertion;
      try {
        assertion = Files.readString(assertionFile, StandardCharsets.UTF_8).strip();
      } catch (IOException e) {
        throw new ServiceTokenException("Cannot read the client assertion file", e);
      }
      form.append("&client_id=").append(enc(clientId));
      form.append("&client_assertion_type=").append(enc(ASSERTION_TYPE));
      form.append("&client_assertion=").append(enc(assertion));
    } else {
      String basic = enc(clientId) + ":" + enc(clientSecret);
      request.header(
          "Authorization",
          "Basic " + Base64.getEncoder().encodeToString(basic.getBytes(StandardCharsets.UTF_8)));
    }
    request.POST(HttpRequest.BodyPublishers.ofString(form.toString()));
    HttpResponse<String> response;
    try {
      response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    } catch (IOException e) {
      throw new ServiceTokenException("Token endpoint unreachable", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ServiceTokenException("Interrupted while requesting a service token", e);
    }
    if (response.statusCode() != 200) {
      // The body is not logged or returned: it can echo request parameters.
      throw new ServiceTokenException(
          "Token endpoint answered " + response.statusCode() + " for client " + clientId);
    }
    Map<String, Object> body;
    try {
      body = JSONObjectUtils.parse(response.body());
    } catch (Exception e) {
      throw new ServiceTokenException("Token endpoint answered with an unreadable body");
    }
    if (!(body.get("access_token") instanceof String token) || token.isBlank()) {
      throw new ServiceTokenException("Token endpoint answered without an access token");
    }
    long expiresIn = body.get("expires_in") instanceof Number n ? n.longValue() : 60;
    Duration lifetime = Duration.ofSeconds(Math.max(expiresIn, 1));
    // A very short lifetime is refreshed after half of it, not on every call.
    Duration margin =
        refreshBefore.compareTo(lifetime.dividedBy(2)) > 0 ? lifetime.dividedBy(2) : refreshBefore;
    return new Cached(token, now.plus(lifetime).minus(margin), now.plus(lifetime));
  }

  private static String enc(String v) {
    return URLEncoder.encode(v, StandardCharsets.UTF_8);
  }
}
