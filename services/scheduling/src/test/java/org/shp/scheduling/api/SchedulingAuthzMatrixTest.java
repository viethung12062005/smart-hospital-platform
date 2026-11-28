package org.shp.scheduling.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.shp.authz.AuthorizationEngine;
import org.shp.authz.AuthzMatrix;
import org.shp.authz.AuthzRule;
import org.shp.authz.AuthzTestCase;
import org.shp.authz.AuthzTestGenerator;
import org.shp.authz.LinkedProfile;
import org.shp.scheduling.infra.FakePractitionerRoleClient;
import org.shp.security.ShpAuthProperties;
import org.shp.security.ShpJwt;
import org.shp.web.auth.IdentityLink;
import org.shp.web.auth.IdentityLinkClient;
import org.shp.web.authz.RequireShpAuthz;
import org.shp.web.authz.UnprotectedEndpoints;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.json.JsonMapper;

/**
 * SCH-29 (NFR-SEC2; 15 §3): the authorization matrix over scheduling. Two layers, both driven by
 * {@code contracts/authz/matrix.yaml} so they follow it when it changes:
 *
 * <ul>
 *   <li>every cell of the scheduling resources (role × condition assignment, from {@link
 *       AuthzTestGenerator}) through the engine the service runs;
 *   <li>every endpoint scheduling serves with {@link RequireShpAuthz}, found at runtime, over real
 *       HTTP: each role the matrix gives no rule for that (resource, action) is refused and every
 *       refusal is audited.
 * </ul>
 *
 * Endpoints added later are picked up without editing this test.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "shp.auth.authority=" + SchedulingAuthzMatrixTest.TestSecurityConfig.ISSUER,
      "shp.auth.audience=" + SchedulingAuthzMatrixTest.TestSecurityConfig.AUDIENCE
    })
@Testcontainers
@Import(SchedulingAuthzMatrixTest.TestSecurityConfig.class)
class SchedulingAuthzMatrixTest {

  static final AuthzMatrix MATRIX = AuthzMatrix.loadEmbedded();
  static final Set<String> SCHEDULING_RESOURCES =
      Set.of("Schedule", "Slot", "Appointment", "AppointmentHistory");
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Container @ServiceConnection
  static final PostgreSQLContainer DB =
      new PostgreSQLContainer(
          DockerImageName.parse(
                  System.getenv().getOrDefault("SHP_TEST_POSTGRES_IMAGE", "postgres:16-alpine"))
              .asCompatibleSubstituteFor("postgres"));

  @Container @ServiceConnection
  static final RabbitMQContainer BUS =
      new RabbitMQContainer(
          DockerImageName.parse(
                  System.getenv().getOrDefault("SHP_TEST_RABBITMQ_IMAGE", "rabbitmq:4-management"))
              .asCompatibleSubstituteFor("rabbitmq"));

  @TestConfiguration
  static class TestSecurityConfig {
    static final String ISSUER = "https://idp.test/realms/shp";
    static final String AUDIENCE = "shp-api";
    static final byte[] KEY =
        "test-only-signing-key-32-bytes-minimum!!".getBytes(StandardCharsets.UTF_8);

    @Bean
    @Primary
    JwtDecoder testJwtDecoder(ShpAuthProperties props, Clock clock) {
      var d =
          NimbusJwtDecoder.withSecretKey(new SecretKeySpec(KEY, "HmacSHA256"))
              .macAlgorithm(MacAlgorithm.HS256)
              .build();
      d.setJwtValidator(ShpJwt.validator(props, clock));
      return d;
    }

    @Bean
    @Primary
    FakeIdentityLinkClient fakeIdentityLinkClient() {
      return new FakeIdentityLinkClient();
    }

    @Bean
    @Primary
    FakePractitionerRoleClient fakePractitionerRoleClient() {
      return new FakePractitionerRoleClient();
    }

    static class FakeIdentityLinkClient implements IdentityLinkClient {
      final Map<String, IdentityLink> links = new ConcurrentHashMap<>();

      @Override
      public Optional<IdentityLink> find(String subject) {
        return Optional.ofNullable(links.get(subject));
      }
    }
  }

  @Autowired Environment env;
  @Autowired ApplicationContext ctx;
  @Autowired AuthorizationEngine engine;
  @Autowired Clock clock;
  @Autowired JdbcClient jdbc;
  @Autowired TestSecurityConfig.FakeIdentityLinkClient identityLinks;
  @Autowired FakePractitionerRoleClient practitionerRoles;

  /** An existing Schedule, so that fact resolution does not answer 404 before authorization. */
  String scheduleId;

  /** The practitioner who owns the fixture Schedule. */
  UUID schedulePractitioner;

  /** One guarded endpoint as Spring maps it. */
  record Guarded(String method, String pattern, String consumes, RequireShpAuthz meta) {
    @Override
    public String toString() {
      return method + " " + pattern + " (" + meta.resource() + "." + meta.action() + ")";
    }
  }

  /** Every cell of the scheduling resources. */
  static Stream<AuthzTestCase> schedulingCells() {
    return AuthzTestGenerator.embedded().generate().stream()
        .filter(c -> SCHEDULING_RESOURCES.contains(c.resource()));
  }

  @BeforeEach
  void fixtures() throws Exception {
    jdbc.sql(
            "TRUNCATE scheduling.appointment, scheduling.slot, scheduling.schedule_template, scheduling.schedule, scheduling.resource_history, scheduling.outbox, scheduling.idempotency_key CASCADE")
        .update();
    identityLinks.links.clear();
    practitionerRoles.clear();
    UUID practitioner = UUID.randomUUID();
    schedulePractitioner = practitioner;
    practitionerRoles.register(practitioner, "cardiology");
    String body =
        """
        {
          "resourceType": "Schedule",
          "active": true,
          "actor": [{ "reference": "Practitioner/%s" }],
          "specialty": [{ "coding": [{ "code": "cardiology" }] }]
        }
        """
            .formatted(practitioner);
    var res =
        send(
            HttpRequest.newBuilder(url("/fhir/Schedule"))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor("nurse"))
                .header(HttpHeaders.CONTENT_TYPE, "application/fhir+json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build());
    assertThat(res.statusCode()).isEqualTo(201);
    scheduleId = JSON.readTree(res.body()).path("id").asString().replace("Schedule/", "");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("schedulingCells")
  void everySchedulingCellDecidesAsTheMatrixSays(AuthzTestCase cell) {
    var decision =
        engine.decide(
            AuthzTestGenerator.principal(cell),
            cell.resource(),
            cell.action(),
            AuthzTestGenerator.context(cell, clock.instant()));
    assertThat(decision.allowed()).as(cell.description()).isEqualTo(cell.allowed());
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

  /**
   * Roles without a rule for the endpoint's (resource, action): refused, and the refusal audited.
   */
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
        int before = deniedAudits();
        int status = callAs(role, g);
        calls++;
        int expected = g.meta().denyAsNotFound() ? 404 : 403;
        if (status != expected) {
          wrong.add(g + " as " + role + " -> " + status + ", expected " + expected);
        }
        if (deniedAudits() <= before) {
          wrong.add(g + " as " + role + " -> refusal not audited");
        }
      }
    }
    assertThat(wrong).isEmpty();
    assertThat(calls).isPositive();
  }

  /**
   * Conditional denials (15 §3): a role that has a rule for the endpoint, but only under a
   * condition (own, linked, self-practitioner), is refused over HTTP when the condition fails, with
   * the status the design gives, and every refusal is audited. The same request by the identity
   * that meets the condition is not refused, so the refusal is the condition's and not a missing
   * fixture's.
   *
   * <p>Every (endpoint, role) pair whose only rules carry a condition must be covered by a case
   * below or listed in {@link #NARROWED_NOT_DENIED} with its reason, so an endpoint added later
   * fails this test until its conditional denial is exercised.
   */
  @Test
  void conditionalRulesAreRefusedOverHttpWhenTheConditionFailsWithAnAudit() throws Exception {
    UUID patientA = UUID.randomUUID();
    UUID patientB = UUID.randomUUID();
    UUID appointmentOfA = insertAppointment(patientA, schedulePractitioner);
    String foreignDoctor = UUID.randomUUID().toString();
    // A patient whose linked profile is a third person: the link must not open B's data either.
    List<LinkedProfile> linkedToSomeoneElse =
        List.of(new LinkedProfile(UUID.randomUUID().toString(), "guardian", "full", null));

    List<ConditionalCase> cases =
        List.of(
            new ConditionalCase(
                "GET /fhir/Appointment/{id}",
                "patient",
                "own: another patient's appointment",
                404,
                () ->
                    request(
                        "GET",
                        "/fhir/Appointment/" + appointmentOfA,
                        tokenFor("patient", patientB.toString(), List.of()),
                        null),
                () ->
                    request(
                        "GET",
                        "/fhir/Appointment/" + appointmentOfA,
                        tokenFor("patient", patientA.toString(), List.of()),
                        null)),
            new ConditionalCase(
                "GET /fhir/Appointment/{id}",
                "patient",
                "linked: the token's linked profile is someone else",
                404,
                () ->
                    request(
                        "GET",
                        "/fhir/Appointment/" + appointmentOfA,
                        tokenFor("patient", patientB.toString(), linkedToSomeoneElse),
                        null),
                null),
            new ConditionalCase(
                "GET /fhir/Appointment/{id}",
                "doctor",
                "self-practitioner: another practitioner's appointment",
                404,
                () ->
                    request(
                        "GET",
                        "/fhir/Appointment/" + appointmentOfA,
                        tokenFor("doctor", foreignDoctor, List.of()),
                        null),
                () ->
                    request(
                        "GET",
                        "/fhir/Appointment/" + appointmentOfA,
                        tokenFor("doctor", schedulePractitioner.toString(), List.of()),
                        null)),
            new ConditionalCase(
                "POST /fhir/Appointment/$book",
                "patient",
                "own: booking for another patient",
                403,
                () ->
                    request(
                        "POST",
                        "/fhir/Appointment/$book",
                        tokenFor("patient", patientB.toString(), List.of()),
                        bookingBody(patientA)),
                () ->
                    request(
                        "POST",
                        "/fhir/Appointment/$book",
                        tokenFor("patient", patientA.toString(), List.of()),
                        bookingBody(patientA))),
            new ConditionalCase(
                "POST /fhir/Appointment/$book",
                "patient",
                "linked: booking for a patient who is not the token's linked profile",
                403,
                () ->
                    request(
                        "POST",
                        "/fhir/Appointment/$book",
                        tokenFor("patient", patientB.toString(), linkedToSomeoneElse),
                        bookingBody(patientA)),
                null),
            new ConditionalCase(
                "GET /fhir/Schedule/{id}",
                "doctor",
                "self-practitioner: a schedule the doctor does not own",
                403,
                () ->
                    request(
                        "GET",
                        "/fhir/Schedule/" + scheduleId,
                        tokenFor("doctor", foreignDoctor, List.of()),
                        null),
                () ->
                    request(
                        "GET",
                        "/fhir/Schedule/" + scheduleId,
                        tokenFor("doctor", schedulePractitioner.toString(), List.of()),
                        null)),
            new ConditionalCase(
                "GET /api/v1/scheduling/schedules/{id}/templates",
                "doctor",
                "self-practitioner: the templates of a schedule the doctor does not own",
                403,
                () ->
                    request(
                        "GET",
                        "/api/v1/scheduling/schedules/" + scheduleId + "/templates",
                        tokenFor("doctor", foreignDoctor, List.of()),
                        null),
                () ->
                    request(
                        "GET",
                        "/api/v1/scheduling/schedules/" + scheduleId + "/templates",
                        tokenFor("doctor", schedulePractitioner.toString(), List.of()),
                        null)));

    Set<String> required = new java.util.TreeSet<>();
    for (Guarded g : guarded()) {
      List<AuthzRule> rules = MATRIX.rulesFor(g.meta().resource(), g.meta().action());
      for (String role : rules.stream().map(AuthzRule::role).collect(Collectors.toSet())) {
        boolean onlyConditional =
            rules.stream().filter(r -> r.role().equals(role)).noneMatch(r -> r.when().isEmpty());
        if (onlyConditional) {
          required.add(g.method() + " " + g.pattern() + " as " + role);
        }
      }
    }
    Set<String> covered =
        cases.stream().map(c -> c.endpoint() + " as " + c.role()).collect(Collectors.toSet());
    assertThat(required)
        .as("conditional (endpoint, role) pairs without a denial case")
        .allMatch(k -> covered.contains(k) || NARROWED_NOT_DENIED.containsKey(k));
    assertThat(covered)
        .as("cases for pairs the matrix no longer makes conditional")
        .allMatch(required::contains);

    List<String> wrong = new ArrayList<>();
    for (ConditionalCase c : cases) {
      String label = c.endpoint() + " as " + c.role() + " (" + c.failing() + ")";
      int before = deniedAudits();
      int status = send(c.denied().build()).statusCode();
      if (status != c.expected()) {
        wrong.add(label + " -> " + status + ", expected " + c.expected());
      }
      if (deniedAudits() <= before) {
        wrong.add(label + " -> refusal not audited");
      }
      if (c.owner() != null) {
        int ownerBefore = deniedAudits();
        int ownerStatus = send(c.owner().build()).statusCode();
        if (ownerStatus == 403
            || ownerStatus == 401
            || deniedAudits() > ownerBefore
            || (c.expected() == 404 && ownerStatus != 200)) {
          wrong.add(label + " -> the identity that meets the condition got " + ownerStatus);
        }
      }
    }
    assertThat(wrong).isEmpty();
  }

  /**
   * Conditional pairs where the design narrows the request to the caller instead of refusing it, so
   * there is no denial to provoke.
   */
  static final Map<String, String> NARROWED_NOT_DENIED =
      Map.of(
          "GET /fhir/Appointment as patient",
          "S13 forces the patient filter to the caller (08 S13); it never refuses",
          "GET /fhir/Appointment as doctor",
          "S13 forces the practitioner filter to the caller (08 S13); it never refuses");

  /** One conditional denial: the request that fails the condition and the one that meets it. */
  record ConditionalCase(
      String endpoint,
      String role,
      String failing,
      int expected,
      RequestFactory denied,
      RequestFactory owner) {}

  @FunctionalInterface
  interface RequestFactory {
    HttpRequest build() throws Exception;
  }

  HttpRequest request(String method, String path, String token, String body) {
    var b =
        HttpRequest.newBuilder(url(path))
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
            .header("Idempotency-Key", UUID.randomUUID().toString());
    if (body == null) {
      return b.method(method, HttpRequest.BodyPublishers.noBody()).build();
    }
    return b.header(HttpHeaders.CONTENT_TYPE, "application/fhir+json")
        .method(method, HttpRequest.BodyPublishers.ofString(body))
        .build();
  }

  static String bookingBody(UUID patient) {
    return "{\"resourceType\":\"Parameters\",\"parameter\":["
        + "{\"name\":\"slot\",\"valueReference\":{\"reference\":\"Slot/"
        + UUID.randomUUID()
        + "\"}},"
        + "{\"name\":\"patient\",\"valueReference\":{\"reference\":\"Patient/"
        + patient
        + "\"}}]}";
  }

  /** A booked appointment of {@code patient} on the fixture Schedule, one day ahead. */
  UUID insertAppointment(UUID patient, UUID practitioner) {
    UUID slot = UUID.randomUUID();
    UUID appointment = UUID.randomUUID();
    Instant start = clock.instant().plus(java.time.Duration.ofDays(1));
    Instant end = start.plus(java.time.Duration.ofMinutes(15));
    jdbc.sql(
            "INSERT INTO scheduling.slot (id, last_updated, schedule_id, practitioner_id, specialty, status, start_at, end_at, content) VALUES (?, now(), ?, ?, 'cardiology', 'busy', ?, ?, '{}'::jsonb)")
        .params(
            slot,
            UUID.fromString(scheduleId),
            practitioner,
            java.sql.Timestamp.from(start),
            java.sql.Timestamp.from(end))
        .update();
    jdbc.sql(
            "INSERT INTO scheduling.appointment (id, last_updated, status, slot_id, patient_id, practitioner_id, specialty, start_at, end_at, channel, booked_by, content) VALUES (?, now(), 'booked', ?, ?, ?, 'cardiology', ?, ?, 'web', 'sch29-seed', '{}'::jsonb)")
        .params(
            appointment,
            slot,
            patient,
            practitioner,
            java.sql.Timestamp.from(start),
            java.sql.Timestamp.from(end))
        .update();
    return appointment;
  }

  /** NFR-SEC2: the DPO has no rule on any scheduling resource. */
  @Test
  void dpoHasNoRuleOnAnySchedulingResource() {
    assertThat(MATRIX.rules())
        .filteredOn(r -> SCHEDULING_RESOURCES.contains(r.resource()))
        .allSatisfy(r -> assertThat(r.role()).isNotEqualTo("dpo"));
  }

  int deniedAudits() {
    return jdbc.sql(
            "SELECT count(*) FROM scheduling.outbox WHERE event_type = 'shp.audit.recorded.v1' AND payload->'data'->>'outcome' = 'denied'")
        .query(Integer.class)
        .single();
  }

  /**
   * Scheduling's own handlers carrying {@link RequireShpAuthz}, one per HTTP method and pattern.
   */
  List<Guarded> guarded() {
    List<Guarded> out = new ArrayList<>();
    ctx.getBeansOfType(RequestMappingHandlerMapping.class)
        .values()
        .forEach(m ->
            m.getHandlerMethods()
                .forEach((info, hm) -> {
                  RequireShpAuthz meta = hm.getMethodAnnotation(RequireShpAuthz.class);
                  if (meta == null
                      || !hm.getBeanType().getName().startsWith("org.shp.scheduling.")) {
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

  /**
   * Call {@code g} as a linked account with {@code role}. A path variable names the fixture
   * Schedule under {@code /schedules/}, else a random id; precondition headers and a minimal body
   * are sent so that only authorization can refuse the call before the handler.
   */
  int callAs(String role, Guarded g) throws Exception {
    String lower = g.pattern().toLowerCase(java.util.Locale.ROOT);
    String id =
        lower.contains("/schedules/") || lower.contains("/schedule/")
            ? scheduleId
            : UUID.randomUUID().toString();
    String path = g.pattern().replaceAll("\\{[^}]+}", id);
    var b =
        HttpRequest.newBuilder(url(path))
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(role))
            .header(HttpHeaders.CONTENT_TYPE, g.consumes())
            .header(HttpHeaders.IF_MATCH, "W/\"1\"")
            .header("Idempotency-Key", UUID.randomUUID().toString());
    boolean hasBody = Set.of("POST", "PUT", "PATCH").contains(g.method());
    b.method(
        g.method(),
        hasBody
            ? HttpRequest.BodyPublishers.ofString(bodyFor(g))
            : HttpRequest.BodyPublishers.noBody());
    return send(b.build()).statusCode();
  }

  /**
   * The facts of an authorization decision come from the request, so a booking carries a slot and a
   * patient reference; every other call sends an empty object.
   */
  String bodyFor(Guarded g) {
    if (!g.pattern().contains("$book")) {
      return "{}";
    }
    return "{\"resourceType\":\"Parameters\",\"parameter\":["
        + "{\"name\":\"slot\",\"valueReference\":{\"reference\":\"Slot/"
        + UUID.randomUUID()
        + "\"}},"
        + "{\"name\":\"patient\",\"valueReference\":{\"reference\":\"Patient/"
        + UUID.randomUUID()
        + "\"}}]}";
  }

  String tokenFor(String role) {
    return tokenFor(role, UUID.randomUUID().toString(), List.of());
  }

  /** A token for a linked account with {@code role}, linked to {@code fhirUserId}. */
  String tokenFor(String role, String fhirUserId, List<LinkedProfile> linkedProfiles) {
    String sub = "sch29-" + role + "-" + UUID.randomUUID();
    String type = "patient".equals(role) ? "Patient" : "Practitioner";
    identityLinks.links.put(sub, new IdentityLink(type, fhirUserId, false, linkedProfiles));
    try {
      Instant now = Instant.now();
      var claims =
          new JWTClaimsSet.Builder()
              .issuer(TestSecurityConfig.ISSUER)
              .subject(sub)
              .audience(TestSecurityConfig.AUDIENCE)
              .claim("roles", List.of(role))
              .claim("amr", List.of("pwd", "mfa"))
              .issueTime(Date.from(now.minusSeconds(60)))
              .notBeforeTime(Date.from(now.minusSeconds(60)))
              .expirationTime(Date.from(now.plusSeconds(600)));
      var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims.build());
      jwt.sign(new MACSigner(TestSecurityConfig.KEY));
      return jwt.serialize();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  URI url(String path) {
    return URI.create("http://localhost:" + env.getProperty("local.server.port") + path);
  }

  HttpResponse<String> send(HttpRequest req) throws Exception {
    try (var client = HttpClient.newHttpClient()) {
      return client.send(req, HttpResponse.BodyHandlers.ofString());
    }
  }
}
