package org.shp.clinical.app;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.shp.clinical.domain.IdentityAccount;
import org.shp.security.Roles;
import org.shp.security.ShpAuthProperties;
import org.shp.web.errors.ShpException;
import org.springframework.stereotype.Service;

/**
 * Link and lock accounts (C21, 09 §5). A link ties an OIDC subject to a Patient or Practitioner and
 * must agree with the profile: a patient account carries exactly the {@code patient} role; a staff
 * account carries {@code doctor}/{@code nurse} only when the Practitioner has an active
 * PractitionerRole with that code (15 §8). A lock is one write; every service rejects the account
 * once its 60-second identity cache expires (AC-08.1).
 */
@Service
public class IdentityAccountService {
  public static final int DEFAULT_PAGE = 50;
  public static final int MAX_PAGE = 200;
  private static final Pattern SUBJECT = Pattern.compile("[A-Za-z0-9._:@|-]{1,128}");
  private static final Pattern FHIR_USER =
      Pattern.compile("(Patient|Practitioner)/([A-Za-z0-9-]{1,64})");
  private static final Set<String> CLINICAL_STAFF_ROLES = Set.of("doctor", "nurse");

  /** One page of accounts and the token for the next page (null on the last page). */
  public record Page(List<IdentityAccount> items, String nextPageToken) {}

  private final IdentityAccounts accounts;
  private final ProfileDirectory profiles;
  private final ShpAuthProperties auth;
  private final Clock clock;

  public IdentityAccountService(
      IdentityAccounts accounts, ProfileDirectory profiles, ShpAuthProperties auth, Clock clock) {
    this.accounts = accounts;
    this.profiles = profiles;
    this.auth = auth;
    this.clock = clock;
  }

  public Page list(Integer count, String pageToken) {
    int limit = count == null ? DEFAULT_PAGE : count;
    if (limit < 1 || limit > MAX_PAGE) {
      throw new ShpException("validation-failed", "_count must be between 1 and " + MAX_PAGE);
    }
    String after = pageToken == null ? null : decodeToken(pageToken);
    List<IdentityAccount> rows = accounts.page(after, limit + 1);
    if (rows.size() <= limit) {
      return new Page(rows, null);
    }
    List<IdentityAccount> items = rows.subList(0, limit);
    String last = items.get(limit - 1).subject();
    return new Page(items, Base64.getUrlEncoder().withoutPadding().encodeToString(bytes(last)));
  }

  public IdentityAccount link(String subject, String fhirUser, List<String> roles, String actor) {
    if (subject == null || !SUBJECT.matcher(subject).matches()) {
      throw new ShpException("validation-failed", "subject is missing or malformed");
    }
    var m = fhirUser == null ? null : FHIR_USER.matcher(fhirUser);
    if (m == null || !m.matches()) {
      throw new ShpException(
          "validation-failed", "fhirUser must be Patient/{id} or Practitioner/{id}");
    }
    Set<String> wanted = normalizeRoles(roles);
    String type = m.group(1);
    String id = m.group(2);
    if (type.equals("Patient")) {
      checkPatient(id, wanted);
    } else {
      checkPractitioner(id, wanted);
    }
    var now = clock.instant();
    var account = new IdentityAccount(subject, fhirUser, List.copyOf(wanted), false, 1, now, now);
    if (!accounts.insert(account, auth.authority(), actor)) {
      throw new ShpException(
          "invalid-state-transition", "The account or the profile is already linked.");
    }
    return account;
  }

  /** Locks or unlocks; 404 when not linked, 412 when {@code expectedVersion} is stale. */
  public IdentityAccount setDisabled(
      String subject, Boolean disabled, long expectedVersion, String actor) {
    if (disabled == null) {
      throw new ShpException("validation-failed", "disabled is required");
    }
    var current = accounts.find(subject).orElseThrow(() -> new ShpException("not-found"));
    if (current.version() != expectedVersion) {
      throw new ShpException("version-conflict");
    }
    return accounts
        .setDisabled(subject, disabled, expectedVersion, clock.instant(), actor)
        .orElseThrow(() -> new ShpException("version-conflict"));
  }

  private void checkPatient(String patientId, Set<String> roles) {
    if (!roles.equals(Set.of("patient"))) {
      throw new RoleMismatchException("A Patient account carries exactly the patient role.");
    }
    if (!profiles.patientExists(patientId)) {
      throw new ShpException("not-found", "The linked Patient does not exist.");
    }
  }

  private void checkPractitioner(String practitionerId, Set<String> roles) {
    if (roles.contains("patient")) {
      throw new RoleMismatchException("A Practitioner account cannot carry the patient role.");
    }
    if (!profiles.practitionerExists(practitionerId)) {
      throw new ShpException("not-found", "The linked Practitioner does not exist.");
    }
    Set<String> held = profiles.activeRoleCodes(practitionerId);
    for (String role : roles) {
      if (CLINICAL_STAFF_ROLES.contains(role) && !held.contains(role)) {
        throw new RoleMismatchException(
            "The Practitioner has no active PractitionerRole with code " + role + ".");
      }
    }
  }

  private static Set<String> normalizeRoles(List<String> roles) {
    if (roles == null || roles.isEmpty()) {
      throw new ShpException("validation-failed", "roles must not be empty");
    }
    Set<String> out = new LinkedHashSet<>();
    for (String r : new ArrayList<>(roles)) {
      String role = r == null ? "" : r.toLowerCase(java.util.Locale.ROOT);
      if (!Roles.ALL.contains(role)) {
        throw new ShpException("validation-failed", "unknown role");
      }
      out.add(role);
    }
    return out;
  }

  private static String decodeToken(String token) {
    try {
      return new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
    } catch (IllegalArgumentException e) {
      throw new ShpException("validation-failed", "_page_token is invalid");
    }
  }

  private static byte[] bytes(String s) {
    return s.getBytes(StandardCharsets.UTF_8);
  }
}
