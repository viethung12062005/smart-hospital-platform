// Verify design 04 against a real MongoDB (replica set). Usage: mongosh "<uri>" mongo-notification-ml.js mongo-notification-ml.test.js
const ntf = db.getSiblingDB("notification"); const ml = db.getSiblingDB("ml");
let failures = 0;
function check(name, ok) { print((ok ? "PASS " : "FAIL ") + name); if (!ok) failures++; }
function rejects(fn) { try { fn(); return false; } catch (e) { return e.code === 121 || e.code === 11000; } }
ntf.reminders.deleteMany({}); ml.appointment_facts.deleteMany({}); ntf.outbox.deleteMany({});

const appt = "Appointment/0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e70"; const now = new Date();
function rem(id, kind, due) { return { _id: id, subjectRef: appt, patientId: "p1", kind: kind, dueAt: due, status: "scheduled", attempts: NumberInt(0), createdAt: now, updatedAt: now }; }

ntf.reminders.insertOne(rem("r1", "t-24h", new Date(now - 1000)));
check("duplicate event does not create a duplicate reminder (unique subjectRef+kind)", rejects(() => ntf.reminders.insertOne(rem("r1b", "t-24h", now))));
check("invalid kind is rejected", rejects(() => ntf.reminders.insertOne(rem("r2", "t-99h", now))));
check("attempts > 5 is rejected", rejects(() => ntf.reminders.insertOne(Object.assign(rem("r3", "t-2h", now), { attempts: NumberInt(6) }))));
check("invalid channel in preferences is rejected", rejects(() => ntf.preferences.insertOne({ _id: "p1", channels: ["fax"], locale: "vi", updatedAt: now })));
check("invalid ageBand is rejected", rejects(() => ml.appointment_facts.insertOne({ _id: "a1", patientId: "p", practitionerId: "d", specialty: "x", channel: "web", ageBand: "forty", bookedAt: now, startAt: now, outcome: "pending" })));

// Atomic work claiming (sequential in mongosh): each reminder is claimed only once. The concurrent test lives in the Java library.
for (let i = 0; i < 20; i++) ntf.reminders.insertOne(Object.assign(rem("q" + i, "booking-confirmation", new Date(now - 5000)), { subjectRef: "Appointment/0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e" + String(10 + i) }));
function claim() { return ntf.reminders.findOneAndUpdate({ status: "scheduled", dueAt: { $lte: new Date() } }, { $set: { status: "sending", lockedUntil: new Date(Date.now() + 120000) }, $inc: { attempts: 1 } }, { sort: { dueAt: 1 }, returnDocument: "after" }); }
const got = []; let d; while ((d = claim()) !== null) got.push(d._id);
check("claiming work: 21 due reminders are each claimed exactly once", got.length === 21 && new Set(got).size === 21);
check("claiming work uses the partial index ix_reminder_due", JSON.stringify(ntf.reminders.find({ status: "scheduled", dueAt: { $lte: new Date() } }).sort({ dueAt: 1 }).explain().queryPlanner.winningPlan).includes("ix_reminder_due"));

ntf.reminders.updateOne({ _id: "q0" }, { $set: { lockedUntil: new Date(Date.now() - 1000) } });
const rec = ntf.reminders.updateMany({ status: "sending", lockedUntil: { $lt: new Date() } }, { $set: { status: "scheduled", lockedUntil: null } });
check("stuck reminder (dead worker) is reclaimed", rec.modifiedCount === 1);

const s = db.getMongo().startSession(); const sn = s.getDatabase("notification");
s.startTransaction();
sn.reminders.insertOne(Object.assign(rem("tx1", "t-2h", now), { subjectRef: "Appointment/0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e99" }));
sn.outbox.insertOne({ _id: "e1", eventType: "shp.scheduling.appointment-booked.v1", subject: appt, payload: {}, occurredAt: now });
s.abortTransaction();
check("reminder + outbox transaction rolls back fully", ntf.reminders.countDocuments({ _id: "tx1" }) === 0 && ntf.outbox.countDocuments({ _id: "e1" }) === 0);

check("TTL index cleans up reminders after 90 days", ntf.reminders.getIndexes().some(i => i.name === "ttl_reminder_90d" && i.expireAfterSeconds === 7776000));
check("inbox TTL 14 days", ntf.inbox.getIndexes().some(i => i.expireAfterSeconds === 1209600));
check("predictions exists", ml.getCollectionNames().includes("predictions"));
print(failures === 0 ? "ALL PASSED" : failures + " FAILED");
if (failures) quit(1);
