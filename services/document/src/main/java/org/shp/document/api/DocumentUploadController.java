package org.shp.document.api;

import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.shp.authz.Principal;
import org.shp.document.app.UploadService;
import org.shp.web.auth.IdentityInterceptor;
import org.shp.web.authz.RequireShpAuthz;
import org.shp.web.precondition.PreconditionInterceptor;
import org.shp.web.precondition.RequireIdempotencyKey;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** D1 {@code POST /api/v1/documents/uploads}: start an upload and receive a signed link. */
@RestController
public class DocumentUploadController {
  private final UploadService service;

  public DocumentUploadController(UploadService service) {
    this.service = service;
  }

  /**
   * 201 with the ids and the upload link, or 200 when the same Idempotency-Key and body repeat an
   * earlier request (same ids, new link). The Care check and the audit of the decision belong to
   * {@code @RequireShpAuthz}; the file checks (415, 413, 400) run before any link is signed.
   */
  @PostMapping(value = "/api/v1/documents/uploads", produces = MediaType.APPLICATION_JSON_VALUE)
  @RequireIdempotencyKey
  @RequireShpAuthz(resource = "Document", action = "upload", context = "uploadFacts")
  public ResponseEntity<Map<String, Object>> start(HttpServletRequest request) {
    Principal principal = IdentityInterceptor.principal(request);
    UploadRequest body = UploadRequest.from(request);
    var result =
        service.start(
            new UploadService.Command(
                principal.subject(),
                PreconditionInterceptor.idempotencyKey(request),
                body.hash(),
                body.patientId(),
                body.encounterId(),
                body.type(),
                body.title(),
                body.description(),
                body.fileName(),
                body.contentType(),
                body.size(),
                body.sha256(),
                UploadRequest.EXTENSIONS.get(body.contentType())));

    Map<String, Object> upload = new LinkedHashMap<>();
    upload.put("method", "PUT");
    upload.put("url", result.link().url());
    upload.put(
        "headers", Map.of("x-ms-blob-type", "BlockBlob", "Content-Type", result.contentType()));
    upload.put("expiresAt", result.link().expiresAt().toString());
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("uploadId", result.uploadId().toString());
    out.put("documentId", result.documentId().toString());
    out.put("upload", upload);
    return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
        .header("Cache-Control", "no-store")
        .body(out);
  }
}
