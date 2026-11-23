package org.shp.clinical.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.shp.authz.Principal;
import org.shp.clinical.app.DependentProfiles;
import org.shp.clinical.app.DependentProfiles.DependentRequest;
import org.shp.clinical.app.DependentProfiles.Profile;
import org.shp.web.auth.IdentityInterceptor;
import org.shp.web.authz.RequireShpAuthz;
import org.shp.web.errors.ErrorWriter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 09 §9 C22 {@code GET /api/v1/me/profiles} and C23 {@code POST /api/v1/me/profiles/dependents}
 * (patients). The caller's own account is the subject: no other account's profiles can be named, so
 * the matrix rules {@code PROF-READ-PAT} and {@code PROF-CREATE-PAT} (patient role, no other
 * condition) are enough and every decision is audited.
 */
@RestController
public class MyProfilesController {
  private final DependentProfiles profiles;

  public MyProfilesController(DependentProfiles profiles) {
    this.profiles = profiles;
  }

  @GetMapping("/api/v1/me/profiles")
  @RequireShpAuthz(resource = "PatientProfile", action = "read")
  public List<Profile> list(HttpServletRequest req) {
    Principal p = patient(req);
    return profiles.list(p.subject(), p.fhirUserId());
  }

  /** C23 body: { name: { family, given[] }, birthDate, gender, declaration }. */
  public record Name(String family, List<String> given) {}

  public record CreateDependent(Name name, String birthDate, String gender, Boolean declaration) {}

  @PostMapping(path = "/api/v1/me/profiles/dependents", consumes = "application/json")
  @RequireShpAuthz(resource = "PatientProfile", action = "create")
  public ResponseEntity<Profile> create(@RequestBody CreateDependent body, HttpServletRequest req) {
    Principal p = patient(req);
    DependentRequest r =
        body == null
            ? null
            : new DependentRequest(
                body.name() == null ? null : body.name().family(),
                body.name() == null ? null : body.name().given(),
                body.birthDate(),
                body.gender(),
                body.declaration());
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(profiles.createDependent(p.subject(), p.fhirUserId(), r, "User/" + p.subject()));
  }

  @ExceptionHandler(DependentProfiles.RuleException.class)
  void rule(DependentProfiles.RuleException ex, HttpServletRequest req, HttpServletResponse res)
      throws IOException {
    ErrorWriter.write(req, res, ex.info(), ex.getMessage(), null);
  }

  private static Principal patient(HttpServletRequest req) {
    return IdentityInterceptor.principal(req);
  }
}
