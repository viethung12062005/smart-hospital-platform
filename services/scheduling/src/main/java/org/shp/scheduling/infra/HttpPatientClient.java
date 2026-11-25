package org.shp.scheduling.infra;

import java.util.Objects;
import java.util.UUID;
import org.shp.scheduling.domain.PatientClient;
import org.shp.web.errors.ShpException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * HTTP implementation of {@link PatientClient}: {@code GET /internal/v1/patients/{id}/age-band} of
 * the clinical service answers 404 for a patient that does not exist (09 §4).
 */
public class HttpPatientClient implements PatientClient {

  private static final Logger log = LoggerFactory.getLogger(HttpPatientClient.class);

  private final RestClient restClient;

  public HttpPatientClient(RestClient restClient) {
    this.restClient = Objects.requireNonNull(restClient, "restClient must not be null");
  }

  @Override
  public boolean exists(UUID patientId) {
    Objects.requireNonNull(patientId, "patientId must not be null");
    try {
      Boolean found =
          restClient
              .get()
              .uri("/internal/v1/patients/{id}/age-band", patientId)
              .accept(MediaType.APPLICATION_JSON)
              .exchange((req, res) -> {
                int status = res.getStatusCode().value();
                if (status == 404) {
                  return false;
                }
                if (!res.getStatusCode().is2xxSuccessful()) {
                  log.warn("Clinical patient lookup failed with status {}", status);
                  throw new ShpException("dependency-unavailable");
                }
                return true;
              });
      return Boolean.TRUE.equals(found);
    } catch (ShpException e) {
      throw e;
    } catch (RestClientException e) {
      log.warn("Clinical patient lookup failed: {}", e.getClass().getSimpleName());
      throw new ShpException("dependency-unavailable");
    }
  }
}
