package org.shp.document.app;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bson.Document;
import org.shp.data.mongo.MongoIdempotency;
import org.shp.data.mongo.MongoMessaging;
import org.shp.document.config.BlobStoreProperties;
import org.shp.document.domain.BlobStore;
import org.shp.document.domain.SignedUrl;
import org.shp.events.Uuids;
import org.shp.web.errors.ShpException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * D1 (10 §2): claim the Idempotency-Key, create the Document ({@code preliminary}, {@code
 * pending-upload}) and its upload session in one transaction, then sign the upload link. A replay
 * of the same key and body returns the same ids with a fresh link.
 */
@Service
public class UploadService {
  /** Operation name stored with the idempotency key (03 §2.7). */
  public static final String OPERATION = "create-upload-session";

  /** BR: at most 30 uploads per user per hour (10 §2). */
  public static final int MAX_UPLOADS_PER_HOUR = 30;

  static final Duration SESSION_TTL = Duration.ofMinutes(15);
  static final String DOCUMENTS = "document_references";
  static final String SESSIONS = "upload_sessions";

  /** What the caller asked for, already validated. */
  public record Command(
      String subject,
      String idempotencyKey,
      byte[] requestHash,
      UUID patientId,
      UUID encounterId,
      String type,
      String title,
      String description,
      String fileName,
      String contentType,
      long size,
      String sha256,
      String extension) {}

  /** The answer: ids, the signed link and whether an earlier request is being replayed. */
  public record Result(
      UUID uploadId, UUID documentId, SignedUrl link, String contentType, boolean replayed) {}

  private final MongoTemplate mongo;
  private final TransactionTemplate tx;
  private final MongoIdempotency idempotency;
  private final BlobStore blobs;
  private final BlobStoreProperties props;
  private final Clock clock;

  public UploadService(
      MongoTemplate mongo,
      TransactionTemplate tx,
      MongoIdempotency idempotency,
      BlobStore blobs,
      BlobStoreProperties props,
      Clock clock) {
    this.mongo = mongo;
    this.tx = tx;
    this.idempotency = idempotency;
    this.blobs = blobs;
    this.props = props;
    this.clock = clock;
  }

  public Result start(Command c) {
    Created created = MongoMessaging.inTransaction(tx, () -> claimAndCreate(c));
    SignedUrl link = blobs.uploadUrl(props.documentsContainer(), created.blobPath());
    return new Result(
        created.uploadId(), created.documentId(), link, c.contentType(), created.replay());
  }

  private record Created(UUID uploadId, UUID documentId, String blobPath, boolean replay) {}

  private Created claimAndCreate(Command c) {
    Instant now = clock.instant();
    var claim = idempotency.claim(c.subject(), c.idempotencyKey(), OPERATION, c.requestHash());
    UUID documentId = claim.resourceId();
    if (claim.replay()) {
      return replay(documentId, c, now);
    }
    long recent =
        mongo.count(
            Query.query(
                Criteria.where("createdBy")
                    .is(c.subject())
                    .and("createdAt")
                    .gte(Date.from(now.minus(Duration.ofHours(1))))),
            DOCUMENTS);
    if (recent >= MAX_UPLOADS_PER_HOUR) {
      throw new ShpException("rate-limited", null, Duration.ofMinutes(5));
    }
    String path = blobPath(c, documentId);
    mongo.insert(document(c, documentId, path, now), DOCUMENTS);
    UUID uploadId = Uuids.v7(clock);
    mongo.insert(session(uploadId, documentId, c, path, now), SESSIONS);
    return new Created(uploadId, documentId, path, false);
  }

  /** Same key and body: the Document and session of the first request, link signed again. */
  private Created replay(UUID documentId, Command c, Instant now) {
    Document doc = mongo.findById(documentId.toString(), Document.class, DOCUMENTS);
    if (doc == null) {
      throw new ShpException("conflict");
    }
    String path = doc.get("blob", Document.class).getString("path");
    Document s =
        mongo.findOne(
            Query.query(Criteria.where("documentId").is(documentId.toString())),
            Document.class,
            SESSIONS);
    UUID uploadId;
    if (s == null) {
      uploadId = Uuids.v7(clock);
      mongo.insert(session(uploadId, documentId, c, path, now), SESSIONS);
    } else {
      uploadId = UUID.fromString(s.getString("_id"));
    }
    return new Created(uploadId, documentId, path, true);
  }

  static String blobPath(Command c, UUID documentId) {
    return "patients/" + c.patientId() + "/" + documentId + "/v1/original." + c.extension();
  }

  private Document session(UUID uploadId, UUID documentId, Command c, String path, Instant now) {
    return new Document("_id", uploadId.toString())
        .append("documentId", documentId.toString())
        .append("patientId", c.patientId().toString())
        .append("createdBy", c.subject())
        .append("blobPath", path)
        .append("maxSize", c.size())
        .append("allowedTypes", List.of(c.contentType()))
        .append("state", "open")
        .append("expiresAt", Date.from(now.plus(SESSION_TTL)));
  }

  private Document document(Command c, UUID documentId, String path, Instant now) {
    Map<String, Object> resource = new LinkedHashMap<>();
    resource.put("resourceType", "DocumentReference");
    resource.put("id", documentId.toString());
    resource.put("status", "current");
    resource.put("docStatus", "preliminary");
    resource.put(
        "type", Map.of("coding", List.of(Map.of("system", "http://loinc.org", "code", c.type()))));
    resource.put("subject", Map.of("reference", "Patient/" + c.patientId()));
    if (c.title() != null) {
      resource.put("description", c.title());
    }
    Document search =
        new Document("type", c.type())
            .append("date", Date.from(now))
            .append("authorId", c.subject());
    if (c.encounterId() != null) {
      search.append("encounterId", c.encounterId().toString());
    }
    Document doc =
        new Document("_id", documentId.toString())
            .append("versionId", 1L)
            .append("lastUpdated", Date.from(now))
            .append("patientId", c.patientId().toString())
            .append("resource", new Document(resource))
            .append("processing", new Document("state", "pending-upload"))
            .append(
                "blob",
                new Document("container", props.documentsContainer())
                    .append("path", path)
                    .append("contentType", c.contentType())
                    .append("size", c.size())
                    .append("sha256", c.sha256()))
            .append("releasedToPatient", false)
            .append("search", search)
            .append("createdBy", c.subject())
            .append("createdAt", Date.from(now));
    if (c.description() != null) {
      doc.append("note", c.description());
    }
    return doc;
  }
}
