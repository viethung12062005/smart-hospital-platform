package org.shp.gateway;

import java.net.InetSocketAddress;
import java.security.Principal;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Rate limiting at the gateway (07 §6, NFR-S3): token bucket on Redis, counted by the token's
 * {@code sub} (registration: by IP). Exceeding the limit -> 429 {@code rate-limited} with {@code
 * Retry-After}.
 *
 * <p>Runs after authentication (Spring Security is a WebFilter, running before GlobalFilter), so a
 * wrong token still receives 401 first. If Redis fails, {@link RedisRateLimiter} lets requests
 * through (fail-open): a temporary loss of limits is better than blocking all users; gateway
 * readiness therefore does not depend on Redis.
 */
@Component
@ConditionalOnProperty(
    name = "shp.gateway.rate-limit.enabled",
    havingValue = "true",
    matchIfMissing = true)
class RateLimitFilter implements GlobalFilter, Ordered {

  /** Time to replenish one request = requestedTokens / replenishPerSecond seconds. */
  record Policy(
      String id, int replenishPerSecond, int burstCapacity, int requestedTokens, boolean byIp) {
    long retryAfterSeconds() {
      return Math.max(1, (long) Math.ceil((double) requestedTokens / replenishPerSecond));
    }
  }

  // Table 07 §6. Token bucket replenishes at least 1 token/second, so per-minute/hour limits use
  // requestedTokens:
  // 10/minute = each request costs 6 tokens, replenishing 1 token/second.
  static final Policy REGISTRATION =
      new Policy("registration", 1, 5 * 720, 720, true); // 5/hour per IP
  static final Policy BOOKING =
      new Policy("booking", 1, 10 * 6, 6, false); // $book, $reschedule 10/minute
  static final Policy PATIENT_SEARCH =
      new Policy("patient-search", 1, 10, 1, false); // 60/minute, burst 10
  static final Policy AI = new Policy("ai", 1, 10 * 3, 3, false); // 20/minute, burst 10
  static final Policy DEFAULT = new Policy("default", 2, 30, 1, false); // 120/minute, burst 30
  static final List<Policy> ALL = List.of(REGISTRATION, BOOKING, PATIENT_SEARCH, AI, DEFAULT);

  private final RedisRateLimiter limiter;

  RateLimitFilter(RedisRateLimiter limiter) {
    this.limiter = limiter;
    for (Policy p : ALL) {
      limiter
          .getConfig()
          .put(
              p.id(),
              new RedisRateLimiter.Config()
                  .setReplenishRate(p.replenishPerSecond())
                  .setBurstCapacity(p.burstCapacity())
                  .setRequestedTokens(p.requestedTokens()));
    }
  }

  static Policy policyFor(ServerHttpRequest req) {
    String path = req.getPath().value();
    HttpMethod m = req.getMethod();
    if (path.equals("/api/v1/registration")) {
      return REGISTRATION;
    }
    if (HttpMethod.POST.equals(m) && (path.endsWith("/$book") || path.endsWith("/$reschedule"))) {
      return BOOKING;
    }
    if (HttpMethod.POST.equals(m) && path.equals("/fhir/Patient/_search")) {
      return PATIENT_SEARCH;
    }
    if (path.startsWith("/api/v1/ai/")) {
      return AI;
    }
    return DEFAULT;
  }

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
    Policy policy = policyFor(exchange.getRequest());
    return key(exchange, policy)
        .flatMap(k -> limiter.isAllowed(policy.id(), policy.id() + ":" + k))
        .flatMap(
            r -> {
              if (r.isAllowed()) {
                return chain.filter(exchange);
              }
              var res = exchange.getResponse();
              res.getHeaders().set("Retry-After", Long.toString(policy.retryAfterSeconds()));
              return GatewaySecurity.problem(res, HttpStatus.TOO_MANY_REQUESTS, "rate-limited");
            })
        // Public paths (health, info) have no user: not limited.
        .switchIfEmpty(Mono.defer(() -> chain.filter(exchange)));
  }

  private static Mono<String> key(ServerWebExchange exchange, Policy policy) {
    if (policy.byIp()) {
      return Mono.justOrEmpty(clientIp(exchange.getRequest()));
    }
    return exchange.getPrincipal().map(Principal::getName);
  }

  /**
   * Client IP: first element of X-Forwarded-For (after Front Door/Envoy), otherwise the connection
   * address.
   */
  static String clientIp(ServerHttpRequest req) {
    String xff = req.getHeaders().getFirst("X-Forwarded-For");
    if (xff != null && !xff.isBlank()) {
      return xff.split(",")[0].strip();
    }
    InetSocketAddress remote = req.getRemoteAddress();
    return remote == null ? null : remote.getAddress().getHostAddress();
  }

  @Override
  public int getOrder() {
    return Ordered.HIGHEST_PRECEDENCE + 10; // before routing to the upstream
  }
}
