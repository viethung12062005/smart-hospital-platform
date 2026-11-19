package org.shp.clinical.api;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.util.List;
import org.shp.clinical.app.ProfileLinks;
import org.shp.clinical.domain.LinkedProfile;
import org.shp.web.auth.AllowUnlinkedIdentity;
import org.shp.web.auth.IdentityInterceptor;
import org.shp.web.auth.IdentityLink;
import org.shp.web.auth.IdentityResolver;
import org.shp.web.authz.ShpPublicEndpoint;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * C2 {@code GET /api/v1/me}: who the caller is. Open to every authenticated account (including one
 * not linked yet, so the web app can send it to registration); a locked account is rejected by the
 * identity layer before this runs.
 */
@RestController
@AllowUnlinkedIdentity
class MeController {
  /**
   * @param fhirUser {@code Patient/{id}} or {@code Practitioner/{id}}; null when not linked yet
   * @param linkedProfiles active, unexpired {@code profile_links} of the account (09 §5)
   */
  record Me(
      String subject, String fhirUser, List<String> roles, List<LinkedProfile> linkedProfiles) {}

  private final IdentityResolver identity;
  private final ProfileLinks links;
  private final Clock clock;

  MeController(IdentityResolver identity, ProfileLinks links, Clock clock) {
    this.identity = identity;
    this.links = links;
    this.clock = clock;
  }

  // Authentication is enforced by the security filter chain; the matrix has no rule for "me".
  @GetMapping("/api/v1/me")
  @ShpPublicEndpoint
  Me me(HttpServletRequest req) {
    var principal = IdentityInterceptor.principal(req);
    String fhirUser =
        identity
            .resolve(principal.subject())
            .map((IdentityLink l) -> l.fhirUserType() + "/" + l.fhirUserId())
            .orElse(null);
    return new Me(
        principal.subject(),
        fhirUser,
        principal.roles().stream().sorted().toList(),
        links.activeFor(principal.subject(), clock.instant()));
  }
}
