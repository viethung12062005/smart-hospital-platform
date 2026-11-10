# Detailed Design

> Status: **Approved — G3 passed on 2026-10-26**. All future changes go through a PR approved by SA (API/event contract changes must update documents and tasks).
> Architecture overview: [../overview.md](../overview.md). Requirements: [../../02-requirements](../../02-requirements/functional-requirements.md).

| # | Document | Contents |
|---|---|---|
| 01 | [Domain model](01-domain-model.md) | Bounded contexts, data ownership, FHIR profile per resource (fields, required, value set), relationship diagram |
| 02 | [DB scheduling (PostgreSQL)](02-db-scheduling.md) | Full DDL, double-booking constraints, indexes, main queries, retention |
| 03 | [DB clinical + document (MongoDB)](03-db-clinical-document.md) | Collections, document shapes, indexes, shard key, validation, history, projection |
| 04 | [DB notification + ML (PostgreSQL)](04-db-notification-ml.md) | Reminder table, delivery attempts, deduplication; feature store and prediction log |
| 05 | [Audit + Blob storage](05-storage-audit-blob.md) | Audit records, WORM + hash chain, Log Analytics table, Blob layout |
| 06 | [AI data](06-data-ai.md) | AI Search index, conversations, knowledge base, extraction jobs |
| 07 | [API conventions](07-api-conventions.md) | Gateway routing, authentication, errors, pagination, versioning, idempotency, concurrency |
| 08 | [API scheduling](08-api-scheduling.md) | Each endpoint: permissions, request, response, errors |
| 09 | [API clinical](09-api-clinical.md) | Same as above |
| 10 | [API document](10-api-document.md) | Same as above |
| 11 | [API notification, audit, ML](11-api-notification-audit-ml.md) | Same as above |
| 12 | [API AI](12-api-ai.md) | Same as above |
| 13 | [Event catalog](13-events.md) | CloudEvents, topics, producers/consumers, payloads, retry/DLQ |
| 14 | [Sequence diagram](14-sequences.md) | Main end-to-end flows |
| 15 | [Authorization design](15-authorization.md) | How the authorization matrix is enforced, care relationships, break-glass, identity links |
| 16 | [MLOps no-show](16-mlops.md) | Synthetic dataset specification, reproducible training, quality gates, drift, automatic retraining, shadow + canary |

## Common conventions (apply to all documents)

| Topic | Convention |
|---|---|
| FHIR version | R4 (4.0.1), JSON |
| Resource ID | UUIDv7 string (`0192f8a4-...`), generated in the service; time-ordered so B-tree friendly |
| Time | Stored in UTC (`timestamptz` / ISO 8601 `Z`); displayed and slots generated in `Asia/Ho_Chi_Minh` |
| Canonical URL | `https://shp.example.org/fhir/...` (assumed domain) |
| Identifier system | `urn:shp:mrn` (patient ID `BN-YYYY-NNNNNN`), `urn:shp:practitioner` (staff ID `NV-NNNN`), `urn:shp:subject` (OIDC account identifier) |
| Code system | ICD-10 `http://hl7.org/fhir/sid/icd-10`, LOINC `http://loinc.org`, specialty `https://shp.example.org/fhir/CodeSystem/specialty`, cancellation reason `https://shp.example.org/fhir/CodeSystem/cancel-reason` |
| Extension | `https://shp.example.org/fhir/StructureDefinition/<key>`; list in 01 §3.1 and `contracts/terminology` |
| Name prefixes | Event `shp.<context>.<event>.v<N>`; table/collection snake_case singular for Postgres, plural for MongoDB |
| Data | Synthetic data only; events and logs **contain no PII** (IDs only) |

## Changes from the architecture overview (found during detailed design)

| # | Change | Reason | Update |
|---|---|---|---|
| C1 | `DocumentReference` belongs to the **document service** (MongoDB database `document`), not clinical | A resource has only one owner; document needs its own malware scan status | ADR-0008 |
| C2 | Store FHIR as **a separate table/collection per resource type, with typed search columns** + full JSON, instead of a shared search-index table | Queries use B-tree indexes directly, have real DB constraints (double-booking prevention), and are faster than EAV | ADR-0003 |
| C3 | Double-booking prevention adds a **unique partial index** on `appointment(slot_id)` and a **per-patient advisory lock** for BR-03 | Guaranteed at the DB layer even if the code is wrong | ADR-0005 |
| C4 | Notification stores reminder state in **PostgreSQL** (database `notification`; ML also uses PostgreSQL database `ml`) and schedules with a table + `SKIP LOCKED` worker | Cancelling a reminder is just a row update; behaves the same on RabbitMQ and Service Bus | ADR-0008 |
| C5 | ML maintains attendance statistics from events and **emits `NoShowScored`**; notification listens to this event instead of calling the ML API | ML failures do not affect reminders (AC-15.1), no synchronous dependency | ADR-0007 |
| C6 | Check-in belongs to scheduling (`Appointment.status = arrived`); clinical listens to the event to create an `Encounter` | Single source of truth for appointment status | 01, 13 |
| C7 | Account ↔ `Patient`/`Practitioner` links stored in clinical (`identity_links`); other services look them up via a cached internal API, no custom claim | No custom claim configuration in Keycloak; changing IdP does not change code | 15 |
| C8 | Events use the **CloudEvents 1.0** format | Open standard, carries `traceparent` across services | 13 |
