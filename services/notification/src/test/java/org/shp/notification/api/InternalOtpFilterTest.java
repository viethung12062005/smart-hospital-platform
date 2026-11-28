package org.shp.notification.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import jakarta.servlet.FilterChain;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.shp.notification.app.OtpService;
import org.shp.web.errors.ShpException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/** N8/N9 caller rules and the wire format. Synthetic numbers only. */
class InternalOtpFilterTest {
  final OtpService otp = mock(OtpService.class);
  final FilterChain chain = mock(FilterChain.class);
  final InternalOtpFilter filter = new InternalOtpFilter(otp, Set.of("svc-clinical"));

  @AfterEach
  void clearContext() {
    SecurityContextHolder.clearContext();
  }

  static void signIn(String azp, String... roles) {
    Jwt jwt =
        Jwt.withTokenValue("test-token")
            .header("alg", "none")
            .subject("service-account-" + azp)
            .claim("azp", azp)
            .claim("roles", List.of(roles))
            .build();
    SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
  }

  MockHttpServletResponse post(String uri, String body) throws Exception {
    var req = new MockHttpServletRequest("POST", uri);
    req.setContent(body.getBytes(StandardCharsets.UTF_8));
    req.setContentType("application/json");
    var res = new MockHttpServletResponse();
    filter.doFilter(req, res, chain);
    return res;
  }

  @Test
  void sendReturnsTheChallengeAndPassesTheClientIp() throws Exception {
    signIn("svc-clinical", "internal.notification");
    var expires = Instant.parse("2026-11-17T03:05:00Z");
    when(otp.send("claim-record", "phone", "+84900000001", "vi", "203.0.113.7"))
        .thenReturn(new OtpService.Sent("c-1", "sms", expires));

    var res =
        post(
            "/internal/v1/otp/send",
            "{\"purpose\":\"claim-record\",\"channelHint\":\"phone\","
                + "\"destination\":\"+84900000001\",\"locale\":\"vi\","
                + "\"context\":{\"clientIp\":\"203.0.113.7\"}}");

    assertThat(res.getStatus()).isEqualTo(200);
    assertThat(res.getContentAsString())
        .contains("\"challengeId\":\"c-1\"", "\"channel\":\"sms\"", "2026-11-17T03:05:00Z")
        .doesNotContain("+84900000001");
  }

  @Test
  void xForwardedForIsNotUsedAsTheClientIp() throws Exception {
    signIn("svc-clinical", "internal.notification");
    var expires = Instant.parse("2026-11-17T03:05:00Z");
    when(otp.send("register", "phone", "+84900000001", "vi", null))
        .thenReturn(new OtpService.Sent("c-2", "sms", expires));

    var req = new MockHttpServletRequest("POST", "/internal/v1/otp/send");
    req.addHeader("X-Forwarded-For", "10.244.0.12, 198.51.100.4");
    req.setContent(
        ("{\"purpose\":\"register\",\"channelHint\":\"phone\","
                + "\"destination\":\"+84900000001\",\"locale\":\"vi\"}")
            .getBytes(StandardCharsets.UTF_8));
    req.setContentType("application/json");
    var res = new MockHttpServletResponse();
    filter.doFilter(req, res, chain);

    assertThat(res.getStatus()).isEqualTo(200);
    // Without context.clientIp the service gets no IP, only the per-number limit applies.
    verify(otp).send("register", "phone", "+84900000001", "vi", null);
  }

  @Test
  void verifyReturnsOk() throws Exception {
    signIn("svc-clinical", "internal.notification");
    when(otp.verify("c-1", "123456")).thenReturn(true);
    var res = post("/internal/v1/otp/verify", "{\"challengeId\":\"c-1\",\"code\":\"123456\"}");
    assertThat(res.getStatus()).isEqualTo(200);
    assertThat(res.getContentAsString()).contains("\"ok\":true");
  }

  @Test
  void aBusinessErrorKeepsItsStatus() throws Exception {
    signIn("svc-clinical", "internal.notification");
    when(otp.verify(any(), any())).thenThrow(new ShpException("otp-locked"));
    var res = post("/internal/v1/otp/verify", "{\"challengeId\":\"c-1\",\"code\":\"123456\"}");
    assertThat(res.getStatus()).isEqualTo(429);
    assertThat(res.getContentAsString()).contains("otp-locked").doesNotContain("123456");
  }

  @Test
  void aUserTokenIsForbidden() throws Exception {
    signIn("shp-api", "patient");
    var res = post("/internal/v1/otp/verify", "{\"challengeId\":\"c-1\",\"code\":\"123456\"}");
    assertThat(res.getStatus()).isEqualTo(403);
    verifyNoInteractions(otp);
  }

  @Test
  void aServiceThatIsNotAllowedIsForbidden() throws Exception {
    signIn("svc-document", "internal.notification");
    var res = post("/internal/v1/otp/send", "{}");
    assertThat(res.getStatus()).isEqualTo(403);
    verifyNoInteractions(otp);
  }

  @Test
  void noTokenIsUnauthenticated() throws Exception {
    var res = post("/internal/v1/otp/send", "{}");
    assertThat(res.getStatus()).isEqualTo(401);
    verifyNoInteractions(otp);
  }

  @Test
  void malformedJsonIsRejectedWithoutEchoingIt() throws Exception {
    signIn("svc-clinical", "internal.notification");
    var res = post("/internal/v1/otp/verify", "{\"code\":\"123456\"");
    assertThat(res.getStatus()).isEqualTo(400);
    assertThat(res.getContentAsString()).doesNotContain("123456");
  }

  @Test
  void otherPathsAreNotHandled() throws Exception {
    var req = new MockHttpServletRequest("POST", "/internal/v1/other");
    var res = new MockHttpServletResponse();
    filter.doFilter(req, res, chain);
    verify(chain).doFilter(req, res);
  }
}
