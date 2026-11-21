package org.shp.clinical.api;

import jakarta.servlet.http.HttpServletRequest;
import org.shp.clinical.app.RegistrationService;
import org.shp.web.auth.AllowUnlinkedIdentity;
import org.shp.web.auth.IdentityInterceptor;
import org.shp.web.authz.RequireShpAuthz;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** C1 {@code POST /api/v1/registration} (09 §2): a new account creates its own patient record. */
@RestController
class RegistrationController {
  private final RegistrationService service;

  RegistrationController(RegistrationService service) {
    this.service = service;
  }

  @PostMapping("/api/v1/registration")
  @AllowUnlinkedIdentity
  @RequireShpAuthz(resource = "Patient", action = "create")
  ResponseEntity<RegistrationResult> register(
      @RequestBody RegistrationRequest body,
      @AuthenticationPrincipal Jwt jwt,
      HttpServletRequest req) {
    var principal = IdentityInterceptor.principal(req);
    var result =
        service.register(
            principal.subject(),
            jwt.getIssuer() == null ? "" : jwt.getIssuer().toString(),
            principal.fhirUserId() != null,
            body);
    return ResponseEntity.status(HttpStatus.CREATED).body(result);
  }
}
