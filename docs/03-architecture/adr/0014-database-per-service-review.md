# ADR-0014: Re-review of each service's database — PostgreSQL kept only for scheduling

## Status
**Accepted (2026-10-28)** — PM approved ("approve all designs you proposed"). Replaces the `notification` and `ml` rows of [ADR-0008](0008-polyglot-persistence.md). Implemented in Java per [ADR-0015](0015-java-for-all-services.md).

## Context
The PM commented: "always PostgreSQL, must optimize the DB". Currently PostgreSQL serves 3 services (scheduling, notification, ml) and the shared libraries just written (outbox, inbox, idempotency) also only have PostgreSQL versions.
The ADR-0008 principle remains: **do not add a new engine unless it is proven to do substantially better what the current engine cannot do**, because every engine adds operational cost, backup, monitoring, Azure spend, and another base library for devs.

## Actual load figures (workload-analysis §2–3)
| Service | Volume | Peak | Year-1 capacity |
|---|---|---|---|
| scheduling | ~900 bookings/day, ~20,000 slot searches/day | 500 RPS search, 50 RPS booking (high contention) | ~3 GB |
| notification | ~2,000 reminders/day | 200/minute | a few hundred MB |
| ml | ~900 predictions/day; ~330,000 appointment_fact/year | 50 RPS predictions (at the same time as booking) | < 1 GB |

Conclusion from the figures: notification and ml are **small in volume**; neither needs a specialized big-data engine (ClickHouse, Cassandra, Kafka...). The right question is "which access pattern fits which engine", not "which engine is fastest".

## Evaluation of each service
| Service | Actual access pattern | Options considered | Conclusion |
|---|---|---|---|
| **scheduling** | Contended transactions: prevent 2 people booking 1 slot, max 3 future appointments/patient, no doctor schedule overlap | MongoDB (unique index + transaction), Azure SQL, keep PostgreSQL | **Keep PostgreSQL.** Constraints are at the DB layer (`btree_gist` exclusion for overlaps, unique partial index on `appointment(slot_id)`, advisory lock) so they are correct even if the code is wrong; verified 50 concurrent bookings → exactly 1 succeeds. MongoDB can do it with a unique index but loses the time-range overlap constraint; Azure SQL is equivalent but heavier to run locally. This is the **only** service that truly needs relational ACID |
| **notification** | Time-based work queue: create, cancel, fetch due work (multiple replicas), retry, deduplication, auto-cleanup after N days; each reminder has several send attempts | Redis (sorted set), MongoDB, RabbitMQ delayed, keep PostgreSQL | **MongoDB** (already used for clinical/document/ai). One document/reminder, send attempts embedded inside; fetch work with `findOneAndUpdate({status:'pending', dueAt:{$lte:now}})` with `lockedUntil` (atomic, multi-replica, auto-reclaim when a pod dies); dedup with unique index `(appointmentId, kind)`; **TTL index auto-deletes** old reminders. Redis is faster but has weak durability (up to ~1 second AOF loss, or needs Premium) while reminders are state data that must be durable; RabbitMQ delayed is hard to cancel per reminder. At 200 reminders/minute, every option is more than enough — choose by **fit and number of engines to operate** |
| **ml** | (1) Per-patient feature state, updated by events (`prior_noshow`, `prior_booked`), read by key at prediction time; (2) prediction log append-only, queried by model_version + time to compute drift/AUC; (3) immutable, versioned training set | ClickHouse/Data Explorer (columnar), Redis, keep PostgreSQL, MongoDB + Parquet | **MongoDB + Parquet on Blob.** (1) `patient_stats` is a document keyed by `patientId`, updated atomically with `$inc`, fast key reads; (2) prediction log as a **time-series collection** (good compression, time queries, TTL) — **need to check availability on Cosmos DB for MongoDB vCore**, if not available use a regular collection + index `(modelVersion, predictedAt)`; (3) training snapshot exported to **Parquet** on Blob, content hash recorded in MLflow (per 16-mlops: dataset versioning), drift computed with pandas/DuckDB (embedded libraries, not a server). ClickHouse/ADX rejected: 330k rows/year does not need a columnar engine, and a new engine violates the principle |
| clinical, document, ai | (unchanged) | — | MongoDB as ADR-0008 |
| gateway (rate limit) | Counter by `sub`, fast expiry | — | Redis as ADR-0008 |
| audit | Append-only, immutable | — | Blob WORM + Log Analytics as ADR-0008 |

## Result if approved
- The **number of engine types is unchanged** (PostgreSQL, MongoDB, Redis, Blob, Log Analytics/Loki, AI Search, MLflow); PostgreSQL serves only **one service** → smaller Azure PostgreSQL server (small burstable/GP), no more `notification`/`ml` databases.
- Base libraries: the PostgreSQL version (`Shp.Data.Postgres`, already written) is used only for scheduling; the **MongoDB version** (outbox/inbox/idempotency, tasks SHR-07/10/12) is used by 5 services, so it is the more important version.

## Per-engine optimizations (measured in Phase 6, not merely asserted)
| Engine | Work | How measured |
|---|---|---|
| PostgreSQL (scheduling) | Index matching slot search queries (`(practitioner_id, start) WHERE status='free'`); UUIDv7; PgBouncer; partition `appointment` by month if needed; read replica for slot search; `EXPLAIN (ANALYZE, BUFFERS)` for all main queries | k6: NFR-P1 (500 RPS, p95 ≤ 300 ms), NFR-P2; query plans stored in the repo |
| MongoDB | Shard/partition key `patientId` for clinical data; compound index by SearchParameter; projection; `secondaryPreferred` for history; partial index for outbox/due reminders; TTL for inbox/reminders/conversations | `explain("executionStats")`: `docsExamined/nReturned` ratio ≈ 1; k6: NFR-P3 |
| Redis | 5-second TTL for free slots, event-based invalidation; Redis down → read directly from DB | Hit ratio; failure (chaos) testing |
| Blob | Hot → Cool after 30 days → Cold after 180 days; short-lived SAS | Cost report |

## Risks and mitigation
| Risk | Mitigation |
|---|---|
| Time-series collection may not yet be available on Cosmos DB for MongoDB vCore | Check before approving the detailed design; fallback already stated (regular collection + index) |
| Multi-document transactions on vCore | Already a dependency of clinical (outbox + resource in the same transaction); verified in task SCF/SHR-10 |
| notification loses `SKIP LOCKED` | Replace with atomic work fetch + `lockedUntil`; multi-replica test |
| Added load on the shared MongoDB cluster | Separate databases for notification/ml; monitor RU/CPU; split clusters if contention is measured |

## Impact if approved (done in a later step, logged)
Edit: `04-db-notification-ml.md` (rewrite as Mongo collection + Parquet), `02-db-scheduling.md` (unchanged), `03-db-clinical-document.md` (add `notification`, `ml` databases), ADR-0008/0011 and `overview.md`;
`dev/chart/shp-local-deps` (remove `notification`, `ml` databases from Postgres, add Mongo initialization); tasks NTF-01..05 and ML-11 (and dependent tasks), SCF-xx; data IaC module (IAC-09..16); Mongo base library (SHR-07/10/12) raised to high priority.
