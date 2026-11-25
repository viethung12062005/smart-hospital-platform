package org.shp.notification.infra;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.shp.notification.app.ContactClient;
import org.shp.web.errors.ShpException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * HTTP implementation of {@link ContactClient} over {@code GET /internal/v1/patients/{id}/contact}.
 * Clinical audits every call (09 §4). Nothing returned is cached, persisted or logged; log lines
 * carry only the HTTP status.
 */
public class HttpContactClient implements ContactClient {

  private static final Logger log = LoggerFactory.getLogger(HttpContactClient.class);

  private final RestClient restClient;

  public HttpContactClient(RestClient restClient) {
    this.restClient = Objects.requireNonNull(restClient, "restClient must not be null");
  }

  @Override
  public Optional<Contact> findContact(UUID patientId) {
    Objects.requireNonNull(patientId, "patientId must not be null");
    try {
      return restClient
          .get()
          .uri("/internal/v1/patients/{id}/contact", patientId)
          .accept(MediaType.APPLICATION_JSON)
          .exchange((req, res) -> {
            int status = res.getStatusCode().value();
            if (status == 404) {
              return Optional.<Contact>empty();
            }
            if (!res.getStatusCode().is2xxSuccessful()) {
              log.warn("Clinical contact lookup failed with status {}", status);
              throw new ShpException("dependency-unavailable");
            }
            return Optional.ofNullable(res.bodyTo(Contact.class));
          });
    } catch (ShpException e) {
      throw e;
    } catch (RestClientException e) {
      log.warn("Clinical contact lookup failed: {}", e.getClass().getSimpleName());
      throw new ShpException("dependency-unavailable");
    }
  }
}
