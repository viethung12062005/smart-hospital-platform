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
 * Giới hạn tốc độ ở gateway (07 §6, NFR-S3): token bucket trên Redis, đếm theo {@code sub} của
 * token (đăng ký: theo IP). Vượt hạn mức → 429 {@code rate-limited} kèm {@code Retry-After}.
 *
 * <p>Chạy sau xác thực (Spring Security là WebFilter, chạy trước GlobalFilter), nên token sai vẫn
 * nhận 401 trước. Redis lỗi thì {@link RedisRateLimiter} cho qua (fail-open): mất giới hạn tạm thời
 * còn hơn chặn toàn bộ người dùng; readiness của gateway vì vậy không phụ thuộc Redis.
 */
@Component
@ConditionalOnProperty(
    name = "shp.gateway.rate-limit.enabled",
    havingValue = "true",
    matchIfMissing = true)
class RateLimitFilter implements GlobalFilter, Ordered {

  /** Thời gian nạp lại một yêu cầu = requestedTokens / replenishPerSecond giây. */
  record Policy(
      String id, int replenishPerSecond, int burstCapacity, int requestedTokens, boolean byIp) {
    long retryAfterSeconds() {
      return Math.max(1, (long) Math.ceil((double) requestedTokens / replenishPerSecond));
    }
  }

  // Bảng 07 §6. Token bucket nạp tối thiểu 1 token/giây, nên giới hạn theo phút/giờ dùng
  // requestedTokens:
  // 10/phút = mỗi yêu cầu tốn 6 token, nạp 1 token/giây.
  static final Policy REGISTRATION =
      new Policy("registration", 1, 5 * 720, 720, true); // 5/giờ theo IP
  static final Policy BOOKING =
      new Policy("booking", 1, 10 * 6, 6, false); // $book, $reschedule 10/phút
  static final Policy PATIENT_SEARCH =
      new Policy("patient-search", 1, 10, 1, false); // 60/phút, burst 10
  static final Policy AI = new Policy("ai", 1, 10 * 3, 3, false); // 20/phút, burst 10
  static final Policy DEFAULT = new Policy("default", 2, 30, 1, false); // 120/phút, burst 30
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
        // Đường dẫn công khai (health, info) không có người dùng: không giới hạn.
        .switchIfEmpty(Mono.defer(() -> chain.filter(exchange)));
  }

  private static Mono<String> key(ServerWebExchange exchange, Policy policy) {
    if (policy.byIp()) {
      return Mono.justOrEmpty(clientIp(exchange.getRequest()));
    }
    return exchange.getPrincipal().map(Principal::getName);
  }

  /**
   * IP client: phần tử đầu của X-Forwarded-For (sau Front Door/Envoy), nếu không có thì địa chỉ kết
   * nối.
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
    return Ordered.HIGHEST_PRECEDENCE + 10; // trước khi định tuyến tới upstream
  }
}
