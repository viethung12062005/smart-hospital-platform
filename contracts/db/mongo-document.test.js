// Verify design 03 §3 against a real MongoDB. Usage: mongosh "<uri>" mongo-document.js mongo-document.test.js
// Fake data only. Run the init script twice before this file to prove idempotency (the mongo-contract workflow does).
const d = db.getSiblingDB("document");
let failures = 0;
function check(name, ok) { print((ok ? "PASS " : "FAIL ") + name); if (!ok) failures++; }
function rejects(fn) { try { fn(); return false; } catch (e) { return e.code === 121 || e.code === 11000; } }
function accepts(fn) { try { fn(); return true; } catch (e) { print("  unexpected: " + e.codeName); return false; } }
const names = ["document_references", "upload_sessions", "resource_history", "outbox", "inbox", "idempotency_keys"];
names.forEach(n => d.getCollection(n).deleteMany({}));

const now = new Date();
const P = "0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e6f", DOC = "0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e70";
function ref(over) {
  return Object.assign({ _id: DOC, versionId: NumberInt(1), lastUpdated: now, patientId: P,
    resource: { resourceType: "DocumentReference" }, processing: { state: "pending-upload" }, search: { date: now } }, over || {});
}
function session(over) {
  return Object.assign({ _id: "up-1", documentId: DOC, patientId: P, createdBy: "sub-1", blobPath: "docs/" + DOC,
    maxSize: NumberLong("10485760"), allowedTypes: ["application/pdf"], state: "open", expiresAt: new Date(now.getTime() + 15 * 60000) }, over || {});
}

check("every collection of 03 §3 exists", names.every(n => d.getCollectionNames().includes(n)));

check("a valid DocumentReference is accepted", accepts(() => d.document_references.insertOne(ref())));
check("processing.state outside the value set is rejected", rejects(() => d.document_references.insertOne(ref({ _id: "0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e71", processing: { state: "deleted" } }))));
check("DocumentReference without patientId is rejected", rejects(() => { const r = ref({ _id: "0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e72" }); delete r.patientId; d.document_references.insertOne(r); }));
check("a valid upload session is accepted", accepts(() => d.upload_sessions.insertOne(session())));
check("upload session state outside the value set is rejected", rejects(() => d.upload_sessions.insertOne(session({ _id: "up-2", state: "cancelled" }))));
check("upload session without expiresAt is rejected", rejects(() => { const s = session({ _id: "up-3" }); delete s.expiresAt; d.upload_sessions.insertOne(s); }));
check("upload session with empty allowedTypes is rejected", rejects(() => d.upload_sessions.insertOne(session({ _id: "up-4", allowedTypes: [] }))));
check("outbox event with a malformed type is rejected", rejects(() => d.outbox.insertOne({ _id: "e1", eventType: "DocumentAvailable", subject: "x", payload: {}, occurredAt: now })));

check("upload_sessions has a TTL index on expiresAt", d.upload_sessions.getIndexes().some(i => i.key.expiresAt === 1 && i.expireAfterSeconds === 0));
check("inbox TTL 14 days", d.inbox.getIndexes().some(i => i.name === "ttl_inbox_14d" && i.expireAfterSeconds === 1209600));
check("idempotency TTL 24 hours", d.idempotency_keys.getIndexes().some(i => i.name === "ttl_idempotency_24h" && i.expireAfterSeconds === 86400));
check("document_references has the three indexes of 03 §3", ["ix_document_references_patient_date", "ix_document_references_encounter_id", "ix_document_references_state_updated"]
  .every(n => d.document_references.getIndexes().some(i => i.name === n)));
const plan = JSON.stringify(d.document_references.find({ patientId: P }).sort({ "search.date": -1 }).explain().queryPlanner.winningPlan);
check("patient document list uses the patient/date index", plan.includes("ix_document_references_patient_date"));

names.forEach(n => d.getCollection(n).deleteMany({}));
print(failures === 0 ? "ALL PASSED" : failures + " FAILED");
if (failures) quit(1);
