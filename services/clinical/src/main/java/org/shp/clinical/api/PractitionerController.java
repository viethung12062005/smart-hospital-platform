package org.shp.clinical.api;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.hl7.fhir.r4.model.Resource;
import org.shp.clinical.app.PractitionerService;
import org.shp.clinical.app.PractitionerService.Page;
import org.shp.clinical.app.PractitionerService.Stored;
import org.shp.fhir.FhirJson;
import org.shp.fhir.search.PageToken;
import org.shp.fhir.search.PageTokens;
import org.shp.fhir.search.SearchBundles;
import org.shp.fhir.search.SearchParser;
import org.shp.fhir.search.SearchQuery;
import org.shp.web.auth.IdentityInterceptor;
import org.shp.web.authz.RequireShpAuthz;
import org.shp.web.authz.ShpPublicEndpoint;
import org.shp.web.errors.ShpException;
import org.shp.web.precondition.ETags;
import org.shp.web.precondition.PreconditionInterceptor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * 09 C9 (doctor directory, any signed-in user) and C10 (staff management, Admin). Staff management
 * is authorized by the {@code User: manage} rule of the matrix (USER-MANAGE-ADM), so its audit
 * records name {@code User/<id>}.
 */
@RestController
public class PractitionerController {
  private static final MediaType FHIR = MediaType.parseMediaType(FhirJson.MEDIA_TYPE);
  private static final int MAX_COUNT = SearchParser.MAX_COUNT;
  private static final String KEY_ID = "id";

  private final PractitionerService service;
  private final PageTokens pageTokens;
  private final SearchParser parser = SearchParser.standard();

  public PractitionerController(PractitionerService service, PageTokens pageTokens) {
    this.service = service;
    this.pageTokens = pageTokens;
  }

  // ---------- C9: directory ----------

  @GetMapping("/fhir/Practitioner")
  @ShpPublicEndpoint
  public ResponseEntity<String> directory(HttpServletRequest req) {
    SearchQuery q = parse("Practitioner", req);
    var page = service.directory(q.count(), afterId(q));
    return bundle(req, page, q);
  }

  @GetMapping("/fhir/Practitioner/{id}")
  @ShpPublicEndpoint
  public ResponseEntity<String> read(@PathVariable String id) {
    return resource(HttpStatus.OK, service.readPractitioner(id), null);
  }

  @GetMapping("/fhir/PractitionerRole")
  @ShpPublicEndpoint
  public ResponseEntity<String> roles(HttpServletRequest req) {
    SearchQuery q = parse("PractitionerRole", req);
    var page = service.roles(q.count(), afterId(q));
    return bundle(req, page, q);
  }

  // ---------- C10: staff management ----------

  @PostMapping(
      path = "/fhir/Practitioner",
      consumes = {FhirJson.MEDIA_TYPE, "application/json"})
  @RequireShpAuthz(resource = "User", action = "manage")
  public ResponseEntity<String> create(@RequestBody String body, HttpServletRequest req) {
    var s = service.createPractitioner(body, changedBy(req));
    return resource(HttpStatus.CREATED, s, "Practitioner");
  }

  @PutMapping(
      path = "/fhir/Practitioner/{id}",
      consumes = {FhirJson.MEDIA_TYPE, "application/json"})
  @RequireShpAuthz(resource = "User", action = "manage")
  public ResponseEntity<String> update(
      @PathVariable String id, @RequestBody String body, HttpServletRequest req) {
    var s = service.updatePractitioner(id, body, expectedVersion(req), changedBy(req));
    return resource(HttpStatus.OK, s, null);
  }

  @PostMapping(
      path = "/fhir/PractitionerRole",
      consumes = {FhirJson.MEDIA_TYPE, "application/json"})
  @RequireShpAuthz(resource = "User", action = "manage")
  public ResponseEntity<String> createRole(@RequestBody String body, HttpServletRequest req) {
    var s = service.createRole(body, changedBy(req));
    return resource(HttpStatus.CREATED, s, "PractitionerRole");
  }

  @PutMapping(
      path = "/fhir/PractitionerRole/{id}",
      consumes = {FhirJson.MEDIA_TYPE, "application/json"})
  @RequireShpAuthz(resource = "User", action = "manage")
  public ResponseEntity<String> updateRole(
      @PathVariable String id, @RequestBody String body, HttpServletRequest req) {
    var s = service.updateRole(id, body, expectedVersion(req), changedBy(req));
    return resource(HttpStatus.OK, s, null);
  }

  // ---------- helpers ----------

  /**
   * Search parameters follow 07 §4: {@code _count} (1..100) and keyset {@code _page_token};
   * anything else is a 400 {@code unsupported-parameter}.
   */
  private SearchQuery parse(String resourceType, HttpServletRequest req) {
    Map<String, List<String>> params = params(req);
    SearchQuery q = parser.parse(resourceType, params);
    // The parser clamps _count above the maximum; 07 §4 says outside 1..100 is a 400.
    if (params.containsKey("_count")
        && Integer.parseInt(params.get("_count").getFirst()) > MAX_COUNT) {
      throw new ShpException("validation-failed");
    }
    return q;
  }

  /** The id of the last entry of the previous page, from the signed keyset token. */
  private String afterId(SearchQuery q) {
    if (q.pageToken() == null) {
      return null;
    }
    PageToken token = pageTokens.decode(q.pageToken(), q);
    if (token.keyset().size() != 1 || !KEY_ID.equals(token.keyset().getFirst().name())) {
      throw new ShpException("validation-failed");
    }
    return token.keyset().getFirst().value();
  }

  private static Map<String, List<String>> params(HttpServletRequest req) {
    return SearchParser.fromServlet(req.getParameterMap());
  }

  private ResponseEntity<String> bundle(
      HttpServletRequest req, Page<? extends Resource> page, SearchQuery q) {
    String base = ServletUriComponentsBuilder.fromRequestUri(req).replaceQuery(null).toUriString();
    String nextToken =
        page.nextAfterId() == null
            ? null
            : pageTokens.encode(q, List.of(new PageToken.Key(KEY_ID, page.nextAfterId())));
    return ResponseEntity.ok()
        .contentType(FHIR)
        .body(
            FhirJson.serialize(
                SearchBundles.searchset(base, params(req), page.items(), nextToken)));
  }

  private static ResponseEntity<String> resource(
      HttpStatus status, Stored<? extends Resource> s, String createdType) {
    var builder =
        ResponseEntity.status(status)
            .contentType(FHIR)
            .header(HttpHeaders.ETAG, ETags.format(s.version()));
    if (createdType != null) {
      String id = s.resource().getIdElement().getIdPart();
      builder.location(URI.create("/fhir/" + createdType + "/" + id + "/_history/" + s.version()));
    }
    return builder.body(FhirJson.serialize(s.resource()));
  }

  private static String changedBy(HttpServletRequest req) {
    return "User/" + IdentityInterceptor.principal(req).subject();
  }

  private static int expectedVersion(HttpServletRequest req) {
    long version = PreconditionInterceptor.expectedVersion(req);
    if (version > Integer.MAX_VALUE) {
      throw new ShpException("version-conflict");
    }
    return (int) version;
  }
}
