# 03 — DB design: clinical and document (MongoDB)

Azure Cosmos DB for MongoDB vCore (local: `mongo:8` running a single-node replica set for transactions).
Two separate databases in the same cluster: `clinical` (clinical service) and `document` (document service). Each service has
its own DB user, with permissions only on its own database.

## 1. Principles

- **One collection per resource type**. The document includes the administrative part (ID, version, partition key), `resource` is the full FHIR
  JSON, and `search` is normalized fields for searching (server writes only).
- **Shard key `patientId`** for every patient-related collection: a patient's timeline reads only one
  partition. MVP runs one shard; the design is ready for horizontal scaling.
- Optimistic concurrency with `versionId`; every resource + history + outbox write is in **one transaction**.
- Write concern `majority`; read from primary (ensure doctors see data immediately after it is written).

## 2. `clinical` database

### 2.1 Common document shape

```json
{
  "_id": "0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e6f",
  "versionId": 3,
  "lastUpdated": { "$date": "2026-11-20T02:15:00Z" },
  "patientId": "0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e6f",
  "resource": { "resourceType": "Patient", "id": "0192f8a4-...", "meta": { "versionId": "3", "lastUpdated": "2026-11-20T02:15:00Z" } },
  "search": { },
  "deleted": false
}
```

### 2.2 Collections and indexes

| Collection | `search` (normalized fields) | Index | Shard key |
|---|---|---|---|
| `patients` | `mrn`, `nameTokens[]` (lowercase, diacritics removed), `phone` (E.164), `birthDate`, `gender`, `active`, `nationalIdEnc` (AES-256-GCM, optional), `nationalIdLast4` | `{search.mrn:1}` **unique** · `{search.phone:1, search.birthDate:1}` (deduplication AC-01.2) · `{search.nationalIdHash:1}` **unique, partial** (only when present; HMAC-SHA-256, do not store the clear number) · `{search.nameTokens:1, search.birthDate:1}` | `{_id: "hashed"}` |
| `practitioners` | `staffCode`, `nameTokens[]`, `active` | `{search.staffCode:1}` **unique** · `{search.nameTokens:1}` | none (small) |
| `practitioner_roles` | `practitionerId`, `specialty`, `code`, `active` | `{search.practitionerId:1}` · `{search.specialty:1, search.code:1, search.active:1}` | none |
| `encounters` | `status`, `practitionerId`, `appointmentId`, `periodStart`, `periodEnd`, `serviceType` | `{patientId:1, search.periodStart:-1}` · `{search.practitionerId:1, search.periodStart:-1}` · `{search.appointmentId:1}` **unique** (creating an Encounter from an event is idempotent) | `{patientId: "hashed"}` |
| `observations` | `encounterId`, `category`, `code`, `effective`, `interpretation` | `{patientId:1, search.category:1, search.effective:-1}` · `{search.encounterId:1}` | `{patientId: "hashed"}` |
| `conditions` | `encounterId`, `category`, `code`, `clinicalStatus`, `recordedDate` | `{patientId:1, search.recordedDate:-1}` · `{search.encounterId:1}` | `{patientId: "hashed"}` |
| `allergy_intolerances` | `clinicalStatus`, `criticality` | `{patientId:1}` | `{patientId: "hashed"}` |
| `resource_history` | — (`_id` = `<type>/<id>/<version>`, with `resourceType`, `id`, `versionId`, `operation`, `changedBy`, `changedAt`, `resource`) | `{resourceType:1, id:1, versionId:-1}` | `{patientId: "hashed"}` |
| `identity_links` | `_id` = OIDC subject; `issuer`, `fhirUser` (`Patient/..` or `Practitioner/..`), `disabled`, `createdAt`, `createdBy` | `{fhirUser:1}` **unique** | none |
| `profile_links` | `accountSub`, `patientId`, `relationship` (`guardian` · `proxy`), `scope` (`booking` · `records` · `full`), `status` (`pending` · `active` · `revoked` · `expired`), `establishedBy` (`otp` · `counter` · `guardian-declaration`), `consentRef`, `expiresAt`, `createdAt`, `revokedAt` | `{accountSub:1, status:1}` · `{patientId:1, status:1}` · `{accountSub:1, patientId:1}` **unique** | none |
| `link_attempts` | `accountSub`, `kind` (`claim` · `link`), `phoneHash`, `at` | `{accountSub:1, at:1}` · `{phoneHash:1, at:1}` · TTL 24 hours on `at` | none |
| `care_relationships` | `_id` = `<practitionerId>\|<patientId>`; `practitionerId`, `patientId`, `sources[]` (`{type, id, status, at}`), `validUntil` | `{patientId:1}` · `{practitionerId:1, validUntil:-1}` | none |
| `break_glass_sessions` | `practitionerId`, `patientId`, `reason`, `startedAt`, `expiresAt` | `{practitionerId:1, patientId:1, expiresAt:-1}` · **TTL** on `expiresAt` | none |
| `mrn_counters` | `_id` = year, `seq` | (by `_id`) | none |
| `outbox` | `_id` = event id; `eventType`, `subject`, `payload`, `occurredAt`, `publishedAt` | `{occurredAt:1}` partial `publishedAt: null` | none |
| `inbox` | `_id` = `<consumer>\|<messageId>`, `processedAt` | **TTL** 14 days on `processedAt` | none |

### 2.3 Search normalization rules

- `nameTokens`: Unicode NFD → remove diacritics → `d` (from the Vietnamese d with stroke) → lowercase → split on whitespace. Example "Nguyen Van An" →
  `["nguyen","van","an"]`.
- Search "nguyen va" (AC-06.1): every token except the last matches exactly, the last token matches by prefix:
  `{ $and: [ { "search.nameTokens": "nguyen" }, { "search.nameTokens": { $regex: "^va" } } ] }`
  (the regex is anchored with `^`, so the multikey index can be used).
- `phone`: normalized to E.164 (`0901234567` → `+84901234567`).

### 2.4 DB-layer validation (`$jsonSchema`, example `encounters`)

```json
{
  "$jsonSchema": {
    "bsonType": "object",
    "required": ["_id", "versionId", "lastUpdated", "patientId", "resource", "search"],
    "properties": {
      "_id":         { "bsonType": "string", "pattern": "^[0-9a-f-]{36}$" },
      "versionId":   { "bsonType": "int", "minimum": 1 },
      "lastUpdated": { "bsonType": "date" },
      "patientId":   { "bsonType": "string" },
      "resource": {
        "bsonType": "object",
        "required": ["resourceType", "status", "subject"],
        "properties": { "resourceType": { "enum": ["Encounter"] },
                        "status": { "enum": ["arrived", "in-progress", "finished", "cancelled"] } }
      },
      "search": { "bsonType": "object", "required": ["appointmentId", "practitionerId", "status"] }
    }
  }
}
```
Each resource collection has a similar validator (`validationLevel: strict`, `validationAction: error`). Detailed FHIR profile
validation (cardinality, value set) is at the application layer; the DB validator blocks structurally invalid data.

### 2.5 Main write operations

| Operation | How |
|---|---|
| Create resource | Transaction: `insertOne` resource → `insertOne` history (`operation=create`) → `insertOne` outbox (if there is an event) |
| Update | Transaction: `updateOne({_id, versionId: expected}, {$set: {resource, search, lastUpdated}, $inc: {versionId: 1}})`; `matchedCount = 0` → **412** (wrong If-Match) or **409**; then insert history + outbox |
| Generate MRN | `findOneAndUpdate({_id: "2026"}, {$inc: {seq: 1}}, {upsert: true, returnDocument: "after"})` → `BN-2026-000123` |
| Create Encounter from check-in event | `insertOne` with `search.appointmentId`; duplicate unique key error = event already processed → skip (idempotent) |
| Update care relationship | `updateOne({_id: "<prac>\|<pat>"}, {$set/$pull/$push sources...}, {upsert: true})`, then recompute `validUntil = max(sources.at of non-cancelled appointments) + 365 days` |

### 2.6 Main queries

| Query | Filter | Index |
|---|---|---|
| Patient timeline (NFR-P3 ≤ 500 ms) | 4 parallel queries by `patientId` on `encounters`, `observations`, `conditions`, `allergy_intolerances` (+ call document service), sorted by time, limit 50 items/page | index `{patientId, <time>}` of each collection |
| Check care relationship | `findOne({_id: "<prac>\|<pat>", validUntil: {$gt: now}})` or a valid break-glass session | `_id`, break-glass index |
| Deduplication at registration | `find({search.phone, search.birthDate, search.active: true})` | `{search.phone, search.birthDate}` |
| Doctor's patient list today | `encounters.find({search.practitionerId, search.periodStart: {$gte: today}})` | `{search.practitionerId, search.periodStart}` |

### 2.7 Platform collections: `outbox`, `inbox`, `idempotency_keys`

Common definition for **every** MongoDB database (clinical, document, notification, audit, ml, ai). Documents 04, 05, 06 reference this section.
The equivalent PostgreSQL version (tables `outbox`, `inbox`, `idempotency_key`) is in [02](02-db-scheduling.md).

| Collection | Fields | Index / TTL | Library |
|---|---|---|---|
| `outbox` | `_id` = event id (UUIDv7); `eventType` (`^shp\.[a-z]+\.[a-z-]+\.v[0-9]+$`), `subject`, `payload` (full CloudEvent), `occurredAt`, `publishedAt` (`null` when not yet published), `attempts` | `ix_outbox_unpublished`: `{occurredAt:1}` partial `publishedAt: null` | Java `shp-data-mongo` · Python `shp_platform.mongo` |
| `inbox` | `_id` = `<consumer>\|<messageId>`, `processedAt` | `ttl_inbox_14d`: **TTL** 14 days on `processedAt` | Java `shp-data-mongo` · Python `shp_platform.mongo` |
| `idempotency_keys` | `_id` = `<subject>\|<key>` (`subject` = OIDC `sub` of the caller, `key` = `Idempotency-Key` header, 8–128 characters); `operation` (operation name, e.g. `create-upload-session`); `requestHash` (SHA-256 of the body, Binary type); `resourceId` (UUIDv7 of the created resource); `createdAt` | `ttl_idempotency_24h`: **TTL** 24 hours on `createdAt` | Java `MongoIdempotency` (SHR-12). Python uses an `idempotency` collection with a different schema, see [06](06-data-ai.md) §5.1 |

`idempotency_keys` rules (same semantics as PostgreSQL and [07](07-api-conventions.md)):
- Write the record **in the same transaction** as the data change; parallel requests with the same key conflict, and the retry sees the committed record.
- Same key + same `operation` + same `requestHash` within 24 hours → return the same old resource (`resourceId`), do not create a new one.
- Same key but different body or operation → **422** `idempotency-key-reused`.
- The key includes `subject`, so one person's key never returns another person's resource. Do not store the body, only the hash (contains no PII).

Which database has which collection (only create collections the database actually uses):

| Database | `outbox` | `inbox` | `idempotency_keys` | Reason |
|---|---|---|---|---|
| scheduling (PostgreSQL) | ✓ | ✓ | ✓ (`idempotency_key`) | Emits E1–E5; receives E6, E7; `$book`, `$cancel`, `$reschedule` require a key |
| clinical | ✓ | ✓ | — | Emits E6, E7; receives E1–E5; no API requires a key |
| document | ✓ | ✓ | ✓ | Emits E8, E9; receives E7; D1 creates an upload session requiring a key |
| notification | ✓ | ✓ | — | Emits E11; receives E1–E3, E7, E9, E10 |
| audit | ✓ | ✓ | — | Receives E11; emits E11 when exporting audit ([11](11-api-notification-audit-ml.md)) |
| ml | ✓ | ✓ | — | Emits E10; receives E1–E3, E5, E7 |
| ai | ✓ | ✓ | ✓ (`idempotency`, stateful variant) | Emits E11; receives E8; AI4, AI6, AI8 require a key ([06](06-data-ai.md) §5.1) |

Every service also emits E11 `audit.recorded` through its own `outbox` ([13](13-events.md)).

## 3. `document` database

| Collection | Fields | Index |
|---|---|---|
| `document_references` | `_id`, `versionId`, `lastUpdated`, `patientId`, `resource` (FHIR DocumentReference), `processing.state` (`pending-upload` · `scanning` · `available` · `quarantined`), `processing.scanResult`, `processing.scannedAt`, `blob` (`container`, `path`, `contentType`, `size`, `sha256`), `releasedToPatient` (bool), `search` (`encounterId`, `type`, `date`, `authorId`) | `{patientId:1, search.date:-1}` · `{search.encounterId:1}` · `{processing.state:1, lastUpdated:1}` |
| `upload_sessions` | `_id` (upload id), `documentId`, `patientId`, `createdBy`, `blobPath`, `maxSize`, `allowedTypes[]`, `state` (`open` · `completed` · `expired`), `expiresAt` | **TTL** on `expiresAt` (15 minutes) |
| `resource_history` | as in the `clinical` database | as above |
| `outbox`, `inbox`, `idempotency_keys` | as in §2.7 | as in §2.7 |

Shard key for `document_references`: `{patientId: "hashed"}`.

## 4. Capacity, backup, permissions

| Item | Value |
|---|---|
| Year-1 size | clinical ~10 GB (including history, indexes); document metadata < 1 GB (files on Blob) |
| Backup | Cosmos DB for MongoDB vCore: continuous backup, point-in-time restore 7 days (drill in PRD-03) |
| DB user | `clinical_app` only `readWrite` on `clinical`; `document_app` only `readWrite` on `document`; password generated by Terraform, stored in Key Vault, injected via the CSI driver |
| Migration | Script creates collections + validators + indexes, run idempotently by a Kubernetes Job before deploy |

## 5. Design verification (already run)

Run on MongoDB 8 (single-node replica set) on 26/10/2026:

| Check | Result |
|---|---|
| `$jsonSchema` validator of `encounters` (section 2.4) | Accepts a valid document; rejects `status` outside the value set (error code 121) |
| Unique `search.appointmentId` | A second Encounter for the same appointment is rejected (code 11000), so creating an Encounter from an event is idempotent |
| Optimistic concurrency | Update with the correct version matched 1 document; with an old version matched 0 documents (→ 412/409) |
| Search "nguyen va" (section 2.3) | Returns only "Nguyen Van An"; the query plan uses `IXSCAN` |
| Transaction resource + outbox | Commit succeeded |
