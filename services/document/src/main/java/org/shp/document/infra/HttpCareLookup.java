package org.shp.document.infra;

import org.shp.document.domain.CareLookup;
import org.shp.web.errors.ShpException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Calls clinical's {@code GET /internal/v1/care-relationships/check} (09 §4). The workload token is
 * attached by the {@link RestClient} interceptor.
 */
public final class HttpCareLookup implements CareLookup {
  private record Dto(boolean care) {}

  private final RestClient http;

  public HttpCareLookup(RestClient http) {
    this.http = http;
  }

  @Override
  public boolean hasCare(String practitionerId, String patientId) {
    try {
      Dto dto =
          http.get()
              .uri(
                  "/internal/v1/care-relationships/check?practitioner={p}&patient={q}",
                  practitionerId,
                  patientId)
              .retrieve()
              .body(Dto.class);
      return dto != null && dto.care();
    } catch (RestClientException e) {
      throw new ShpException("dependency-unavailable");
    }
  }
}
