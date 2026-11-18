package org.shp.clinical.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.shp.clinical.app.AlreadyRegisteredException;
import org.shp.clinical.app.RegistrationService;
import org.shp.web.auth.AllowUnlinkedIdentity;
import org.shp.web.auth.IdentityInterceptor;
import org.shp.web.authz.RequireShpAuthz;
import org.shp.web.errors.ErrorInfo;
import org.shp.web.errors.ErrorWriter;
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
  /**
   * 09 §2 specifies this code and status, but the shared catalog ({@code ErrorCatalog}, owned by
   * the platform library) does not list it yet, so the controller writes it with the standard
   * writer. Move it into the catalog and 07 §5, then delete this constant.
   */
  static final ErrorInfo ALREADY_REGISTERED =
      new ErrorInfo(
          "already-registered",
          409,
          "conflict",
          "Tài khoản này đã có hồ sơ bệnh nhân.",
          "This account already has a patient record.");

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
      HttpServletRequest req,
      HttpServletResponse res)
      throws IOException {
    var principal = IdentityInterceptor.principal(req);
    try {
      var result =
          service.register(
              principal.subject(),
              jwt.getIssuer() == null ? "" : jwt.getIssuer().toString(),
              principal.fhirUserId() != null,
              body);
      return ResponseEntity.status(HttpStatus.CREATED).body(result);
    } catch (AlreadyRegisteredException e) {
      ErrorWriter.write(req, res, ALREADY_REGISTERED, null, null);
      return null; // the response is already written
    }
  }
}
