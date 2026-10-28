package org.shp.web.auth;

import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClient;

/**
 * Gọi {@code GET /internal/v1/identity/{subject}} của clinical (09-api-clinical §4). Token workload
 * identity được gắn bởi interceptor của {@link RestClient} do service cấu hình.
 */
public final class HttpIdentityLinkClient implements IdentityLinkClient {
  private final RestClient http;

  public HttpIdentityLinkClient(RestClient http) {
    this.http = http;
  }

  private record Dto(String fhirUser, boolean disabled) {}

  @Override
  public Optional<IdentityLink> find(String subject) {
    Dto dto =
        http.get()
            .uri("/internal/v1/identity/{subject}", subject)
            .exchange(
                (req, res) -> {
                  if (res.getStatusCode().isSameCodeAs(HttpStatus.NOT_FOUND)) {
                    return null;
                  }
                  if (!res.getStatusCode().is2xxSuccessful()) {
                    throw new IllegalStateException(
                        "identity service trả " + res.getStatusCode().value());
                  }
                  return res.bodyTo(Dto.class);
                });
    if (dto == null) {
      return Optional.empty();
    }
    String[] parts = dto.fhirUser() == null ? new String[0] : dto.fhirUser().split("/", 2);
    if (parts.length != 2
        || !(parts[0].equals("Patient") || parts[0].equals("Practitioner"))
        || parts[1].isEmpty()) {
      throw new IllegalStateException(
          "fhirUser không đúng dạng Patient/{id} hoặc Practitioner/{id}");
    }
    return Optional.of(new IdentityLink(parts[0], parts[1], dto.disabled()));
  }
}
