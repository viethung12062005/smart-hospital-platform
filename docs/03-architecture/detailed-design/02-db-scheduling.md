# 02 — DB design: scheduling (PostgreSQL 16)

Database `scheduling`, schema `scheduling`. Azure Database for PostgreSQL Flexible Server (local: `postgres:16`).
Satisfies: UC-02, UC-03, BR-01..04, ADR-0005, NFR-P1, P2.

## 1. Principles

- **One table per resource type**: typed columns for every field used for search/constraints + `content jsonb` holding the full FHIR
  resource. Typed columns and `content` are always written in the same statement (the service is the only writer).
- **Important business rules are enforced at the DB layer**, not only in code: no slot overlap, at most one active appointment
  per slot, cancellation reason required when cancelling.
- Optimistic concurrency: `version_id` column; updates always have the condition `WHERE id = $id AND version_id = $expected`.

## 2. DDL

```sql
CREATE EXTENSION IF NOT EXISTS btree_gist;          -- for exclusion constraint (uuid =, range &&)
CREATE SCHEMA IF NOT EXISTS scheduling;
SET search_path = scheduling;

-- A doctor's work schedule for a specialty (FHIR Schedule)
CREATE TABLE schedule (
    id               uuid        PRIMARY KEY,
    version_id       integer     NOT NULL DEFAULT 1 CHECK (version_id > 0),
    last_updated     timestamptz NOT NULL,
    active           boolean     NOT NULL,
    practitioner_id  uuid        NOT NULL,
    specialty        text        NOT NULL,
    horizon_start    date        NOT NULL,
    horizon_end      date        NOT NULL,
    content          jsonb       NOT NULL,
    CONSTRAINT ck_schedule_horizon CHECK (horizon_end >= horizon_start)
);
-- Each doctor has at most 1 active Schedule per specialty
CREATE UNIQUE INDEX ux_schedule_active_practitioner_specialty
    ON schedule (practitioner_id, specialty) WHERE active;

-- Weekly schedule template (not FHIR) used to generate Slots
CREATE TABLE schedule_template (
    id            uuid        PRIMARY KEY,
    schedule_id   uuid        NOT NULL REFERENCES schedule (id),
    weekday       smallint    NOT NULL CHECK (weekday BETWEEN 1 AND 7),   -- ISO 8601: 1 = Monday
    start_time    time        NOT NULL,                                   -- local time Asia/Ho_Chi_Minh
    end_time      time        NOT NULL,
    slot_minutes  smallint    NOT NULL CHECK (slot_minutes IN (10, 15, 20, 30)),
    valid_from    date        NOT NULL,
    valid_to      date,
    created_at    timestamptz NOT NULL,
    created_by    text        NOT NULL,
    CONSTRAINT ck_template_time  CHECK (end_time > start_time),
    CONSTRAINT ck_template_valid CHECK (valid_to IS NULL OR valid_to >= valid_from)
);
CREATE INDEX ix_template_schedule ON schedule_template (schedule_id);

-- Examination time slots (FHIR Slot)
CREATE TABLE slot (
    id               uuid        PRIMARY KEY,
    version_id       integer     NOT NULL DEFAULT 1 CHECK (version_id > 0),
    last_updated     timestamptz NOT NULL,
    schedule_id      uuid        NOT NULL REFERENCES schedule (id),
    practitioner_id  uuid        NOT NULL,                 -- copied from schedule to filter without a join
    specialty        text        NOT NULL,                 -- copied from schedule
    status           text        NOT NULL CHECK (status IN ('free', 'busy', 'busy-unavailable')),
    start_at         timestamptz NOT NULL,
    end_at           timestamptz NOT NULL,
    content          jsonb       NOT NULL,
    CONSTRAINT ck_slot_period CHECK (end_at > start_at),
    -- No two overlapping slots within the same Schedule
    CONSTRAINT ex_slot_no_overlap EXCLUDE USING gist (schedule_id WITH =, tstzrange(start_at, end_at) WITH &&)
);
-- Hottest query (500 RPS at 07:00): find free slots by specialty and date range
CREATE INDEX ix_slot_free_by_specialty ON slot (specialty, start_at) WHERE status = 'free';
-- A doctor's schedule (schedule management screen, filter by doctor)
CREATE INDEX ix_slot_by_practitioner ON slot (practitioner_id, start_at);

-- Appointment (FHIR Appointment)
CREATE TABLE appointment (
    id               uuid        PRIMARY KEY,
    version_id       integer     NOT NULL DEFAULT 1 CHECK (version_id > 0),
    last_updated     timestamptz NOT NULL,
    status           text        NOT NULL CHECK (status IN ('booked', 'arrived', 'fulfilled', 'cancelled', 'noshow')),
    slot_id          uuid        NOT NULL REFERENCES slot (id),
    patient_id       uuid        NOT NULL,
    practitioner_id  uuid        NOT NULL,
    specialty        text        NOT NULL,
    start_at         timestamptz NOT NULL,
    end_at           timestamptz NOT NULL,
    channel          text        NOT NULL CHECK (channel IN ('web', 'chatbot', 'staff')),
    booked_by        text        NOT NULL,                 -- OIDC subject of the booker
    cancel_reason    text,
    rescheduled_from uuid        REFERENCES appointment (id),
    content          jsonb       NOT NULL,
    CONSTRAINT ck_appointment_cancel_reason CHECK ((status = 'cancelled') = (cancel_reason IS NOT NULL))
);
-- BR-01 at the DB layer: at most one active appointment per slot (final safeguard against double-booking)
CREATE UNIQUE INDEX ux_appointment_one_active_per_slot
    ON appointment (slot_id) WHERE status IN ('booked', 'arrived', 'fulfilled');
CREATE INDEX ix_appointment_patient_start      ON appointment (patient_id, start_at DESC);
CREATE INDEX ix_appointment_practitioner_start ON appointment (practitioner_id, start_at);
-- No-show marking job only scans appointments still 'booked'
CREATE INDEX ix_appointment_booked_end         ON appointment (end_at) WHERE status = 'booked';

-- Version history (FHIR _history) for Schedule and Appointment
CREATE TABLE resource_history (
    resource_type text        NOT NULL CHECK (resource_type IN ('Schedule', 'Appointment')),
    id            uuid        NOT NULL,
    version_id    integer     NOT NULL,
    last_updated  timestamptz NOT NULL,
    operation     text        NOT NULL CHECK (operation IN ('create', 'update')),
    changed_by    text        NOT NULL,
    content       jsonb       NOT NULL,
    PRIMARY KEY (resource_type, id, version_id)
);

-- Transactional outbox: event written in the same transaction as data changes
CREATE TABLE outbox (
    id            uuid        PRIMARY KEY,                -- = CloudEvents id
    event_type    text        NOT NULL,                   -- for example shp.scheduling.appointment-booked.v1
    subject       text        NOT NULL,                   -- for example Appointment/{id}
    payload       jsonb       NOT NULL,                   -- full CloudEvent
    occurred_at   timestamptz NOT NULL,
    published_at  timestamptz,
    attempts      smallint    NOT NULL DEFAULT 0,
    last_error    text
);
CREATE INDEX ix_outbox_unpublished ON outbox (occurred_at) WHERE published_at IS NULL;

-- Idempotency for $book, $cancel, $reschedule
CREATE TABLE idempotency_key (
    subject        text        NOT NULL,                  -- OIDC subject of the caller
    key            text        NOT NULL CHECK (length(key) BETWEEN 8 AND 128),
    operation      text        NOT NULL,                  -- for example 'Appointment/$book'
    request_hash   bytea       NOT NULL,                  -- SHA-256 of the canonicalized body
    resource_id    uuid        NOT NULL,                  -- appointment ID allocated before processing
    created_at     timestamptz NOT NULL,
    PRIMARY KEY (subject, key)
);
CREATE INDEX ix_idempotency_created ON idempotency_key (created_at);

-- Inbox: prevent duplicate processing of incoming events (for example EncounterFinished)
CREATE TABLE inbox (
    consumer      text        NOT NULL,
    message_id    uuid        NOT NULL,
    processed_at  timestamptz NOT NULL,
    PRIMARY KEY (consumer, message_id)
);
```

> Slot does **not** store version history: the volume is large (~1.2 million/year) and slot status changes are already traceable through
> Appointment history and audit.

## 3. Main write operations

### 3.1 `$book` (one transaction, READ COMMITTED isolation level)

| Step | SQL | Result on failure |
|---|---|---|
| 1. Acquire idempotency key (appointment ID allocated in advance) | `INSERT INTO idempotency_key (...) VALUES (...) ON CONFLICT DO NOTHING RETURNING resource_id` | No row returned → read the old record: different `request_hash` → **422** `idempotency-key-reused`; same → `ROLLBACK`, return the old appointment (**200**). If a request with the same key is running in parallel, the `INSERT` waits for the other request to commit before deciding |
| 2. Serialize by patient | `SELECT pg_advisory_xact_lock(hashtextextended('appt-patient:' \|\| $patient_id, 0))` | — |
| 3. Check BR-03 | `SELECT count(*) FROM appointment WHERE patient_id = $p AND status = 'booked' AND start_at > now()` | ≥ 3 → **422** `max-future-appointments` |
| 4. Acquire slot (compare-and-set, no long-held lock) | `UPDATE slot SET status = 'busy', version_id = version_id + 1, last_updated = now(), content = jsonb_set(content, '{status}', '"busy"') WHERE id = $slot AND status = 'free' AND start_at > now() AND start_at < now() + interval '30 days' RETURNING practitioner_id, specialty, start_at, end_at` | 0 rows → re-read the slot to distinguish: does not exist **404**, outside the 30-day window **422** `outside-booking-window`, already booked **409** `slot-unavailable` |
| 5. Create appointment | `INSERT INTO appointment (...)` | Violates `ux_appointment_one_active_per_slot` (SQLSTATE 23505) → **409** (never happens if step 4 is correct; this is the final safeguard) |
| 6. History + outbox | `INSERT INTO resource_history ...; INSERT INTO outbox ... ('shp.scheduling.appointment-booked.v1')` | — |
| 7. `COMMIT` | | |

Reschedule (`$reschedule`) = steps 1–6 for the new slot + cancel the old appointment (conditional update on `version_id`) in the **same
transaction**, emitting `appointment-rescheduled.v1`. A failure at any step rolls back everything, and the old appointment remains unchanged.

### 3.2 `$cancel`

```sql
UPDATE appointment
   SET status = 'cancelled', cancel_reason = $reason, version_id = version_id + 1, last_updated = now(), content = $content
 WHERE id = $id AND version_id = $expected AND status = 'booked'
   AND ($is_staff OR start_at - now() >= interval '2 hours')        -- BR-02
RETURNING slot_id, start_at;
-- return the slot to free if cancelled before the appointment time
UPDATE slot SET status = 'free', version_id = version_id + 1, last_updated = now(), content = jsonb_set(content, '{status}', '"free"')
 WHERE id = $slot_id AND status = 'busy' AND start_at > now();
-- + resource_history + outbox ('shp.scheduling.appointment-cancelled.v1')
```

### 3.3 Scheduled jobs

| Job | Schedule | Main SQL | Note |
|---|---|---|---|
| Generate slots | 01:00 daily (Vietnam time) | `INSERT INTO slot ... ON CONFLICT DO NOTHING` for each weekly template, days from `horizon_end + 1` to today + 30 | Exclusion constraint blocks duplicates; safe to rerun |
| Mark no-show | every 5 minutes | `UPDATE appointment SET status = 'noshow' ... WHERE id IN (SELECT id FROM appointment WHERE status = 'booked' AND end_at < now() - interval '30 minutes' LIMIT 500 FOR UPDATE SKIP LOCKED)` | Emits `appointment-status-changed.v1` |
| Publish outbox | continuous (1-second poll) | `SELECT ... FROM outbox WHERE published_at IS NULL ORDER BY occurred_at LIMIT 100 FOR UPDATE SKIP LOCKED` | Multiple replicas run in parallel without duplication |
| Cleanup | 02:00 daily | delete published `outbox` > 7 days, `idempotency_key` > 24 hours, `free` slots past > 90 days | |

## 4. Main read queries and indexes used

| Query | Simplified SQL | Index |
|---|---|---|
| Find free slots (NFR-P1) | `WHERE status = 'free' AND specialty = $s AND start_at >= $from AND start_at < $to [AND practitioner_id = $p] ORDER BY start_at LIMIT $n` | `ix_slot_free_by_specialty` (partial: contains only free slots so it is small) |
| Patient appointments | `WHERE patient_id = $p ORDER BY start_at DESC` | `ix_appointment_patient_start` |
| Doctor appointments by day | `WHERE practitioner_id = $d AND start_at >= $day AND start_at < $day + 1` | `ix_appointment_practitioner_start` |
| Slots of a Schedule | `WHERE schedule_id = $s AND tstzrange(start_at, end_at) && $range` | GiST of `ex_slot_no_overlap` |

All the above queries are checked with `EXPLAIN (ANALYZE, BUFFERS)` on year-1 synthetic data (task SCH-03), and results
are stored in the performance document.

## 5. Capacity, retention, permissions

| Item | Value |
|---|---|
| Year-1 size | slot ~1.2 million rows (~1.2 GB), appointment ~330k (~0.5 GB), history ~1 GB |
| Partition | Not needed at this scale; consider partitioning `slot`/`appointment` by month when > 50 million rows |
| Backup | 7-day PITR (Flexible Server); prod enables geo-redundant backup |
| DB role | Entra principal of managed identity `id-<prefix>-scheduling` (created with `pgaadauth_create_principal` after apply, runbook in `shp-infra/modules/postgres/README.md`). Currently this principal owns the schema in database `scheduling` because Flyway runs inside the service (see the Migration row). Goal: separate `scheduling_migrator` (only the migration Job has DDL) and leave the service with only `SELECT/INSERT/UPDATE`, and `DELETE` on `outbox`, `idempotency_key`, `inbox`, `slot` |
| Migration | Flyway (`db/migration`, `V1__...sql`) runs when the service starts, with locking so multiple replicas do not run it twice; backward-compatible changes only (expand → migrate → contract) for zero-downtime deploy |
| Connection | Local: user/password from `dev/.local`. Azure: **no password** (server disables password auth). JDBC uses the plugin `com.azure:azure-identity-extensions` (`authenticationPluginClassName=com.azure.identity.extensions.jdbc.postgresql.AzurePostgresqlAuthenticationPlugin`), obtains an Entra token with the Workload Identity of the `scheduling` ServiceAccount; `sslmode=require`; username is the managed identity name. Values are filled in `shp-gitops/envs/<env>/scheduling/values.yaml` from Terraform outputs |

## 6. Design verification (already run)

The DDL in section 2 was run on PostgreSQL 16.15 together with the following checks (26/10/2026):

| Check | Result |
|---|---|
| Apply DDL | Successful, no errors |
| Create overlapping slots in the same Schedule | Blocked by `ex_slot_no_overlap` |
| `cancelled` appointment without a reason | Blocked by `ck_appointment_cancel_reason` |
| **50 concurrent sessions booking the same slot** using the SQL in section 3.1 | **Exactly 1 appointment**, slot `busy`, `version_id = 2` |
| Insert a second active appointment into an already-booked slot (skipping the slot acquisition step) | Blocked by `ux_appointment_one_active_per_slot` |
| `EXPLAIN` of the free-slot query | `Index Scan using ix_slot_free_by_specialty` |
