package org.shp.clinical.api;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
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
   * @param linkedProfiles profiles the account manages through {@code profile_links}; empty until
   *     CLN-33 adds them
   */
  record Me(String subject, String fhirUser, List<String> roles, List<Object> linkedProfiles) {}

  private final IdentityResolver identity;

  MeController(IdentityResolver identity) {
    this.identity = identity;
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
        principal.subject(), fhirUser, principal.roles().stream().sorted().toList(), List.of());
  }
}
