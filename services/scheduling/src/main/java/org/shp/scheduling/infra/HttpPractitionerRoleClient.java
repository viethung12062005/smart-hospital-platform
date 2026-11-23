package org.shp.scheduling.infra;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.shp.scheduling.domain.PractitionerRoleClient;
import org.shp.web.errors.ShpException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * HTTP implementation of {@link PractitionerRoleClient} backed by the clinical service's internal
 * lookup endpoint {@code GET /internal/v1/practitioners/{id}/roles}.
 */
public class HttpPractitionerRoleClient implements PractitionerRoleClient {

  private static final Logger log = LoggerFactory.getLogger(HttpPractitionerRoleClient.class);

  private final RestClient restClient;

  public HttpPractitionerRoleClient(RestClient restClient) {
    this.restClient = Objects.requireNonNull(restClient, "restClient must not be null");
  }

  @Override
  public List<String> findActiveSpecialties(UUID practitionerId) {
    if (practitionerId == null) {
      return List.of();
    }
    try {
      List<String> specialties =
          restClient
              .get()
              .uri("/internal/v1/practitioners/{id}/roles", practitionerId)
              .accept(MediaType.APPLICATION_JSON)
              .exchange((req, res) -> {
                int status = res.getStatusCode().value();
                if (status == 404) {
                  return List.of();
                }
                if (!res.getStatusCode().is2xxSuccessful()) {
                  log.warn("Clinical practitioner roles lookup failed with status {}", status);
                  throw new ShpException("dependency-unavailable");
                }
                RoleDto[] roles = res.bodyTo(RoleDto[].class);
                if (roles == null || roles.length == 0) {
                  return List.of();
                }
                return Arrays.stream(roles)
                    .filter(Objects::nonNull)
                    .filter(RoleDto::active)
                    .map(RoleDto::specialty)
                    .filter(s -> s != null && !s.isBlank())
                    .map(s -> s.strip().toLowerCase(Locale.ROOT))
                    .distinct()
                    .toList();
              });
      return specialties != null ? specialties : List.of();
    } catch (ShpException e) {
      throw e;
    } catch (RestClientException e) {
      log.warn("Clinical practitioner roles lookup failed: {}", e.getClass().getSimpleName());
      throw new ShpException("dependency-unavailable");
    }
  }

  private record RoleDto(String id, String specialty, String code, boolean active) {}
}
