// Verify design 03 §2 against a real MongoDB (replica set). Usage: mongosh "<uri>" mongo-clinical.js mongo-clinical.test.js
// Fake data only.
const c = db.getSiblingDB("clinical");
let failures = 0;
function check(name, ok) { print((ok ? "PASS " : "FAIL ") + name); if (!ok) failures++; }
function rejects(fn) { try { fn(); return false; } catch (e) { return e.code === 121 || e.code === 11000; } }
function accepts(fn) { try { fn(); return true; } catch (e) { print("  unexpected: " + e.codeName); return false; } }
["patients", "practitioners", "encounters", "observations", "conditions", "care_relationships", "identity_links", "resource_history", "outbox", "break_glass_sessions"]
  .forEach(n => c.getCollection(n).deleteMany({}));

const now = new Date();
const P = "0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e6f", D = "0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e03";
let n = 0;
function id() { n++; return "0192f8a4-6c1e-7b2a-9f00-" + String(n).padStart(12, "0"); }
function encounter(over) {
  return Object.assign({ _id: id(), versionId: NumberInt(1), lastUpdated: now, patientId: P, deleted: false,
    resource: { resourceType: "Encounter", status: "arrived", subject: { reference: "Patient/" + P } },
    search: { appointmentId: "a-" + n, practitionerId: D, status: "arrived", periodStart: now, periodEnd: null } }, over || {});
}

// ---------------- 03 §2.4: validators ----------------
check("a valid Encounter is accepted", accepts(() => c.encounters.insertOne(encounter())));
check("Encounter status outside the value set is rejected (121)", rejects(() => c.encounters.insertOne(encounter({
  resource: { resourceType: "Encounter", status: "planned", subject: { reference: "Patient/" + P } } }))));
check("Encounter without patientId is rejected", rejects(() => { const e = encounter(); delete e.patientId; c.encounters.insertOne(e); }));
check("Encounter without search.appointmentId is rejected", rejects(() => c.encounters.insertOne(encounter({ search: { practitionerId: D, status: "arrived" } }))));
check("a document of another resource type is rejected", rejects(() => c.encounters.insertOne(encounter({
  resource: { resourceType: "Observation", status: "arrived", subject: {} } }))));
check("versionId 0 is rejected", rejects(() => c.encounters.insertOne(encounter({ versionId: NumberInt(0) }))));
check("a non-UUID _id is rejected", rejects(() => c.encounters.insertOne(encounter({ _id: "enc-1" }))));
check("Observation status outside the value set is rejected", rejects(() => c.observations.insertOne({ _id: id(), versionId: NumberInt(1), lastUpdated: now, patientId: P,
  resource: { resourceType: "Observation", status: "preliminary", code: {}, subject: {} }, search: { category: "vital-signs", code: "8310-5", effective: now } })));
check("identity link with a malformed fhirUser is rejected", rejects(() => c.identity_links.insertOne({ _id: "sub-1", fhirUser: "Group/1", disabled: false })));
check("identity link with an unknown role is rejected", rejects(() => c.identity_links.insertOne({ _id: "sub-2", fhirUser: "Patient/" + P, roles: ["root"], disabled: false })));
check("care relationship with validUntil null (no care) is accepted", accepts(() => c.care_relationships.insertOne({ _id: D + "|" + P, practitionerId: D, patientId: P,
  sources: [{ type: "appointment", id: "a1", status: "cancelled", at: now, version: NumberLong("2") }], validUntil: null })));
check("care source of an unknown type is rejected", rejects(() => c.care_relationships.insertOne({ _id: "x|y", practitionerId: "x", patientId: "y",
  sources: [{ type: "referral", id: "r1", status: "booked", at: now }], validUntil: now })));
check("break-glass reason shorter than 20 characters is rejected", rejects(() => c.break_glass_sessions.insertOne({ practitionerId: D, patientId: P, reason: "short", startedAt: now, expiresAt: now })));
check("history entry with an unknown operation is rejected", rejects(() => c.resource_history.insertOne({ _id: "Encounter/x/1", resourceType: "Encounter", id: "x",
  versionId: NumberInt(1), operation: "purge", changedBy: "system", changedAt: now, resource: {} })));
check("outbox event with a malformed type is rejected", rejects(() => c.outbox.insertOne({ _id: "e1", eventType: "EncounterFinished", subject: "Encounter/x", payload: {}, occurredAt: now })));

// ---------------- 03 §2.2: unique indexes ----------------
const first = encounter(); c.encounters.insertOne(first);
check("a second Encounter for the same appointment is rejected (11000)", rejects(() => c.encounters.insertOne(encounter({
  search: { appointmentId: first.search.appointmentId, practitionerId: D, status: "arrived" } }))));
c.identity_links.insertOne({ _id: "sub-3", fhirUser: "Practitioner/" + D, roles: ["doctor"], disabled: false });
check("one account per profile (unique fhirUser)", rejects(() => c.identity_links.insertOne({ _id: "sub-4", fhirUser: "Practitioner/" + D, disabled: false })));
function practitioner(code) { return { _id: id(), versionId: NumberInt(1), lastUpdated: now, resource: { resourceType: "Practitioner" }, search: { staffCode: code, active: true } }; }
c.practitioners.insertOne(practitioner("NV-0042"));
check("staff code is unique", rejects(() => c.practitioners.insertOne(practitioner("NV-0042"))));

// ---------------- 03 §2.5: optimistic concurrency, transaction ----------------
check("update with the right version matches 1", c.encounters.updateOne({ _id: first._id, versionId: 1 }, { $inc: { versionId: 1 } }).matchedCount === 1);
check("update with a stale version matches 0 (412)", c.encounters.updateOne({ _id: first._id, versionId: 1 }, { $inc: { versionId: 1 } }).matchedCount === 0);
const s = db.getMongo().startSession(); const sc = s.getDatabase("clinical");
s.startTransaction();
const tx = encounter(); sc.encounters.insertOne(tx);
sc.outbox.insertOne({ _id: "e-tx", eventType: "shp.clinical.encounter-finished.v1", subject: "Encounter/" + tx._id, payload: {}, occurredAt: now });
s.abortTransaction();
check("resource + outbox transaction rolls back fully", c.encounters.countDocuments({ _id: tx._id }) === 0 && c.outbox.countDocuments({ _id: "e-tx" }) === 0);

// ---------------- index plans and TTLs ----------------
const plan = JSON.stringify(c.encounters.find({ "search.practitionerId": D, "search.periodStart": { $gte: now } }).explain().queryPlanner.winningPlan);
check("doctor's patient list today uses ix_encounters_practitioner_period_start", plan.includes("ix_encounters_practitioner_period_start"));
check("care check is one read by _id", JSON.stringify(c.care_relationships.find({ _id: D + "|" + P, validUntil: { $gt: now } }).explain().queryPlanner.winningPlan).includes("IDHACK")
  || JSON.stringify(c.care_relationships.find({ _id: D + "|" + P, validUntil: { $gt: now } }).explain().queryPlanner.winningPlan).includes("_id_"));
check("break-glass sessions expire (TTL on expiresAt)", c.break_glass_sessions.getIndexes().some(i => i.expireAfterSeconds === 0));
check("inbox TTL 14 days", c.inbox.getIndexes().some(i => i.name === "ttl_inbox_14d" && i.expireAfterSeconds === 1209600));
check("link attempts TTL 24 hours", c.link_attempts.getIndexes().some(i => i.expireAfterSeconds === 86400));
check("every collection of 03 §2.2 exists", ["patients", "practitioners", "practitioner_roles", "encounters", "observations", "conditions", "allergy_intolerances",
  "resource_history", "identity_links", "profile_links", "link_attempts", "care_relationships", "break_glass_sessions", "mrn_counters", "outbox", "inbox"]
  .every(x => c.getCollectionNames().includes(x)));

["patients", "practitioners", "encounters", "observations", "conditions", "care_relationships", "identity_links", "resource_history", "outbox", "break_glass_sessions"]
  .forEach(x => c.getCollection(x).deleteMany({}));
print(failures === 0 ? "ALL PASSED" : failures + " FAILED");
if (failures) quit(1);
