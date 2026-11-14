package org.shp.web.auth;

import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClient;

/**
 * Call clinical's {@code GET /internal/v1/identity/{subject}} (09-api-clinical §4). The workload
 * identity token is attached by the {@link RestClient} interceptor configured by the service.
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
                        "identity service returned " + res.getStatusCode().value());
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
          "fhirUser is not in the form Patient/{id} or Practitioner/{id}");
    }
    return Optional.of(new IdentityLink(parts[0], parts[1], dto.disabled()));
  }
}
