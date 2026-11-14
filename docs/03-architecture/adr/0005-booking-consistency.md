# ADR-0005: Prevent double-booking and duplicate requests

## Status
Accepted (2026-10-26) — approved by the Solution Architect/PM together with gate G3.

## Context
A common approach is creating an Appointment without locking the Slot → two patients can book the same slot. Mobile
clients often retry on timeout → create a duplicate appointment.

## Decision
`POST /fhir/Appointment/$book` in **one Postgres transaction**:
1. Acquire the idempotency key (`INSERT ... ON CONFLICT DO NOTHING`).
2. Advisory lock per patient (serialize the BR-03 check: at most 3 future appointments).
3. **Compare-and-set** on the slot: `UPDATE slot SET status='busy' WHERE id=@id AND status='free' ...`; 0 rows affected →
   someone booked first → `409` (no long-held lock, no prior read needed).
4. Create the Appointment, write history and the `appointment-booked` event into the outbox.
5. **Final safeguard at the DB layer:** `UNIQUE INDEX ON appointment(slot_id) WHERE status IN ('booked','arrived','fulfilled')` and
   an exclusion constraint preventing overlapping slots — correct even if the code has bugs.

The `Idempotency-Key` header is mandatory: the result is stored by (user, key); a repeated request returns the same
old Appointment instead of creating a new one.

## Alternatives considered
- `SELECT ... FOR UPDATE` (pessimistic): also correct, but holds locks longer under high load.
- Distributed lock with Redis: adds a dependency and another failure mode.

## Consequences
Verified on PostgreSQL 16: 50 concurrent sessions booking the same slot → exactly 1 appointment
(see `detailed-design/02-db-scheduling.md` §6). The corresponding integration test (SCH-13) runs in CI.
