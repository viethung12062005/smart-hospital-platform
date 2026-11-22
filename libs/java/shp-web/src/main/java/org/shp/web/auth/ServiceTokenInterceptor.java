package org.shp.web.auth;

import java.io.IOException;
import org.shp.security.ServiceTokenProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

/**
 * Attaches the service's workload-identity token to a call to another service's {@code /internal/*}
 * API (15 §7). Add it to the {@code RestClient} that talks to that service; it never touches a
 * request that already has an {@code Authorization} header. When the callee answers 401 the cached
 * token is dropped and the call is repeated once with a fresh one (rotated signing key, revoked
 * session).
 */
public final class ServiceTokenInterceptor implements ClientHttpRequestInterceptor {
  private final ServiceTokenProvider tokens;

  public ServiceTokenInterceptor(ServiceTokenProvider tokens) {
    this.tokens = tokens;
  }

  @Override
  public ClientHttpResponse intercept(
      HttpRequest request, byte[] body, ClientHttpRequestExecution execution) throws IOException {
    if (request.getHeaders().containsHeader(HttpHeaders.AUTHORIZATION)) {
      return execution.execute(request, body);
    }
    request.getHeaders().setBearerAuth(tokens.token());
    ClientHttpResponse response = execution.execute(request, body);
    if (!response.getStatusCode().isSameCodeAs(HttpStatus.UNAUTHORIZED)) {
      return response;
    }
    response.close();
    tokens.invalidate();
    request.getHeaders().setBearerAuth(tokens.token());
    return execution.execute(request, body);
  }
}
