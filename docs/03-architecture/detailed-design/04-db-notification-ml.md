# 04 — DB design: notification and ML (MongoDB 8 / Cosmos DB for MongoDB vCore)

> Per [ADR-0014](../adr/0014-database-per-service-review.md) (replaces the previous PostgreSQL design). Two separate databases `notification` and `ml`
> on the same MongoDB cluster as clinical/document/ai; each service has a user with permissions only on its own database, with no cross-access.
> notification is implemented in Java (Spring Data MongoDB), ml in Python (pymongo) — [ADR-0015](../adr/0015-java-for-all-services.md). Initialization script: [§3](#3-initialization-and-verification-script).

Common conventions (same as [03](03-db-clinical-document.md)): `_id` is a UUIDv7 string; times are UTC `Date`; every business write + `outbox` is in
**one transaction**; `inbox` has `_id = "<consumer>|<messageId>"` and a 14-day TTL.

## 1. Notification

### 1.1 Principles
- **Do not store PII**: do not store phone numbers/emails. At send time, the worker calls clinical's internal API to get contact information
  ([09-api-clinical.md](09-api-clinical.md) §4) and keeps it only in memory.
- Each reminder is **one document**; delivery attempts are **embedded** in the document (max 5 attempts × ≤ 3 channels, no unbounded growth).
- Fetch work with an **atomic `findOneAndUpdate`** (replacing `FOR UPDATE SKIP LOCKED`): two workers never take the same reminder;
  `lockedUntil` allows reclaim when a worker dies mid-way. Cancelling a reminder = updating one document.
- Prevent duplicates with a **unique index** `(subjectRef, kind)`; auto-clean completed reminders with a **TTL index** (90 days).

### 1.2 Collections

| Collection | Document | Index |
|---|---|---|
| `preferences` | `_id` = patientId; `channels` (ordered array ⊂ `push`,`sms`,`email`, ≥ 1); `locale` `vi`\|`en`; `quietStart`, `quietEnd` (`HH:mm` strings, optional); `updatedAt` | `_id` |
| `device_tokens` | `_id`; `ownerRef` (`Patient/{id}`\|`Practitioner/{id}`); `platform` `ios`\|`android`\|`web`; `token`; `createdAt`, `lastSeenAt` | unique `{token:1}`; `{ownerRef:1}` |
| `reminders` | see below | see below |
| `inbox`, `outbox` | as in [03](03-db-clinical-document.md) §2.7 (no `idempotency_keys`: no API requires a key) | as in 03 §2.7 |

`reminders`:
```json
{
  "_id": "0192f8a4-...", "subjectRef": "Appointment/0192...", "patientId": "0192...",
  "kind": "t-24h",                       // booking-confirmation | t-24h | t-6h-risk | t-2h | cancellation | result-available
  "appointmentStart": ISODate("2026-12-03T01:15:00Z"),   // null with result-available
  "dueAt": ISODate("2026-12-02T01:15:00Z"),
  "status": "scheduled",                 // scheduled | sending | sent | cancelled | dead-lettered
  "attempts": 0, "lockedUntil": null,
  "deliveries": [ { "attemptNo": 1, "channel": "push", "provider": "fcm", "outcome": "accepted",
                    "providerMessageId": "...", "errorCode": null, "latencyMs": 120, "attemptedAt": ISODate("...") } ],
  "createdAt": ISODate("..."), "updatedAt": ISODate("..."), "completedAt": null
}
```

| Index | Purpose |
|---|---|
| unique `{subjectRef:1, kind:1}` | repeated events do not create duplicate reminders |
| `{dueAt:1}` **partial** `{status:"scheduled"}` | fetching due work scans only waiting reminders |
| `{lockedUntil:1}` **partial** `{status:"sending"}` | reclaim stuck reminders |
| `{subjectRef:1}` | cancel by appointment |
| **TTL** `{completedAt:1}` `expireAfterSeconds: 7776000` (90 days) | auto-clean `sent`/`cancelled`/`dead-lettered` reminders (`completedAt` is set only at completion) |

The validator (`$jsonSchema`) requires `subjectRef`, `patientId`, `kind` ∈ list, `status` ∈ list, `attempts` 0..5, `dueAt` of type `date`.

### 1.3 Main operations

| Operation | How |
|---|---|
| Receive `appointment-booked` | Transaction: `insert inbox` (duplicate key → skip the whole event) → `insertMany` reminders `booking-confirmation` (due immediately), `t-24h`, `t-2h`, `ordered:false`; duplicate key errors (11000) for individual reminders are ignored; milestones already in the past are skipped |
| `no-show-scored` with `highRisk` | add a `t-6h-risk` reminder (duplicate → skip) |
| `appointment-cancelled` | `updateMany({subjectRef, status:"scheduled"}, {$set:{status:"cancelled", completedAt:now}})` + add a `cancellation` reminder (AC-07.1) |
| `appointment-rescheduled` | cancel reminders for the old appointment, create reminders for the new appointment (one event, one combined notification) |
| `document-released` | add a `result-available` reminder |
| **Fetch work** (loop, multiple replicas) | `findOneAndUpdate({status:"scheduled", dueAt:{$lte:now}}, {$set:{status:"sending", lockedUntil:now+2m}, $inc:{attempts:1}}, {sort:{dueAt:1}, returnDocument:"after"})`; repeat until no work remains or a batch of 100 is reached |
| Reclaim stuck reminders | `updateMany({status:"sending", lockedUntil:{$lt:now}}, {$set:{status:"scheduled", lockedUntil:null}})` every minute |
| Send succeeded | `$push deliveries`, `status:"sent"`, `completedAt:now` (condition `status:"sending"` so a just-cancelled reminder is not overwritten) |
| Transient failure | try the next channel in `channels`; out of channels → `status:"scheduled"`, `dueAt = now + backoff` (1 → 5 → 15 → 60 minutes) |
| Exhausted 5 attempts | `status:"dead-lettered"`, `completedAt:now`, metric + alert (OBS-03) |

## 2. ML

### 2.1 Principles
- ML **builds its own data** from events (`appointment-booked`, `appointment-status-changed`, `appointment-cancelled`), and does not read other services' DBs.
- **Online** data (prediction serving, monitoring) is in MongoDB; the **training set** is an immutable **Parquet** snapshot on Blob, with the content hash
  recorded in MLflow ([16-mlops.md](16-mlops.md) §2). Actual drift/AUC is computed with **pandas/DuckDB** (Python) on Parquet.
- No PII: only IDs and the 10-year age band (`ageBand`) that scheduling puts into the event.

### 2.2 Collections

| Collection | Document | Index / note |
|---|---|---|
| `patient_stats` | `_id` = patientId; `bookedTotal`, `fulfilledTotal`, `noshowTotal`, `lateCancelTotal`; `lastNoshowAt`, `lastVisitAt`, `updatedAt` | updated atomically with `$inc`/`$max` and `upsert` — no lock needed |
| `appointment_facts` | `_id` = appointmentId; `patientId`, `practitionerId`, `specialty`, `channel`, `ageBand` (`^\d{1,3}-\d{1,3}$` or `unknown`), `bookedAt`, `startAt`, `outcome` (`pending`\|`fulfilled`\|`noshow`\|`cancelled`), `outcomeAt` | `{startAt:1}`; `{patientId:1, startAt:-1}`; validator for `ageBand`, `outcome` |
| `predictions` | `appointmentId`, `modelName`, `modelVersion`, `score` 0..1, `threshold` 0..1, `highRisk`, `features` (features used — traceability AC-15.2), `predictedAt`, `latencyMs` | **time-series collection** (`timeField: predictedAt`, `metaField: modelVersion`, `granularity: minutes`) if Cosmos DB vCore supports it; otherwise: regular collection + `{modelVersion:1, predictedAt:1}` + unique `{appointmentId:1}`. TTL 400 days |
| `inbox`, `outbox` | as in [03](03-db-clinical-document.md) §2.7 (no `idempotency_keys`) | as in 03 §2.7 |

Training snapshot export: job `ml-export` reads `appointment_facts` with `outcome ≠ pending` (+ features reconstructed from `patient_stats` **at booking
time**, already stored in `predictions.features` to avoid future leakage) → writes `datasets/no-show/<yyyy-MM-dd>/<sha256>.parquet` using DuckDB `COPY ... TO ... (FORMAT parquet)`.

### 2.3 No-show model features

| Feature | Source | Type |
|---|---|---|
| `lead_time_days` | `startAt - bookedAt` | numeric |
| `start_hour`, `weekday` | `startAt` (VN time) | categorical |
| `specialty`, `channel`, `age_band` | `appointment_facts` | categorical |
| `prior_booked`, `prior_noshow`, `prior_late_cancel` | `patient_stats` at prediction time | numeric |
| `noshow_rate_smoothed` | `(prior_noshow + 1) / (prior_booked + 5)` | numeric |
| `days_since_last_noshow` | `patient_stats.lastNoshowAt` (none → 9999) | numeric |
| `is_first_visit` | `prior_booked = 0` | bool |

Split train/validation by **time** (no shuffling) to avoid future information leakage.

### 2.4 Main operations

| Event | Processing (one transaction) |
|---|---|
| `appointment-booked` | `insert appointment_facts`; read `patient_stats`; compute features; run the model (LightGBM in-process); `insert predictions`; `insert outbox` `shp.ml.no-show-scored.v1` |
| `appointment-status-changed` (`fulfilled`/`noshow`) | update `outcome`; `$inc` `patient_stats` |
| `appointment-cancelled` | `outcome = cancelled`; for a late cancellation (< 24 hours) `$inc lateCancelTotal` |

## 3. Initialization and verification script

Script: [`contracts/db/mongo-notification-ml.js`](../../../contracts/db/mongo-notification-ml.js) (idempotent: reruns without error). Run on **MongoDB 8** (single-node replica set)
and checked with [`contracts/db/mongo-notification-ml.test.js`](../../../contracts/db/mongo-notification-ml.test.js):
repeated events do not create duplicate reminders; atomic work fetch (21 due reminders, each reminder fetched only once, using index `ix_reminder_due`; run sequentially in mongosh — the **parallel multi-worker** test is in the Java library, Testcontainers); stuck reminders are reclaimed; invalid channel/`ageBand`/`kind` is blocked by the validator;
TTL indexes exist; the reminder + outbox transaction rolls back fully.
Not yet tested on Cosmos DB for MongoDB vCore (time-series collection, transactions) — that is the data IaC task when provisioning UAT.
