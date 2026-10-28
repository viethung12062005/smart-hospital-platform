// Khởi tạo database notification và ml (docs/03-architecture/detailed-design/04-db-notification-ml.md, ADR-0014).
// Idempotent: chạy lại không lỗi. Dùng: mongosh "<uri>" contracts/db/mongo-notification-ml.js
function ensureCollection(db, name, validator, options) {
  const exists = db.getCollectionNames().includes(name);
  if (!exists) {
    db.createCollection(name, Object.assign({ validator: validator, validationLevel: "strict" }, options || {}));
  } else if (validator) {
    db.runCommand({ collMod: name, validator: validator, validationLevel: "strict" });
  }
}

function messaging(db) {
  ensureCollection(db, "outbox", { $jsonSchema: { bsonType: "object", required: ["_id", "eventType", "subject", "payload", "occurredAt"],
    properties: { eventType: { bsonType: "string", pattern: "^shp\\.[a-z]+\\.[a-z-]+\\.v[0-9]+$" }, occurredAt: { bsonType: "date" } } } });
  db.outbox.createIndex({ occurredAt: 1 }, { name: "ix_outbox_unpublished", partialFilterExpression: { publishedAt: null } });
  ensureCollection(db, "inbox", { $jsonSchema: { bsonType: "object", required: ["_id", "processedAt"], properties: { processedAt: { bsonType: "date" } } } });
  db.inbox.createIndex({ processedAt: 1 }, { name: "ttl_inbox_14d", expireAfterSeconds: 14 * 24 * 3600 });
}

// ---------------- notification ----------------
const ntf = db.getSiblingDB("notification");
ensureCollection(ntf, "preferences", { $jsonSchema: { bsonType: "object", required: ["_id", "channels", "locale", "updatedAt"], properties: {
  channels: { bsonType: "array", minItems: 1, uniqueItems: true, items: { enum: ["push", "sms", "email"] } },
  locale: { enum: ["vi", "en"] },
  quietStart: { bsonType: "string", pattern: "^([01][0-9]|2[0-3]):[0-5][0-9]$" },
  quietEnd: { bsonType: "string", pattern: "^([01][0-9]|2[0-3]):[0-5][0-9]$" } } } });
ensureCollection(ntf, "device_tokens", { $jsonSchema: { bsonType: "object", required: ["_id", "ownerRef", "platform", "token", "createdAt", "lastSeenAt"], properties: {
  ownerRef: { bsonType: "string", pattern: "^(Patient|Practitioner)/[0-9a-f-]{36}$" }, platform: { enum: ["ios", "android", "web"] } } } });
ntf.device_tokens.createIndex({ token: 1 }, { unique: true });
ntf.device_tokens.createIndex({ ownerRef: 1 });
ensureCollection(ntf, "reminders", { $jsonSchema: { bsonType: "object", required: ["_id", "subjectRef", "patientId", "kind", "dueAt", "status", "attempts", "createdAt", "updatedAt"], properties: {
  subjectRef: { bsonType: "string", pattern: "^(Appointment|DocumentReference)/[0-9a-f-]{36}$" },
  kind: { enum: ["booking-confirmation", "t-24h", "t-6h-risk", "t-2h", "cancellation", "result-available"] },
  status: { enum: ["scheduled", "sending", "sent", "cancelled", "dead-lettered"] },
  attempts: { bsonType: "int", minimum: 0, maximum: 5 },
  dueAt: { bsonType: "date" },
  deliveries: { bsonType: "array", maxItems: 15, items: { bsonType: "object", required: ["attemptNo", "channel", "outcome", "attemptedAt"], properties: {
    channel: { enum: ["push", "sms", "email"] }, outcome: { enum: ["accepted", "failed-transient", "failed-permanent"] } } } } } } });
ntf.reminders.createIndex({ subjectRef: 1, kind: 1 }, { unique: true, name: "ux_reminder_once" });
ntf.reminders.createIndex({ dueAt: 1 }, { name: "ix_reminder_due", partialFilterExpression: { status: "scheduled" } });
ntf.reminders.createIndex({ lockedUntil: 1 }, { name: "ix_reminder_stuck", partialFilterExpression: { status: "sending" } });
ntf.reminders.createIndex({ subjectRef: 1 }, { name: "ix_reminder_subject" });
ntf.reminders.createIndex({ completedAt: 1 }, { name: "ttl_reminder_90d", expireAfterSeconds: 90 * 24 * 3600 });
messaging(ntf);

// ---------------- ml ----------------
const ml = db.getSiblingDB("ml");
ensureCollection(ml, "patient_stats", { $jsonSchema: { bsonType: "object", required: ["_id"], properties: {
  bookedTotal: { bsonType: "int", minimum: 0 }, noshowTotal: { bsonType: "int", minimum: 0 },
  fulfilledTotal: { bsonType: "int", minimum: 0 }, lateCancelTotal: { bsonType: "int", minimum: 0 } } } });
ensureCollection(ml, "appointment_facts", { $jsonSchema: { bsonType: "object", required: ["_id", "patientId", "practitionerId", "specialty", "channel", "ageBand", "bookedAt", "startAt", "outcome"], properties: {
  ageBand: { bsonType: "string", pattern: "^([0-9]{1,3}-[0-9]{1,3}|unknown)$" },
  channel: { enum: ["web", "chatbot", "staff"] },
  outcome: { enum: ["pending", "fulfilled", "noshow", "cancelled"] },
  bookedAt: { bsonType: "date" }, startAt: { bsonType: "date" } } } });
ml.appointment_facts.createIndex({ startAt: 1 });
ml.appointment_facts.createIndex({ patientId: 1, startAt: -1 });
// predictions: time-series nếu engine hỗ trợ (MongoDB 5+; Cosmos vCore: kiểm khi dựng UAT), nếu không thì collection thường.
if (!ml.getCollectionNames().includes("predictions")) {
  try {
    ml.createCollection("predictions", { timeseries: { timeField: "predictedAt", metaField: "modelVersion", granularity: "minutes" }, expireAfterSeconds: 400 * 24 * 3600 });
    print("ml.predictions: time-series collection");
  } catch (e) {
    ml.createCollection("predictions");
    ml.predictions.createIndex({ predictedAt: 1 }, { expireAfterSeconds: 400 * 24 * 3600 });
    print("ml.predictions: collection thường (" + e.codeName + ")");
  }
}
ml.predictions.createIndex({ modelVersion: 1, predictedAt: 1 });
ml.predictions.createIndex({ appointmentId: 1 });
messaging(ml);
print("OK notification + ml");
