# ADR-0008: Choose data stores by each service's load characteristics (polyglot persistence)

## Status
Accepted (2026-10-26) — approved by the Solution Architect/PM together with gate G3.

## Context
The common approach of FHIR servers (for example Medplum) is to put everything in one PostgreSQL. Each data type in the Project 5 brief, however, has
very different access patterns: appointments need transactions and write contention, clinical records are nested FHIR
documents read by patient, audit is append-only and immutable, RAG needs vector + keyword search + rerank.

Selection principle: **each new engine must answer "what does it do substantially better that an existing engine
cannot do"**, because every added engine adds operational cost, backup, monitoring, and Azure spend.

## Decision

| Service / data | Load type | Data store (Azure → local) | Main reason |
|---|---|---|---|
| `scheduling`: Schedule, Slot, Appointment | Contended writes (many people booking the same slot), requires ACID | **PostgreSQL** (Azure DB for PostgreSQL Flexible → `postgres:16`) | Transaction spans slot + appointment + outbox; row-level optimistic concurrency; small, structured data |
| `scheduling`: free slot lookup | Very read-heavy, tolerates a few seconds of staleness | **Redis** (Azure Cache for Redis → `redis:7`) | Cache-aside by schedule/day, invalidated by `AppointmentBooked/Cancelled` events |
| `clinical`: Patient, Encounter, Observation, Condition... | Nested FHIR documents, read-heavy, always accessed by patient | **MongoDB API** (Azure Cosmos DB for MongoDB vCore → `mongo:8`) | FHIR is inherently a JSON document; shard key = patient compartment so "view full patient history" reads only one partition; flexible schema for FHIR extensions |
| `document`: PDF/image files | Large blobs, write once, rarely read | **Azure Blob Storage** (lifecycle Hot → Cool after 30 days → Cold after 180 days) | Cheapest for large data; short-lived SAS URL; `DocumentReference` metadata owned by the **document service** (MongoDB database `document`) |
| ~~`notification`: reminders~~ → **replaced by ADR-0014 (MongoDB)** | Time-based scheduling, cancellable, retry, multiple replicas fetching work | **PostgreSQL** (same server as scheduling, separate database) | `FOR UPDATE SKIP LOCKED` for the work queue; cancel reminder = update one row; no dependency on bus-specific features |
| ~~`ml`: features, predictions~~ → **replaced by ADR-0014 (MongoDB + Parquet)** | Event-driven writes, reads for training/monitoring | **PostgreSQL** (database `ml`) + MLflow for models | Structured data, aggregate queries for drift |
| `audit` | Append-only, immutable, queried by time/object | **Blob immutable (WORM)** as source of truth + **Log Analytics (KQL)** for query (→ MinIO object lock + Loki) | Truly immutable at the storage layer (no one, including the DB admin, can modify); KQL immediately answers "who viewed record X" |
| `ai`: RAG knowledge base | Hybrid search (BM25 + vector) + rerank | **Azure AI Search** (→ OpenSearch) | Built-in hybrid + semantic reranker, matching the brief's Retrieve → Rerank pipeline; separated from patient data |
| `ml`: experiment, model registry | Small metadata + artifacts | **MLflow** (backend Azure ML → SQLite/file) | MLOps standard, no separate DB needed |
| `ai`: conversations, extraction jobs | Nested documents, TTL | **MongoDB** (database `ai`, same cluster as clinical) | No new engine; TTL index automatically cleans up conversations |

Each service accesses its data store through an abstraction (`IFhirStore`, `IBlobStore`, `IAuditSink`,
`IRetriever`), so changing the engine does not require changing business logic.

## Engine-specific optimizations (to be measured in P4)

- **PostgreSQL**: proper indexes on search-parameter tables (`(resource_type, param, token_code)`,
  `(…, date_start)`); UUIDv7 IDs (time-ordered) so the B-tree does not fragment;
  connection pooling (Flexible Server integrated PgBouncer); `EXPLAIN ANALYZE` for every main
  search query; partition Appointment by month if needed.
- **MongoDB**: shard/partition key `patientId`; compound index by SearchParameter; projection
  to avoid returning the whole large document; read preference `secondaryPreferred` for history queries.
- **Redis**: short TTL + event-based invalidation, limit key size; measure cache hit ratio.
- **Blob**: direct upload from the client via SAS (does not go through the API), lifecycle policy reduces cost.
- **AI Search**: chunks of 500–800 tokens with overlap, security trimming by user
  permissions directly in the query.

## Alternatives considered

- **One PostgreSQL for everything** (like the PoC): cheapest and simplest, sufficient for capstone scale. Rejected because it is
  not optimized for immutable audit and hybrid RAG. Still the fallback for `clinical`
  (the `SmartHospital.Fhir.Postgres` implementation works for both).
- **Cosmos DB (NoSQL API) for clinical**: scales very well but has an Azure-specific API, is hard to run
  locally, and causes vendor lock-in; the MongoDB API runs anywhere.
- **Azure Health Data Services**: see ADR-0003.
- **pgvector for RAG**: cheap, but you must implement BM25 + rerank yourself; AI Search has them built in.
- **Azure Data Explorer for audit**: powerful queries but high fixed cost relative to the audit
  volume of a capstone project; Log Analytics is billed per GB so it is cheaper at small scale.

## Consequences

- 7 data store types (PostgreSQL, MongoDB, Redis, Blob, Log Analytics/Loki, AI Search, MLflow) → backup/restore and monitoring are required for each (P4). Estimated monthly
  cost will be in the FinOps report; Azure AI Search Basic is the largest fixed cost.
- No cross-store transactions: synchronize via events (outbox) and eventual consistency.
- Deployment order: PostgreSQL (scheduling) → MongoDB (clinical) → Blob → audit → AI Search → Redis.
