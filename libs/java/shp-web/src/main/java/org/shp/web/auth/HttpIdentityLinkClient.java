package org.shp.web.auth;

import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.shp.authz.LinkedProfile;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClient;

/**
 * Call clinical's {@code GET /internal/v1/identity/{subject}} (09-api-clinical §4). The workload
 * identity token is attached by the {@link RestClient} interceptor configured by the service.
 */
public final class HttpIdentityLinkClient implements IdentityLinkClient {
  private static final Set<String> RELATIONSHIPS = Set.of("guardian", "proxy");
  private static final Set<String> SCOPES = Set.of("booking", "records", "full");

  private final RestClient http;
  private final Clock clock;

  public HttpIdentityLinkClient(RestClient http) {
    this(http, Clock.systemUTC());
  }

  public HttpIdentityLinkClient(RestClient http, Clock clock) {
    this.http = http;
    this.clock = clock;
  }

  private record ProfileDto(
      String patientId, String relationship, String scope, String expiresAt) {}

  /** {@code linkedProfiles} is absent in responses of an older clinical: treated as none. */
  private record Dto(String fhirUser, boolean disabled, List<ProfileDto> linkedProfiles) {}

  @Override
  public Optional<IdentityLink> find(String subject) {
    Dto dto =
        http.get()
            .uri("/internal/v1/identity/{subject}", subject)
            .exchange((req, res) -> {
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
    return Optional.of(new IdentityLink(parts[0], parts[1], dto.disabled(), profiles(dto)));
  }

  /**
   * Fail closed: an entry that is incomplete, has an unknown relationship or scope, an unreadable
   * {@code expiresAt}, or is already past it is dropped, never granted.
   */
  private List<LinkedProfile> profiles(Dto dto) {
    if (dto.linkedProfiles() == null) {
      return List.of();
    }
    Instant now = clock.instant();
    return dto.linkedProfiles().stream()
        .filter(p -> p != null && p.patientId() != null && !p.patientId().isEmpty())
        .filter(p ->
            p.relationship() != null
                && RELATIONSHIPS.contains(p.relationship())
                && p.scope() != null
                && SCOPES.contains(p.scope()))
        .map(HttpIdentityLinkClient::toProfile)
        .flatMap(Optional::stream)
        .filter(p -> p.activeAt(now))
        .toList();
  }

  private static Optional<LinkedProfile> toProfile(ProfileDto p) {
    try {
      Instant expires = p.expiresAt() == null ? null : Instant.parse(p.expiresAt());
      return Optional.of(new LinkedProfile(p.patientId(), p.relationship(), p.scope(), expires));
    } catch (DateTimeParseException e) {
      return Optional.empty();
    }
  }
}
