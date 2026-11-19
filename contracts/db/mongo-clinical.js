// Initialize the clinical database (docs/03-architecture/detailed-design/03-db-clinical-document.md §2.2, §2.4).
// Idempotent: re-running is safe. Usage: mongosh "<uri>" contracts/db/mongo-clinical.js
//
// Index NAMES match the ones the clinical service creates at startup (MongoDB refuses the same keys under another name,
// error 85 IndexOptionsConflict); indexes the service does not create use the server's default name.
function ensureCollection(db, name, validator, options) {
  const exists = db.getCollectionNames().includes(name);
  if (!exists) {
    db.createCollection(name, Object.assign({ validator: validator, validationLevel: "strict", validationAction: "error" }, options || {}));
  } else if (validator) {
    db.runCommand({ collMod: name, validator: validator, validationLevel: "strict", validationAction: "error" });
  }
}

const UUID = "^[0-9a-f-]{36}$";
const DATE_OR_NULL = { bsonType: ["date", "null"] };

// The MongoResourceStore shape (03 §2.1): administrative part + FHIR resource + server-written search fields.
// patient: the resource belongs to a patient, so the document carries patientId (shard key).
function resourceValidator(type, patient, searchRequired, resourceProps, resourceRequired) {
  const required = ["_id", "versionId", "lastUpdated", "resource", "search"].concat(patient ? ["patientId"] : []);
  const props = {
    _id: { bsonType: "string", pattern: UUID },
    versionId: { bsonType: ["int", "long"], minimum: 1 },
    lastUpdated: { bsonType: "date" },
    deleted: { bsonType: "bool" },
    resource: { bsonType: "object", required: ["resourceType"].concat(resourceRequired || []),
      properties: Object.assign({ resourceType: { enum: [type] } }, resourceProps || {}) },
    // MongoDB refuses an empty "required" array.
    search: (searchRequired || []).length ? { bsonType: "object", required: searchRequired } : { bsonType: "object" }
  };
  if (patient) props.patientId = { bsonType: "string", pattern: UUID };
  return { $jsonSchema: { bsonType: "object", required: required, properties: props } };
}

const c = db.getSiblingDB("clinical");

// ---------------- resources ----------------
ensureCollection(c, "patients", resourceValidator("Patient", false, ["mrn"], { active: { bsonType: "bool" } }));
c.patients.createIndex({ "search.mrn": 1 }, { unique: true });
c.patients.createIndex({ "search.phone": 1, "search.birthDate": 1 });
c.patients.createIndex({ "search.nationalIdHash": 1 }, { unique: true, partialFilterExpression: { "search.nationalIdHash": { $exists: true } } });
c.patients.createIndex({ "search.nameTokens": 1, "search.birthDate": 1 });

ensureCollection(c, "practitioners", resourceValidator("Practitioner", false, ["staffCode", "active"]));
c.practitioners.createIndex({ "search.staffCode": 1 }, { unique: true, name: "ux_practitioners_staff_code" });
c.practitioners.createIndex({ "search.nameTokens": 1 }, { name: "ix_practitioners_name_tokens" });

ensureCollection(c, "practitioner_roles", resourceValidator("PractitionerRole", false, ["practitionerId", "specialty", "code", "active"],
  {}, ["practitioner"]));
c.practitioner_roles.createIndex({ "search.practitionerId": 1 }, { name: "ix_practitioner_roles_practitioner_id" });
c.practitioner_roles.createIndex({ "search.specialty": 1, "search.code": 1, "search.active": 1 }, { name: "ix_practitioner_roles_specialty_code_active" });

ensureCollection(c, "encounters", resourceValidator("Encounter", true, ["appointmentId", "practitionerId", "status"],
  { status: { enum: ["arrived", "in-progress", "finished", "cancelled"] } }, ["status", "subject"]));
c.encounters.createIndex({ patientId: 1, "search.periodStart": -1 }, { name: "ix_encounters_patient_period_start" });
c.encounters.createIndex({ "search.practitionerId": 1, "search.periodStart": -1 }, { name: "ix_encounters_practitioner_period_start" });
c.encounters.createIndex({ "search.appointmentId": 1 }, { unique: true, name: "ux_encounters_appointment_id" });

ensureCollection(c, "observations", resourceValidator("Observation", true, ["category", "code", "effective"],
  { status: { enum: ["final", "amended", "entered-in-error"] } }, ["status", "code", "subject"]));
c.observations.createIndex({ patientId: 1, "search.category": 1, "search.effective": -1 });
c.observations.createIndex({ "search.encounterId": 1 });

ensureCollection(c, "conditions", resourceValidator("Condition", true, ["code", "recordedDate"], {}, ["code", "subject"]));
c.conditions.createIndex({ patientId: 1, "search.recordedDate": -1 });
c.conditions.createIndex({ "search.encounterId": 1 });

ensureCollection(c, "allergy_intolerances", resourceValidator("AllergyIntolerance", true, [], {}, ["patient"]));
c.allergy_intolerances.createIndex({ patientId: 1 });

ensureCollection(c, "resource_history", { $jsonSchema: { bsonType: "object",
  required: ["_id", "resourceType", "id", "versionId", "operation", "changedBy", "changedAt", "resource"], properties: {
    resourceType: { enum: ["Patient", "Practitioner", "PractitionerRole", "Encounter", "Observation", "Condition", "AllergyIntolerance"] },
    versionId: { bsonType: ["int", "long"], minimum: 1 },
    operation: { enum: ["create", "update", "delete"] },
    changedBy: { bsonType: "string", minLength: 1 },
    changedAt: { bsonType: "date" } } } });
c.resource_history.createIndex({ resourceType: 1, id: 1, versionId: -1 }, { name: "ix_resource_history" });

// ---------------- identity and links ----------------
ensureCollection(c, "identity_links", { $jsonSchema: { bsonType: "object", required: ["_id", "fhirUser", "disabled"], properties: {
  fhirUser: { bsonType: "string", pattern: "^(Patient|Practitioner)/[A-Za-z0-9-]{1,64}$" },
  roles: { bsonType: "array", items: { enum: ["patient", "doctor", "nurse", "admin", "dpo"] } },
  disabled: { bsonType: "bool" },
  versionId: { bsonType: ["int", "long"], minimum: 1 } } } });
c.identity_links.createIndex({ fhirUser: 1 }, { unique: true, name: "ux_fhir_user" });

ensureCollection(c, "profile_links", { $jsonSchema: { bsonType: "object", required: ["_id", "accountSub", "patientId", "relationship", "scope", "status", "createdAt"], properties: {
  relationship: { enum: ["guardian", "proxy"] },
  scope: { enum: ["booking", "records", "full"] },
  status: { enum: ["pending", "active", "revoked", "expired"] },
  establishedBy: { enum: ["otp", "counter", "guardian-declaration"] },
  expiresAt: DATE_OR_NULL, createdAt: { bsonType: "date" }, revokedAt: DATE_OR_NULL } } });
c.profile_links.createIndex({ accountSub: 1, status: 1 }, { name: "ix_account_status" });
c.profile_links.createIndex({ patientId: 1, status: 1 }, { name: "ix_patient_status" });
c.profile_links.createIndex({ accountSub: 1, patientId: 1 }, { unique: true, name: "ux_account_patient" });

ensureCollection(c, "link_attempts", { $jsonSchema: { bsonType: "object", required: ["accountSub", "kind", "at"], properties: {
  kind: { enum: ["claim", "link"] }, at: { bsonType: "date" } } } });
c.link_attempts.createIndex({ accountSub: 1, at: 1 });
c.link_attempts.createIndex({ phoneHash: 1, at: 1 });
c.link_attempts.createIndex({ at: 1 }, { name: "ttl_link_attempts_24h", expireAfterSeconds: 24 * 3600 });

// ---------------- care and break-glass ----------------
ensureCollection(c, "care_relationships", { $jsonSchema: { bsonType: "object", required: ["_id", "practitionerId", "patientId", "sources"], properties: {
  _id: { bsonType: "string", pattern: "^[A-Za-z0-9-]{1,64}\\|[A-Za-z0-9-]{1,64}$" },
  sources: { bsonType: "array", items: { bsonType: "object", required: ["type", "id", "status", "at"], properties: {
    type: { enum: ["appointment", "encounter"] }, at: { bsonType: "date" } } } },
  validUntil: DATE_OR_NULL } } });
c.care_relationships.createIndex({ patientId: 1 }, { name: "ix_care_patient_id" });
c.care_relationships.createIndex({ practitionerId: 1, validUntil: -1 }, { name: "ix_care_practitioner_valid_until" });

ensureCollection(c, "break_glass_sessions", { $jsonSchema: { bsonType: "object", required: ["practitionerId", "patientId", "reason", "startedAt", "expiresAt"], properties: {
  reason: { bsonType: "string", minLength: 20, maxLength: 500 },
  startedAt: { bsonType: "date" }, expiresAt: { bsonType: "date" } } } });
c.break_glass_sessions.createIndex({ practitionerId: 1, patientId: 1, expiresAt: -1 });
c.break_glass_sessions.createIndex({ expiresAt: 1 }, { name: "ttl_break_glass_expires_at", expireAfterSeconds: 0 });

ensureCollection(c, "mrn_counters", { $jsonSchema: { bsonType: "object", required: ["_id", "seq"], properties: {
  _id: { bsonType: "string", pattern: "^[0-9]{4}$" }, seq: { bsonType: ["int", "long"], minimum: 1 } } } });

// ---------------- messaging (13 §1) ----------------
ensureCollection(c, "outbox", { $jsonSchema: { bsonType: "object", required: ["_id", "eventType", "subject", "payload", "occurredAt"],
  properties: { eventType: { bsonType: "string", pattern: "^shp\\.[a-z]+\\.[a-z-]+\\.v[0-9]+$" }, occurredAt: { bsonType: "date" } } } });
c.outbox.createIndex({ occurredAt: 1 }, { name: "ix_outbox_unpublished", partialFilterExpression: { publishedAt: null } });
ensureCollection(c, "inbox", { $jsonSchema: { bsonType: "object", required: ["_id", "processedAt"], properties: { processedAt: { bsonType: "date" } } } });
c.inbox.createIndex({ processedAt: 1 }, { name: "ttl_inbox_14d", expireAfterSeconds: 14 * 24 * 3600 });

print("clinical: " + c.getCollectionNames().length + " collections ready");
