package org.shp.clinical.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.shp.authz.Principal;
import org.shp.clinical.app.RecordClaims;
import org.shp.clinical.app.RecordClaims.ClaimRequest;
import org.shp.web.auth.AllowUnlinkedIdentity;
import org.shp.web.auth.IdentityInterceptor;
import org.shp.web.authz.ShpPublicEndpoint;
import org.shp.web.errors.ErrorWriter;
import org.shp.web.errors.ShpException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 09 §9 C27 {@code POST /api/v1/me/claim-record} and C28 {@code POST
 * /api/v1/me/claim-record/verify}. The caller is a patient account that has no record yet, so, like
 * C1, the account does not have to be linked; the record is only named after the code is right.
 */
@RestController
public class ClaimRecordController {
  private final RecordClaims claims;

  public ClaimRecordController(RecordClaims claims) {
    this.claims = claims;
  }

  public record Name(String family, List<String> given) {}

  public record ClaimBody(Name name, String birthDate, String phone) {}

  public record VerifyBody(String otp) {}

  public record Status(String status) {}

  public record Linked(String status, String patientId) {}

  @PostMapping(path = "/api/v1/me/claim-record", consumes = "application/json")
  @AllowUnlinkedIdentity
  @ShpPublicEndpoint
  public Status claim(@RequestBody ClaimBody body, HttpServletRequest req) {
    Principal p = patient(req);
    ClaimRequest r =
        body == null
            ? null
            : new ClaimRequest(
                body.name() == null ? null : body.name().family(),
                body.name() == null ? null : body.name().given(),
                body.birthDate(),
                body.phone());
    claims.claim(p.subject(), p.fhirUserId() != null, r);
    return new Status("claim-sent");
  }

  @PostMapping(path = "/api/v1/me/claim-record/verify", consumes = "application/json")
  @AllowUnlinkedIdentity
  @ShpPublicEndpoint
  public Linked verify(
      @RequestBody VerifyBody body, @AuthenticationPrincipal Jwt jwt, HttpServletRequest req) {
    Principal p = patient(req);
    String patientId =
        claims.verify(
            p.subject(),
            jwt.getIssuer() == null ? "" : jwt.getIssuer().toString(),
            p.fhirUserId() != null,
            body == null ? null : body.otp());
    return new Linked("linked", patientId);
  }

  @ExceptionHandler(RecordClaims.RuleException.class)
  void rule(RecordClaims.RuleException ex, HttpServletRequest req, HttpServletResponse res)
      throws IOException {
    ErrorWriter.write(req, res, ex.info(), ex.getMessage(), null);
  }

  private static Principal patient(HttpServletRequest req) {
    Principal p = IdentityInterceptor.principal(req);
    if (!p.roles().contains("patient")) {
      throw new ShpException("forbidden", "Records are claimed by patient accounts.");
    }
    return p;
  }
}
