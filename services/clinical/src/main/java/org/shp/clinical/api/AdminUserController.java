package org.shp.clinical.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import org.shp.clinical.app.IdentityAccountService;
import org.shp.clinical.app.RoleMismatchException;
import org.shp.clinical.domain.IdentityAccount;
import org.shp.web.auth.IdentityInterceptor;
import org.shp.web.authz.RequireShpAuthz;
import org.shp.web.errors.ErrorInfo;
import org.shp.web.errors.ErrorWriter;
import org.shp.web.precondition.PreconditionInterceptor;
import org.shp.web.precondition.Versioned;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * C21 user administration (09 §5): list, link and lock accounts. Admin only; the matrix rule {@code
 * User/manage} audits every allow and deny. An Admin never reads clinical data here: the rows hold
 * identifiers and roles only.
 */
@RestController
@RequestMapping("/api/v1/admin/users")
class AdminUserController {
  /**
   * Error for a role that disagrees with the linked profile. The code is documented in 09 §5; the
   * shared catalog (07 §5) does not list it yet.
   */
  static final ErrorInfo ROLE_MISMATCH =
      new ErrorInfo(
          "role-mismatch",
          422,
          "business-rule",
          "Vai trò không khớp với hồ sơ được liên kết.",
          "The roles do not match the linked profile.");

  record LinkRequest(String subject, String fhirUser, List<String> roles) {}

  record LockRequest(Boolean disabled) {}

  record AccountView(
      String subject,
      String fhirUser,
      List<String> roles,
      boolean disabled,
      long version,
      Instant createdAt,
      Instant updatedAt)
      implements Versioned {
    static AccountView of(IdentityAccount a) {
      return new AccountView(
          a.subject(),
          a.fhirUser(),
          a.roles(),
          a.disabled(),
          a.version(),
          a.createdAt(),
          a.updatedAt());
    }
  }

  record AccountPage(List<AccountView> items, String nextPageToken) {}

  private final IdentityAccountService service;

  AdminUserController(IdentityAccountService service) {
    this.service = service;
  }

  @GetMapping
  @RequireShpAuthz(resource = "User", action = "manage")
  AccountPage list(
      @RequestParam(name = "_count", required = false) Integer count,
      @RequestParam(name = "_page_token", required = false) String pageToken) {
    var page = service.list(count, pageToken);
    return new AccountPage(
        page.items().stream().map(AccountView::of).toList(), page.nextPageToken());
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @RequireShpAuthz(resource = "User", action = "manage")
  AccountView link(@RequestBody LinkRequest body, HttpServletRequest req) {
    var admin = IdentityInterceptor.principal(req);
    return AccountView.of(
        service.link(body.subject(), body.fhirUser(), body.roles(), admin.subject()));
  }

  @PutMapping("/{subject}")
  @RequireShpAuthz(resource = "User", action = "manage")
  AccountView lock(
      @PathVariable String subject, @RequestBody LockRequest body, HttpServletRequest req) {
    var admin = IdentityInterceptor.principal(req);
    return AccountView.of(
        service.setDisabled(
            subject,
            body.disabled(),
            PreconditionInterceptor.expectedVersion(req),
            admin.subject()));
  }

  @ExceptionHandler(RoleMismatchException.class)
  void roleMismatch(RoleMismatchException ex, HttpServletRequest req, HttpServletResponse res)
      throws IOException {
    ErrorWriter.write(req, res, ROLE_MISMATCH, ex.getMessage(), null);
  }
}
