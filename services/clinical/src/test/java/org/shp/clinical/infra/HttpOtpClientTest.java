package org.shp.clinical.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.shp.clinical.app.OtpClient;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** N8/N9 request bodies and the mapping of notification's answers (11 §N8, N9). */
class HttpOtpClientTest {
  MockRestServiceServer server;
  HttpOtpClient client;

  @BeforeEach
  void setUp() {
    RestClient.Builder builder = RestClient.builder().baseUrl("http://notification");
    server = MockRestServiceServer.bindTo(builder).build();
    client = new HttpOtpClient(builder.build());
  }

  @Test
  void sendPostsN8AndReturnsTheChallenge() {
    server
        .expect(requestTo("http://notification/internal/v1/otp/send"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(
            content()
                .json(
                    """
                    { "purpose": "claim-record", "channelHint": "phone",
                      "destination": "+84901000061", "locale": "vi" }
                    """))
        .andRespond(
            withSuccess(
                "{\"challengeId\":\"ch-1\",\"channel\":\"zalo\"}", MediaType.APPLICATION_JSON));
    assertThat(client.send("claim-record", "+84901000061", "vi")).isEqualTo("ch-1");
    server.verify();
  }

  @Test
  void sendRefusedWith429IsRateLimited() {
    server
        .expect(requestTo("http://notification/internal/v1/otp/send"))
        .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
    assertThatThrownBy(() -> client.send("claim-record", "+84901000061", "vi"))
        .isInstanceOf(OtpClient.RateLimited.class);
  }

  @Test
  void sendFailureIsAnError() {
    server
        .expect(requestTo("http://notification/internal/v1/otp/send"))
        .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
    assertThatThrownBy(() -> client.send("claim-record", "+84901000061", "vi"))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void verifyMapsTheAnswers() {
    server
        .expect(requestTo("http://notification/internal/v1/otp/verify"))
        .andExpect(content().json("{ \"challengeId\": \"ch-1\", \"code\": \"246810\" }"))
        .andRespond(withSuccess("{\"ok\":true}", MediaType.APPLICATION_JSON));
    server
        .expect(requestTo("http://notification/internal/v1/otp/verify"))
        .andRespond(withSuccess("{\"ok\":false}", MediaType.APPLICATION_JSON));
    server
        .expect(requestTo("http://notification/internal/v1/otp/verify"))
        .andRespond(withStatus(HttpStatus.UNPROCESSABLE_CONTENT));
    server
        .expect(requestTo("http://notification/internal/v1/otp/verify"))
        .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
    assertThat(client.verify("ch-1", "246810")).isEqualTo(OtpClient.Result.OK);
    assertThat(client.verify("ch-1", "000000")).isEqualTo(OtpClient.Result.INVALID);
    assertThat(client.verify("ch-1", "000000")).isEqualTo(OtpClient.Result.INVALID);
    assertThat(client.verify("ch-1", "000000")).isEqualTo(OtpClient.Result.LOCKED);
    server.verify();
  }
}
