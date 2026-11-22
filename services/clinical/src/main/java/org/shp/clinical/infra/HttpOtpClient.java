package org.shp.clinical.infra;

import java.util.Map;
import org.shp.clinical.app.OtpClient;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/**
 * Calls notification's {@code POST /internal/v1/otp/send} (N8) and {@code /internal/v1/otp/verify}
 * (N9). The workload identity token is attached by the {@link RestClient} interceptor.
 */
public final class HttpOtpClient implements OtpClient {
  private final RestClient http;

  public HttpOtpClient(RestClient http) {
    this.http = http;
  }

  private record SendBody(
      String purpose,
      String channelHint,
      String destination,
      String locale,
      Map<String, Object> context) {}

  private record Challenge(String challengeId) {}

  private record VerifyBody(String challengeId, String code) {}

  private record Verified(boolean ok) {}

  @Override
  public String send(String purpose, String destination, String locale) {
    Challenge c =
        http.post()
            .uri("/internal/v1/otp/send")
            .contentType(MediaType.APPLICATION_JSON)
            .body(new SendBody(purpose, "phone", destination, locale, Map.of()))
            .exchange((req, res) -> {
              if (res.getStatusCode().isSameCodeAs(HttpStatus.TOO_MANY_REQUESTS)) {
                throw new RateLimited();
              }
              if (!res.getStatusCode().is2xxSuccessful()) {
                throw new IllegalStateException(
                    "notification N8 returned " + res.getStatusCode().value());
              }
              return res.bodyTo(Challenge.class);
            });
    if (c == null || c.challengeId() == null) {
      throw new IllegalStateException("notification N8 returned no challengeId");
    }
    return c.challengeId();
  }

  @Override
  public Result verify(String challengeId, String code) {
    return http.post()
        .uri("/internal/v1/otp/verify")
        .contentType(MediaType.APPLICATION_JSON)
        .body(new VerifyBody(challengeId, code))
        .exchange((req, res) -> {
          if (res.getStatusCode().isSameCodeAs(HttpStatus.TOO_MANY_REQUESTS)) {
            return Result.LOCKED;
          }
          if (res.getStatusCode().isSameCodeAs(HttpStatus.UNPROCESSABLE_CONTENT)
              || res.getStatusCode().isSameCodeAs(HttpStatus.NOT_FOUND)) {
            return Result.INVALID; // wrong code, or the challenge expired
          }
          if (!res.getStatusCode().is2xxSuccessful()) {
            throw new IllegalStateException(
                "notification N9 returned " + res.getStatusCode().value());
          }
          Verified v = res.bodyTo(Verified.class);
          return v != null && v.ok() ? Result.OK : Result.INVALID;
        });
  }
}
