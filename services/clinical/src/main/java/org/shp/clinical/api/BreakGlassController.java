package org.shp.clinical.api;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.Map;
import org.bson.Document;
import org.shp.authz.Principal;
import org.shp.authz.ResourceContext;
import org.shp.clinical.app.BreakGlassService;
import org.shp.events.CloudEvents;
import org.shp.web.auth.IdentityInterceptor;
import org.shp.web.authz.RequireShpAuthz;
import org.shp.web.authz.ResourceContextResolver;
import org.shp.web.errors.ShpException;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.CompoundIndexDefinition;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * 09 C20 {@code POST /api/v1/clinical/break-glass} {@code { "patientId", "reason" }} → 201 {@code {
 * "expiresAt" }}. Authorized by {@code BG-START-DOC}; the shared interceptor writes the {@code
 * break-glass-start} audit record (purpose ETREAT) naming the patient, which the audit service uses
 * to notify the DPO.
 */
@RestController
public class BreakGlassController {
  private final BreakGlassService service;

  public BreakGlassController(BreakGlassService service) {
    this.service = service;
  }

  @PostMapping(path = "/api/v1/clinical/break-glass", consumes = "application/json")
  @RequireShpAuthz(resource = "BreakGlass", action = "start", context = "breakGlassFacts")
  public ResponseEntity<Map<String, String>> start(HttpServletRequest req) {
    Principal p = IdentityInterceptor.principal(req);
    String doctor = p.roles().contains("doctor") ? p.fhirUserId() : null;
    JsonNode body = body(req);
    Instant expiresAt = service.start(doctor, text(body, "patientId"), text(body, "reason"));
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(Map.of("expiresAt", expiresAt.toString()));
  }

  /** The JSON body, read once (the facts resolver reads it first, see RequestBodies). */
  static JsonNode body(HttpServletRequest req) {
    try {
      JsonNode node = CloudEvents.JSON.readTree(RequestBodies.read(req));
      if (node != null && node.isObject()) {
        return node;
      }
    } catch (ShpException e) {
      throw e;
    } catch (RuntimeException e) {
      // fall through
    }
    throw new ShpException("validation-failed", "The body must be { \"patientId\", \"reason\" }.");
  }

  static String text(JsonNode body, String field) {
    JsonNode v = body.get(field);
    return v != null && v.isString() ? v.asString() : null;
  }

  @Configuration
  static class Setup {

    /**
     * Facts: the patient, for the audit record. Care is deliberately not reported: C20 answers a
     * doctor who already has care with 409 (from the service) rather than the 403 the {@code
     * not-care} condition would give; every other caller is still decided by the matrix.
     */
    @Bean
    ResourceContextResolver breakGlassFacts() {
      return (req, vars, principal) -> {
        String patientId = text(body(req), "patientId");
        if (patientId == null || !patientId.matches("^[A-Za-z0-9-]{1,64}$")) {
          throw new ShpException("validation-failed", "patientId must be a Patient id.");
        }
        return ResourceContext.builder().patientId(patientId).build();
      };
    }

    /** {@code break_glass_sessions} and its 03 §2.2 indexes (same names as mongo-clinical.js). */
    @Bean
    ApplicationRunner breakGlassCollection(MongoTemplate mongo) {
      return args -> {
        String c = BreakGlassService.COLLECTION;
        if (!mongo.collectionExists(c)) {
          mongo.createCollection(c);
        }
        mongo
            .indexOps(c)
            .createIndex(
                new CompoundIndexDefinition(
                    new Document("practitionerId", 1)
                        .append("patientId", 1)
                        .append("expiresAt", -1)));
        mongo
            .indexOps(c)
            .createIndex(
                new Index()
                    .on("expiresAt", Sort.Direction.ASC)
                    .named("ttl_break_glass_expires_at")
                    .expire(java.time.Duration.ZERO));
      };
    }
  }
}
