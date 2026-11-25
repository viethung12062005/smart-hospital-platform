package org.shp.scheduling.infra;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.shp.scheduling.domain.AgeBandClient;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/**
 * HTTP implementation of {@link AgeBandClient}: {@code GET /internal/v1/patients/{id}/age-band}.
 */
public class HttpAgeBandClient implements AgeBandClient {

  private record AgeBandDto(String ageBand) {}

  private final RestClient restClient;

  public HttpAgeBandClient(RestClient restClient) {
    this.restClient = Objects.requireNonNull(restClient, "restClient must not be null");
  }

  @Override
  public Optional<String> ageBand(UUID patientId) {
    Objects.requireNonNull(patientId, "patientId must not be null");
    return restClient
        .get()
        .uri("/internal/v1/patients/{id}/age-band", patientId)
        .accept(MediaType.APPLICATION_JSON)
        .exchange((req, res) -> {
          int status = res.getStatusCode().value();
          if (status == 404) {
            return Optional.<String>empty();
          }
          if (!res.getStatusCode().is2xxSuccessful()) {
            throw new IllegalStateException("clinical age-band lookup answered " + status);
          }
          AgeBandDto dto = res.bodyTo(AgeBandDto.class);
          if (dto == null || dto.ageBand() == null || dto.ageBand().isBlank()) {
            throw new IllegalStateException("clinical age-band lookup returned no band");
          }
          return Optional.of(dto.ageBand());
        });
  }
}
