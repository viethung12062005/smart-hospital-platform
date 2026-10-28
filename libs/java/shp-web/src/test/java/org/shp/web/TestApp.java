package org.shp.web;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.spec.SecretKeySpec;
import org.shp.authz.ResourceContext;
import org.shp.events.audit.InMemoryAuditSink;
import org.shp.security.ShpAuthProperties;
import org.shp.security.ShpJwt;
import org.shp.web.auth.AllowUnlinkedIdentity;
import org.shp.web.auth.IdentityInterceptor;
import org.shp.web.auth.IdentityLink;
import org.shp.web.auth.IdentityLinkClient;
import org.shp.web.authz.RequireShpAuthz;
import org.shp.web.authz.ResourceContextResolver;
import org.shp.web.authz.ShpPublicEndpoint;
import org.shp.web.errors.ShpException;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** Ứng dụng thử: dùng đúng auto-config của shp-web; IdP được thay bằng khóa ký HMAC cục bộ. */
@SpringBootApplication
public class TestApp {
  static final String ISSUER = "https://idp.test/realms/shp";
  static final String AUDIENCE = "shp-api";
  static final byte[] KEY =
      "test-only-signing-key-32-bytes-minimum!!".getBytes(StandardCharsets.UTF_8);
  static final byte[] OTHER_KEY =
      "another-signing-key-32-bytes-minimum!!!!".getBytes(StandardCharsets.UTF_8);

  /** Liên kết giả: test thêm/xóa trực tiếp. */
  static final class FakeLinks implements IdentityLinkClient {
    final Map<String, IdentityLink> links = new ConcurrentHashMap<>();
    int calls;

    @Override
    public Optional<IdentityLink> find(String subject) {
      calls++;
      return Optional.ofNullable(links.get(subject));
    }
  }

  /** Quan hệ điều trị giả: cặp "practitionerId|patientId". */
  static final Set<String> CARE = ConcurrentHashMap.newKeySet();

  @Bean
  FakeLinks fakeLinks() {
    return new FakeLinks();
  }

  @Bean
  InMemoryAuditSink auditSink() {
    return new InMemoryAuditSink();
  }

  @Bean
  JwtDecoder testJwtDecoder(ShpAuthProperties props, Clock clock) {
    var d =
        NimbusJwtDecoder.withSecretKey(new SecretKeySpec(KEY, "HmacSHA256"))
            .macAlgorithm(MacAlgorithm.HS256)
            .build();
    d.setJwtValidator(ShpJwt.validator(props, clock));
    return d;
  }

  @Bean
  ResourceContextResolver patientFacts() {
    return (req, vars, principal) -> {
      String id = vars.get("id");
      return ResourceContext.builder()
          .patientId(id)
          .hasCareRelationship(
              principal.fhirUserId() != null && CARE.contains(principal.fhirUserId() + "|" + id))
          .build();
    };
  }

  static String token(
      List<String> roles,
      String subject,
      List<String> amr,
      String audience,
      Duration lifetime,
      byte[] key) {
    try {
      Instant now = Instant.now();
      var claims =
          new JWTClaimsSet.Builder()
              .issuer(ISSUER)
              .subject(subject)
              .audience(audience)
              .claim("roles", roles)
              .issueTime(Date.from(now.minusSeconds(600)))
              .notBeforeTime(Date.from(now.minusSeconds(600)))
              .expirationTime(Date.from(now.plus(lifetime)));
      if (amr != null) {
        claims.claim("amr", amr);
      }
      var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims.build());
      jwt.sign(new MACSigner(key));
      return jwt.serialize();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  static String token(List<String> roles, String subject, List<String> amr) {
    return token(roles, subject, amr, AUDIENCE, Duration.ofMinutes(10), KEY);
  }

  @RestController
  static class Endpoints {
    @GetMapping("/api/v1/me")
    @ShpPublicEndpoint
    Map<String, Object> me(HttpServletRequest req) {
      var p = IdentityInterceptor.principal(req);
      return Map.of(
          "subject",
          p.subject(),
          "roles",
          new HashSet<>(p.roles()),
          "fhirUserId",
          String.valueOf(p.fhirUserId()));
    }

    @GetMapping("/api/v1/registration")
    @ShpPublicEndpoint
    @AllowUnlinkedIdentity
    Map<String, Object> registration(HttpServletRequest req) {
      return Map.of("linked", IdentityInterceptor.principal(req).fhirUserId() != null);
    }

    @GetMapping("/api/v1/slot")
    @ShpPublicEndpoint
    void slot() {
      throw new ShpException("slot-unavailable");
    }

    @GetMapping("/api/v1/limited")
    @ShpPublicEndpoint
    void limited() {
      throw new ShpException("rate-limited", null, Duration.ofSeconds(30));
    }

    @GetMapping("/fhir/Patient/x")
    @ShpPublicEndpoint
    void fhirMissing() {
      throw new ShpException("not-found");
    }

    @GetMapping("/api/v1/boom")
    @ShpPublicEndpoint
    void boom() {
      throw new IllegalStateException("secret patient Nguyen Van A 0901234567");
    }

    @GetMapping("/api/v1/patients/{id}")
    @RequireShpAuthz(resource = "Patient", action = "read", context = "patientFacts")
    Map<String, String> patient(@PathVariable String id) {
      return Map.of("id", id);
    }

    @GetMapping("/api/v1/hidden/{id}")
    @RequireShpAuthz(
        resource = "Patient",
        action = "read",
        context = "patientFacts",
        denyAsNotFound = true)
    Map<String, String> hidden(@PathVariable String id) {
      return Map.of("id", id);
    }

    @GetMapping("/api/v1/patients")
    @RequireShpAuthz(resource = "Patient", action = "search")
    List<String> search() {
      return List.of();
    }

    @GetMapping("/api/v1/audited-boom")
    @RequireShpAuthz(resource = "Slot", action = "read")
    void auditedBoom() {
      throw new IllegalStateException("db password=hunter2");
    }

    @GetMapping("/api/v1/audited-conflict")
    @RequireShpAuthz(resource = "Slot", action = "read")
    void auditedConflict() {
      throw new ShpException("slot-unavailable");
    }

    @GetMapping("/api/v1/unprotected")
    String unprotected() {
      return "ok";
    }
  }
}
