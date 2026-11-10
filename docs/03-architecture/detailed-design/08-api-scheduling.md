# 08 — API scheduling

Common conventions: [07-api-conventions.md](07-api-conventions.md). DB: [02-db-scheduling.md](02-db-scheduling.md).
Permission notation: **P** Patient, **D** Doctor, **N** Nurse, **A** Admin. `own` = only one's own data.

## 1. Endpoint summary

| # | Method + path | Purpose | Permission | Use case |
|---|---|---|---|---|
| S1 | `POST /fhir/Schedule` | Create a doctor's work schedule | N, A | UC-02 |
| S2 | `GET /fhir/Schedule/{id}` | Read a schedule | N, A; D (own) | UC-02 |
| S3 | `GET /fhir/Schedule` | Find schedules | N, A; D (own) | UC-02 |
| S4 | `PUT /fhir/Schedule/{id}` | Edit a schedule (enable/disable, notes) | N, A | UC-02 |
| S5 | `PUT /api/v1/scheduling/schedules/{id}/templates` | Set the weekly schedule template | N, A | UC-02 |
| S6 | `GET /api/v1/scheduling/schedules/{id}/templates` | View the weekly schedule template | N, A; D (own) | UC-02 |
| S7 | `POST /api/v1/scheduling/schedules/{id}/slots:generate` | Generate slots from the template for a date range | N, A | UC-02 |
| S8 | `GET /fhir/Slot` | Find slots | P, N, A, D | UC-03 |
| S9 | `POST /fhir/Slot/{id}/$block` | Block a slot | N, A | UC-02 |
| S10 | `POST /fhir/Slot/{id}/$unblock` | Unblock a slot | N, A | UC-02 |
| S11 | `POST /fhir/Appointment/$book` | Book an appointment | P (own), N | UC-03 |
| S12 | `GET /fhir/Appointment/{id}` | Read an appointment | P (own), D (own), N | UC-03 |
| S13 | `GET /fhir/Appointment` | Find appointments | P (own), D (own), N | UC-03 |
| S14 | `POST /fhir/Appointment/{id}/$cancel` | Cancel an appointment | P (own, BR-02), N | UC-03 |
| S15 | `POST /fhir/Appointment/{id}/$reschedule` | Reschedule an appointment | P (own), N | UC-03 |
| S16 | `POST /fhir/Appointment/{id}/$check-in` | Check-in | N | UC-04 |
| S17 | `GET /fhir/Appointment/{id}/_history` | Version history | N, A | UC-03 |
| S18 | `GET /internal/v1/appointments/{id}` | Read an appointment (other service) | service `clinical`, `ml` | — |

A doctor **must not** book, cancel, or reschedule; Admin does not view patient appointments (per the [access control matrix](../../02-requirements/access-control-matrix.md)).

## 2. Work schedule

### S1 `POST /fhir/Schedule`
Request (`Schedule`, `id`/`meta` issued by the server):
```json
{
  "resourceType": "Schedule",
  "active": true,
  "actor": [{ "reference": "Practitioner/0192f8a4-...", "display": "Dr. Le Minh Quan" }],
  "specialty": [{ "coding": [{ "system": "https://shp.example.org/fhir/CodeSystem/specialty", "code": "cardiology" }] }],
  "comment": "Examination room 204"
}
```
| Result | |
|---|---|
| 201 | Resource with `id`, `ETag`, `Location`; `planningHorizon` filled by the server (today → +30 days) |
| 400 | `actor` is not exactly 1 element, `specialty` is outside the value set, `Practitioner` does not exist or has no `PractitionerRole` matching the specialty (the server checks via clinical's `/internal/v1/practitioners/{id}/roles`) |
| 409 | An active Schedule already exists for the same doctor and specialty (`ux_schedule_active_practitioner_specialty`) |
| 403 | Role is not N/A |

### S3 `GET /fhir/Schedule`
Parameters: `actor=Practitioner/{id}` · `specialty={code}` · `active=true|false` · `_count` · `_page_token`.
If a doctor calls without their own `actor`, the server adds `actor=<self>`.

### S5 `PUT /api/v1/scheduling/schedules/{id}/templates`
Replaces the Schedule's **entire** weekly template (`If-Match` required on the Schedule).
```json
{
  "templates": [
    { "weekday": 1, "startTime": "08:00", "endTime": "11:30", "slotMinutes": 15, "validFrom": "2026-12-01", "validTo": null },
    { "weekday": 3, "startTime": "13:30", "endTime": "16:30", "slotMinutes": 15, "validFrom": "2026-12-01", "validTo": null }
  ]
}
```
| Result | |
|---|---|
| 200 | `{ "templates": [...], "affectedFutureSlots": 0 }`; does not automatically delete already-generated slots |
| 400 | `startTime ≥ endTime`; `slotMinutes` outside {10,15,20,30}; the time range is not divisible by `slotMinutes`; two templates on the same weekday overlap |
| 412 | Wrong `If-Match` |

### S7 `POST /api/v1/scheduling/schedules/{id}/slots:generate`
```json
{ "from": "2026-12-01", "to": "2026-12-31" }
```
- `to` is at most today + 30 days (BR-04), at most 31 days per call, `from ≥` today.
- Generates slots from effective templates, in local time `Asia/Ho_Chi_Minh`; `ON CONFLICT DO NOTHING` so it is safe to call again.
- 200: `{ "created": 14, "skippedExisting": 0 }` — example AC-02.1: a template of 08:00–11:30 / 15 minutes → **14 `free` slots** per day.
- 400: the date range violates the limit. 404: the Schedule does not exist or `active=false` → 422 `schedule-inactive`.

## 3. Slot

### S8 `GET /fhir/Slot` — find free slots (hot path, NFR-P1)
| Parameter | Type | Note |
|---|---|---|
| `status` | token | default `free` for Patient; N/A/D may use `busy`, `busy-unavailable` |
| `specialty` | token | **required** if there is no `schedule` or `practitioner` |
| `practitioner` | reference | `Practitioner/{id}` |
| `schedule` | reference | `Schedule/{id}` |
| `start` | date | `ge`, `gt`, `le`, `lt`; for example `start=ge2026-12-01&start=lt2026-12-08`; range at most 31 days |
| `_count`, `_page_token` | | keyset by (`start_at`, `id`) |

Response: `Bundle searchset` sorted by ascending `start`; each `Slot` is a shortened form (no `comment`). Patients are always forced to
`status=free` and `start ≥ now`. Redis cache for 5 seconds keyed by the normalized query; invalidated when an `appointment-*` event
occurs for the same doctor/day (SCH-15). 400 if the date range is > 31 days or the specialty/doctor/schedule filter is missing.

### S9 `POST /fhir/Slot/{id}/$block`
Body: `{ "reason": "Doctor on leave" }` (1–200 characters). Header `If-Match`.
| Result | |
|---|---|
| 200 | Slot `busy-unavailable`, `comment = reason` |
| 409 `slot-has-appointment` | The slot is `busy` due to a valid appointment — the appointment must be cancelled/rescheduled first (AC-02.2) |
| 412 | Wrong `If-Match` |

`$unblock`: changes `busy-unavailable → free`; 409 `invalid-state-transition` if the slot is not in that state.

## 4. Appointment

### S11 `POST /fhir/Appointment/$book`
Required header: `Idempotency-Key`. Body is `Parameters`:
```json
{
  "resourceType": "Parameters",
  "parameter": [
    { "name": "slot", "valueReference": { "reference": "Slot/0192f8a4-..." } },
    { "name": "patient", "valueReference": { "reference": "Patient/0192f8a4-..." } },
    { "name": "comment", "valueString": "Chest pain on exertion" },
    { "name": "channel", "valueCode": "web" }
  ]
}
```
- `patient`: a Patient role may only book for **themselves** or for a profile in `linkedProfiles` (children, authorized relatives, scope from `booking`), otherwise 403 (AC-03.6); booking on behalf is audited with `onBehalfOf` (AC-03.7). The limit of 3 future appointments (BR-03) is counted **per profile**. A Nurse books for any patient (`channel=staff` is forced).
- `comment` optional, ≤ 500 characters; `channel` ∈ {`web`,`chatbot`,`staff`} (Patient may only use `web`/`chatbot`).

Result:
| HTTP | Condition | `code` |
|---|---|---|
| **201** | Booking succeeded; returns the `Appointment` (`status=booked`), `Location`, `ETag` | |
| **200** | Same `Idempotency-Key` + same body: returns exactly the appointment that was created (AC-03.2) | |
| 400 | Missing parameter / structurally invalid Parameters | `validation-failed` |
| 403 | Booking for someone else | `forbidden` |
| 404 | Slot or patient does not exist | `not-found` |
| **409** | Slot already booked, blocked, or in the past | `slot-unavailable` — with an `OperationOutcome` containing `extension[suggested-slots]`: the 3 nearest free slots for the same doctor/specialty |
| 422 | Patient already has 3 future appointments (BR-03) | `max-future-appointments` |
| 422 | Slot outside the 30-day window (BR-04) | `outside-booking-window` |
| 422 | Same key, different body (AC-03.3) | `idempotency-key-reused` |
| 428 | Missing `Idempotency-Key` | `idempotency-key-required` |
| 429 | More than 10 times/minute | `rate-limited` |

Side effects: slot → `busy`; write `resource_history`; emit `shp.scheduling.appointment-booked.v1` (same transaction).
Target latency p95 ≤ 800 ms at 50 RPS (NFR-P2). Step-by-step processing: [02-db-scheduling.md](02-db-scheduling.md) §3.1.

### S13 `GET /fhir/Appointment`
| Parameter | Note |
|---|---|
| `patient=Patient/{id}` | Patient: forced = self; D: must be a patient with a care relationship |
| `practitioner=Practitioner/{id}` | D: forced = self |
| `status` | `booked,arrived,fulfilled,cancelled,noshow` (comma-separated = OR) |
| `date` | `ge/le/gt/lt` by `start`; range ≤ 92 days |
| `specialty` | |
| `_sort` | `date` or `-date` (default `date`) |
| `_count`, `_page_token` | keyset |

N is not forced to a filter. At least one of `patient`, `practitioner`, `date` is required.

### S14 `POST /fhir/Appointment/{id}/$cancel`
Header: `Idempotency-Key`, `If-Match`. Body:
```json
{ "resourceType": "Parameters", "parameter": [ { "name": "reason", "valueCode": "patient-request" } ] }
```
| Result | |
|---|---|
| 200 | `Appointment.status=cancelled`; slot → `free` if still before the appointment time; emits `appointment-cancelled.v1` |
| 403 | Patient cancels someone else's appointment |
| 409 `invalid-state-transition` | The appointment is no longer `booked` (already `arrived`, `cancelled`, etc.). A Nurse may cancel an `arrived` appointment with `reason=other` |
| 412 | Wrong `If-Match` |
| 422 `cancellation-too-late` | Patient cancels with **< 2 hours** remaining (BR-02, AC-03.5); a Nurse may always cancel |

### S15 `POST /fhir/Appointment/{id}/$reschedule`
Header: `Idempotency-Key`, `If-Match`. Body: `{ "slot": Reference(Slot), "comment"? }`.
Atomic: book the new slot + cancel the old appointment (`cancelationReason = rescheduled`) + the new appointment has `extension[rescheduled-from]`.
A failure at any step (new slot booked, BR-02 rule with the old appointment, etc.) → **nothing changes**. BR-03 does not count the old appointment being rescheduled.
Result: 201 returns the **new** appointment; errors as in `$book` and `$cancel`. Emits `appointment-rescheduled.v1`.

### S16 `POST /fhir/Appointment/{id}/$check-in`
Nurse only. `If-Match` required. `booked → arrived` within **60 minutes before** to **30 minutes after** `start`,
outside that range → 422 `checkin-out-of-window`. Emits `appointment-checked-in.v1` (clinical listens to create the `Encounter`).

## 5. Internal API

### S18 `GET /internal/v1/appointments/{id}`
Returns the full `Appointment`. Only called from `clinical` (Encounter validation) and `ml`. There is no user permission check
(trusted via service identity); every access is audited with `actor.role = system`.

## 6. State ↔ event mapping

| Action | Event emitted |
|---|---|
| `$book` | `shp.scheduling.appointment-booked.v1` |
| `$cancel` | `shp.scheduling.appointment-cancelled.v1` |
| `$reschedule` | `shp.scheduling.appointment-rescheduled.v1` (+ `appointment-cancelled`/`booked` are **not** emitted separately to avoid duplicate reminders) |
| `$check-in`, no-show job, `fulfilled` (receives `encounter-finished`) | `shp.scheduling.appointment-status-changed.v1` (`checked-in` uses `appointment-checked-in.v1`) |

Each operation also emits `shp.audit.recorded.v1`. Detailed schema: [13-events.md](13-events.md).
