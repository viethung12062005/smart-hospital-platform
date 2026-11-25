// Initialize the document database (docs/03-architecture/detailed-design/03-db-clinical-document.md §2.7, §3, §4).
// Idempotent: re-running is safe. Usage: mongosh "<uri>" contracts/db/mongo-document.js
//
// Index NAMES for outbox, inbox and idempotency_keys match the ones libs/java/shp-data-mongo creates at startup
// (MongoDB refuses the same keys under another name, error 85 IndexOptionsConflict).
function ensureCollection(db, name, validator) {
  const options = { validator: validator, validationLevel: "strict", validationAction: "error" };
  if (!db.getCollectionNames().includes(name)) {
    db.createCollection(name, options);
  } else {
    db.runCommand(Object.assign({ collMod: name }, options));
  }
}

const UUID = "^[0-9a-f-]{36}$";
const d = db.getSiblingDB("document");

// ---------------- document_references (03 §3) ----------------
ensureCollection(d, "document_references", { $jsonSchema: { bsonType: "object",
  required: ["_id", "versionId", "lastUpdated", "patientId", "resource", "processing", "search"], properties: {
    _id: { bsonType: "string", pattern: UUID },
    versionId: { bsonType: ["int", "long"], minimum: 1 },
    lastUpdated: { bsonType: "date" },
    patientId: { bsonType: "string", pattern: UUID },
    resource: { bsonType: "object", required: ["resourceType"], properties: { resourceType: { enum: ["DocumentReference"] } } },
    processing: { bsonType: "object", required: ["state"], properties: {
      state: { enum: ["pending-upload", "scanning", "available", "quarantined"] },
      scannedAt: { bsonType: ["date", "null"] } } },
    blob: { bsonType: "object", properties: {
      container: { bsonType: "string" }, path: { bsonType: "string" }, contentType: { bsonType: "string" },
      size: { bsonType: ["int", "long"], minimum: 0 }, sha256: { bsonType: "string" } } },
    releasedToPatient: { bsonType: "bool" },
    search: { bsonType: "object" } } } });
d.document_references.createIndex({ patientId: 1, "search.date": -1 }, { name: "ix_document_references_patient_date" });
d.document_references.createIndex({ "search.encounterId": 1 }, { name: "ix_document_references_encounter_id" });
d.document_references.createIndex({ "processing.state": 1, lastUpdated: 1 }, { name: "ix_document_references_state_updated" });

// ---------------- upload_sessions (03 §3, 10 D1) ----------------
// expiresAt already holds the deadline (creation + 15 minutes, set by the service), so the TTL delay is 0.
ensureCollection(d, "upload_sessions", { $jsonSchema: { bsonType: "object",
  required: ["_id", "documentId", "patientId", "createdBy", "blobPath", "maxSize", "allowedTypes", "state", "expiresAt"], properties: {
    _id: { bsonType: "string", minLength: 1 },
    documentId: { bsonType: "string", pattern: UUID },
    patientId: { bsonType: "string", pattern: UUID },
    createdBy: { bsonType: "string", minLength: 1 },
    blobPath: { bsonType: "string", minLength: 1 },
    maxSize: { bsonType: ["int", "long"], minimum: 1 },
    allowedTypes: { bsonType: "array", minItems: 1, items: { bsonType: "string", minLength: 1 } },
    state: { enum: ["open", "completed", "expired"] },
    expiresAt: { bsonType: "date" } } } });
d.upload_sessions.createIndex({ expiresAt: 1 }, { name: "ttl_upload_sessions_expires_at", expireAfterSeconds: 0 });

// ---------------- resource_history (as in clinical) ----------------
ensureCollection(d, "resource_history", { $jsonSchema: { bsonType: "object",
  required: ["_id", "resourceType", "id", "versionId", "operation", "changedBy", "changedAt", "resource"], properties: {
    resourceType: { enum: ["DocumentReference"] },
    versionId: { bsonType: ["int", "long"], minimum: 1 },
    operation: { enum: ["create", "update", "delete"] },
    changedBy: { bsonType: "string", minLength: 1 },
    changedAt: { bsonType: "date" } } } });
d.resource_history.createIndex({ resourceType: 1, id: 1, versionId: -1 }, { name: "ix_resource_history" });

// ---------------- messaging and idempotency (03 §2.7, 13 §1) ----------------
ensureCollection(d, "outbox", { $jsonSchema: { bsonType: "object", required: ["_id", "eventType", "subject", "payload", "occurredAt"],
  properties: { eventType: { bsonType: "string", pattern: "^shp\\.[a-z]+\\.[a-z-]+\\.v[0-9]+$" }, occurredAt: { bsonType: "date" } } } });
d.outbox.createIndex({ occurredAt: 1 }, { name: "ix_outbox_unpublished", partialFilterExpression: { publishedAt: null } });
ensureCollection(d, "inbox", { $jsonSchema: { bsonType: "object", required: ["_id", "processedAt"], properties: { processedAt: { bsonType: "date" } } } });
d.inbox.createIndex({ processedAt: 1 }, { name: "ttl_inbox_14d", expireAfterSeconds: 14 * 24 * 3600 });
ensureCollection(d, "idempotency_keys", { $jsonSchema: { bsonType: "object", required: ["_id", "operation", "requestHash", "resourceId", "createdAt"],
  properties: { operation: { bsonType: "string", minLength: 1 }, requestHash: { bsonType: "binData" }, createdAt: { bsonType: "date" } } } });
d.idempotency_keys.createIndex({ createdAt: 1 }, { name: "ttl_idempotency_24h", expireAfterSeconds: 24 * 3600 });

print("document: " + d.getCollectionNames().length + " collections ready");
