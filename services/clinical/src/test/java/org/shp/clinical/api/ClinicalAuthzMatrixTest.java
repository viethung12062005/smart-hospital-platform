package org.shp.clinical.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.bson.Document;
import org.hl7.fhir.r4.model.ContactPoint.ContactPointSystem;
import org.hl7.fhir.r4.model.DateType;
import org.hl7.fhir.r4.model.Enumerations.AdministrativeGender;
import org.hl7.fhir.r4.model.HumanName.NameUse;
import org.hl7.fhir.r4.model.Patient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.shp.authz.AuthorizationEngine;
import org.shp.authz.AuthzMatrix;
import org.shp.authz.AuthzRule;
import org.shp.authz.AuthzTestCase;
import org.shp.authz.AuthzTestGenerator;
import org.shp.clinical.ClinicalApiTestBase;
import org.shp.clinical.app.Allergies;
import org.shp.clinical.app.Conditions;
import org.shp.clinical.app.Encounters;
import org.shp.clinical.app.Observations;
import org.shp.clinical.infra.PatientRepository;
import org.shp.web.authz.RequireShpAuthz;
import org.shp.web.authz.UnprotectedEndpoints;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * CLN-26 (NFR-SEC2; 15 §3): the authorization matrix over clinical. Two layers, both driven by
 * {@code contracts/authz/matrix.yaml} so they follow it when it changes:
 *
 * <ul>
 *   <li>every cell of every clinical-data resource (role × condition assignment, from {@link
 *       AuthzTestGenerator}) through the engine the service runs;
 *   <li>every endpoint clinical serves with {@link RequireShpAuthz}, found at runtime, over real
 *       HTTP: each role the matrix gives no rule for that (resource, action) is refused and the
 *       refusal is audited; Admin and DPO are refused on every clinical-data endpoint.
 * </ul>
 *
 * Endpoints added later are picked up without editing this test.
 */
class ClinicalAuthzMatrixTest extends ClinicalApiTestBase {
  static final AuthzMatrix MATRIX = AuthzMatrix.loadEmbedded();

  @Autowired ApplicationContext ctx;
  @Autowired AuthorizationEngine engine;
  @Autowired Clock clock;
  @Autowired PatientRepository patients;

  @Autowired Encounters encounters;
  @Autowired Conditions conditions;
  @Autowired Observations observations;
  @Autowired Allergies allergies;

  String patientId;

  /** The stored fixture of each resource type, by type ({@link #storedResources}). */
  final Map<String, String> stored = new HashMap<>();

  final Map<String, String> accounts = new HashMap<>();

  /** One guarded endpoint as Spring maps it. */
  record Guarded(String method, String pattern, String consumes, RequireShpAuthz meta) {
    @Override
    public String toString() {
      return method + " " + pattern + " (" + meta.resource() + "." + meta.action() + ")";
    }
  }

  /** Every cell of the resources the matrix marks as clinical data. */
  static Stream<AuthzTestCase> clinicalCells() {
    return AuthzTestGenerator.embedded().generate().stream()
        .filter(c -> MATRIX.clinicalByResource().getOrDefault(c.resource(), false));
  }

  @BeforeEach
  void fixtures() {
    Patient p = new Patient();
    p.addName()
        .setUse(NameUse.OFFICIAL)
        .setText("Pham Van Ma")
        .setFamily("Pham")
        .addGiven("Van Ma");
    p.addTelecom().setSystem(ContactPointSystem.PHONE).setValue("+84901000071");
    p.setGender(AdministrativeGender.MALE);
    p.setBirthDateElement(new DateType("1975-04-04"));
    patientId = patients.create(p, "User/test", null).id();
    stored.clear();
  }

  /**
   * Endpoints whose facts come from a stored resource need one to exist, or they are 404 before
   * authorization is asked: one Encounter, Condition, Observation and AllergyIntolerance of the
   * fixture Patient, made once per test.
   */
  void storedResources() {
    if (!stored.isEmpty()) {
      return;
    }
    String encounterId = UUID.randomUUID().toString();
    encounters.create(
        encounterId,
        patientId,
        fhir("Encounter", encounterId),
        new Document("status", "in-progress")
            .append("practitionerId", "prac-cln26-attending")
            .append("appointmentId", UUID.randomUUID().toString()),
        "User/test");
    stored.put("Encounter", encounterId);
    Document ofTheVisit = new Document("encounterId", encounterId);
    String id = UUID.randomUUID().toString();
    stored.put(
        "Condition",
        conditions.create(id, patientId, fhir("Condition", id), ofTheVisit, "User/test").id());
    id = UUID.randomUUID().toString();
    stored.put(
        "Observation",
        observations.create(id, patientId, fhir("Observation", id), ofTheVisit, "User/test").id());
    id = UUID.randomUUID().toString();
    stored.put(
        "AllergyIntolerance",
        allergies
            .create(id, patientId, fhir("AllergyIntolerance", id), new Document(), "User/test")
            .id());
  }

  static Document fhir(String type, String id) {
    return new Document("resourceType", type).append("id", id);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("clinicalCells")
  void everyClinicalCellDecidesAsTheMatrixSays(AuthzTestCase cell) {
    var decision =
        engine.decide(
            AuthzTestGenerator.principal(cell),
            cell.resource(),
            cell.action(),
            AuthzTestGenerator.context(cell, clock.instant()));
    assertThat(decision.allowed()).as(cell.description()).isEqualTo(cell.allowed());
  }

  @Test
  void theMatrixGivesAdminAndDpoNoClinicalData() {
    assertThat(MATRIX.rules())
        .filteredOn(r -> MATRIX.clinicalByResource().getOrDefault(r.resource(), false))
        .allSatisfy(r -> assertThat(r.role()).isNotIn("admin", "dpo"));
  }

  @Test
  void everyEndpointDeclaresItsAuthorizationRule() {
    assertThat(UnprotectedEndpoints.find(ctx)).isEmpty();
  }

  @Test
  void everyGuardedEndpointNamesACellOfTheMatrix() {
    assertThat(guarded()).isNotEmpty();
    assertThat(guarded())
        .allSatisfy(g ->
            assertThat(MATRIX.actionsByResource().getOrDefault(g.meta().resource(), List.of()))
                .as(g.toString())
                .contains(g.meta().action()));
  }

  /** Roles without a rule for the endpoint's (resource, action): refused, and audited. */
  @Test
  void rolesWithoutARuleAreRefusedOnEveryEndpointWithAnAudit() throws Exception {
    List<String> wrong = new ArrayList<>();
    int calls = 0;
    for (Guarded g : guarded()) {
      Set<String> allowedRoles =
          MATRIX.rulesFor(g.meta().resource(), g.meta().action()).stream()
              .map(AuthzRule::role)
              .collect(Collectors.toSet());
      for (String role : MATRIX.roles()) {
        if (allowedRoles.contains(role)) {
          continue;
        }
        int status = callAs(role, g);
        calls++;
        int expected = g.meta().denyAsNotFound() ? 404 : 403;
        if (status != expected) {
          wrong.add(g + " as " + role + " -> " + status + ", expected " + expected);
        }
      }
    }
    assertThat(wrong).isEmpty();
    assertThat(calls).isPositive();
    assertThat(auditRecords())
        .filteredOn(a -> "denied".equals(a.getString("outcome")))
        .hasSize(calls);
  }

  /** NFR-SEC2: Admin gets 403 on all clinical data. */
  @Test
  void adminIsRefusedOnEveryClinicalDataEndpoint() throws Exception {
    List<Guarded> clinical =
        guarded().stream()
            .filter(g -> MATRIX.clinicalByResource().getOrDefault(g.meta().resource(), false))
            .toList();
    assertThat(clinical).isNotEmpty();
    for (Guarded g : clinical) {
      assertThat(callAs("admin", g)).as(g.toString()).isIn(403, 404);
    }
  }

  /** Clinical's own handlers carrying {@link RequireShpAuthz}, one per HTTP method and pattern. */
  List<Guarded> guarded() {
    List<Guarded> out = new ArrayList<>();
    ctx.getBeansOfType(RequestMappingHandlerMapping.class)
        .values()
        .forEach(m ->
            m.getHandlerMethods()
                .forEach((info, hm) -> {
                  RequireShpAuthz meta = hm.getMethodAnnotation(RequireShpAuthz.class);
                  if (meta == null || !hm.getBeanType().getName().startsWith("org.shp.clinical.")) {
                    return;
                  }
                  Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
                  String consumes =
                      info.getConsumesCondition().getConsumableMediaTypes().stream()
                          .findFirst()
                          .map(Object::toString)
                          .orElse("application/json");
                  for (String pattern : info.getPatternValues()) {
                    for (RequestMethod method :
                        methods.isEmpty() ? Set.of(RequestMethod.GET) : methods) {
                      out.add(new Guarded(method.name(), pattern, consumes, meta));
                    }
                  }
                }));
    return out;
  }

  /** One linked account per role (an identity is linked to one account only). */
  String linkedAccount(String role) {
    String sub = "cln26-" + role + "-" + System.nanoTime();
    link(sub, "patient".equals(role) ? "Patient/" + patientId : "Practitioner/prac-cln26-" + role);
    return sub;
  }

  /**
   * Call {@code g} as a linked account with {@code role}. Path variables name the stored fixture of
   * the FHIR type in the path, else the Patient; a search gets {@code patient}; a FHIR create gets
   * a body that references the fixture Patient; and the precondition headers are sent, so that only
   * authorization can refuse the call before the handler.
   */
  int callAs(String role, Guarded g) throws Exception {
    storedResources();
    String sub = accounts.computeIfAbsent(role, this::linkedAccount);
    String[] segments = g.pattern().split("/");
    String type = g.pattern().startsWith("/fhir/") ? segments[2] : null;
    String path = g.pattern().replaceAll("\\{[^}]+}", stored.getOrDefault(type, patientId));
    if ("GET".equals(g.method()) && !g.pattern().contains("{")) {
      path += "?patient=" + patientId;
    }
    String body = null;
    if (Set.of("POST", "PUT", "PATCH").contains(g.method())) {
      // AllergyIntolerance names its patient in "patient", the others in "subject".
      String field = "AllergyIntolerance".equals(type) ? "patient" : "subject";
      body =
          type == null
              ? "{}"
              : "{\"resourceType\":\"%s\",\"%s\":{\"reference\":\"Patient/%s\"}}"
                  .formatted(type, field, patientId);
    }
    return call(
            g.method(),
            path,
            token(sub, role),
            body,
            "Content-Type",
            g.consumes(),
            "If-Match",
            "W/\"1\"",
            "Idempotency-Key",
            UUID.randomUUID().toString())
        .statusCode();
  }
}
