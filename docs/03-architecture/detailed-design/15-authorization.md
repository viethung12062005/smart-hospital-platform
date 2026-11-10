# 15 — Authorization design

Implements the [access control matrix](../../02-requirements/access-control-matrix.md) (NFR-SEC2) and BR-05, BR-06, BR-07.
Combines **RBAC** (roles in the token) and **ABAC** (the relationship between the caller and the data).

## 1. Control layers

| Layer | What it does | Where |
|---|---|---|
| 1. Authentication | Verify signature, `iss`, `aud`, `exp`; staff must have `amr` containing `mfa` (NFR-SEC1) | Gateway **and** each service |
| 2. Role | `roles` ∈ {`patient`,`doctor`,`nurse`,`admin`,`dpo`} from the claim; missing → 403 | Service (common library) |
| 3. FHIR identity | `sub` → `identity_links` → `fhirUser` (60-second cache) and valid `profile_links` → `linkedProfiles[]` (profile, scope); not linked or `disabled` → 403 `forbidden` | Service, calls clinical |
| 4. Resource policy | Decision function by (role, action, resource type, relationship) — section 3 | Owning service |
| 5. Data filtering | Filter enforced in the query (for example `patient = self`) so data outside the scope is never read | Query layer |
| 6. Audit | Every decision (both allow and deny) emits `audit-recorded` | Common library |

The gateway **does not** decide business permissions (only layer 1 and rate limiting). Services do not trust headers added by the gateway.

## 2. Decision model

```
Decision = Authorize(principal, action, resourceType, resourceContext)
principal       = { subject, roles[], fhirUser, mfa }
resourceContext = { patientId?, practitionerId?, encounterStatus?, releasedToPatient?, ... }
Decision        = Allow(purpose) | Deny(code)       purpose ∈ TREAT | ETREAT | HOPERAT | PATRQT
```
**Deny by default.** A request is allowed only when **exactly one rule** matches and all of its conditions hold.

### Predicates used in rules

| Predicate | Definition |
|---|---|
| `own` | `resourceContext.patientId == principal.fhirUser` (Patient) |
| `linked` | `resourceContext.patientId` is in the caller's `linkedProfiles` with an `active`, unexpired link, scope from `booking` and above (book, cancel, reschedule, view appointments and administrative records) |
| `linked-records` | like `linked`, scope `records` or `full` (also visit results and released documents) |
| `guardian` | link has `relationship=guardian` (father, mother, guardian of a person under 16) |
| `self-practitioner` | `resourceContext.practitionerId == principal.fhirUser` |
| `care` | Has a care relationship (section 4) between `principal.fhirUser` (Practitioner) and `resourceContext.patientId` |
| `breakglass` | Has an unexpired break-glass session for the pair (practitioner, patient) |
| `released` | The document has `released-to-patient` |
| `encounter-finished` | The Encounter of the resource is in `finished` status |
| `before-2h` | `appointment.start - now ≥ 2 hours` |
| `unfinished-encounter` | Encounter is not yet `finished` |
| `not-care` | The doctor **does not** have `care` with the patient (condition for starting break-glass) |

## 3. Matrix as data (source for test generation)

The matrix is stored in the repo at `contracts/authz/matrix.yaml`, and is **both configuration for the policy library and input for automatic
test generation** (each cell → at least one test: Allow and Deny). Excerpt:

```yaml
rules:
  - { id: PAT-READ-OWN,   resource: Patient,     action: read,   role: patient, when: [own],                purpose: PATRQT }
  - { id: PAT-READ-DOC,   resource: Patient,     action: read,   role: doctor,  when: [care],               purpose: TREAT }
  - { id: PAT-READ-DOC-BG,resource: Patient,     action: read,   role: doctor,  when: [breakglass],         purpose: ETREAT }
  - { id: PAT-READ-NUR,   resource: Patient,     action: read,   role: nurse,   when: [],                    purpose: TREAT }
  - { id: PAT-UPD-OWN,    resource: Patient,     action: update, role: patient, when: [own], fields: [telecom, address, contact, communication] }
  - { id: APPT-BOOK-OWN,  resource: Appointment, action: book,   role: patient, when: [own] }
  - { id: APPT-BOOK-NUR,  resource: Appointment, action: book,   role: nurse,   when: [] }
  - { id: APPT-CANCEL-PAT,resource: Appointment, action: cancel, role: patient, when: [own, before-2h] }
  - { id: APPT-CANCEL-NUR,resource: Appointment, action: cancel, role: nurse,   when: [] }
  - { id: APPT-READ-DOC,  resource: Appointment, action: read,   role: doctor,  when: [self-practitioner] }
  - { id: ENC-READ-PAT,   resource: Encounter,   action: read,   role: patient, when: [own, encounter-finished], purpose: PATRQT }
  - { id: ENC-WRITE-DOC,  resource: Encounter,   action: update, role: doctor,  when: [care, unfinished-encounter] }
  - { id: COND-WRITE-DOC, resource: Condition,   action: create, role: doctor,  when: [care] }
  - { id: DOC-DL-PAT,     resource: Document,    action: download, role: patient, when: [own, released], purpose: PATRQT }
  - { id: DOC-DL-DOC,     resource: Document,    action: download, role: doctor,  when: [care] }
  - { id: DOC-REL-DOC,    resource: Document,    action: release,  role: doctor,  when: [care] }
  - { id: AUDIT-READ-DPO, resource: Audit,       action: read,   role: dpo,     when: [], purpose: HOPERAT }
  - { id: AUDIT-READ-ADM, resource: Audit,       action: read,   role: admin,   when: [], hide: [reason] }
  - { id: BG-START-DOC,   resource: BreakGlass,  action: start,  role: doctor,  when: [not-care] }
```
Beyond the rows above: **admin has no row for clinical data**, `dpo` only has rows for `Audit`.

**Test generation (SHR-09):** for each `(resource, action)` in the matrix, the test suite calls the corresponding endpoint with **all 5 roles × combinations of
conditions**; Allow is expected only in the matching row, every other cell must be 403 or 404 and have a `denied` audit record. Adding a new endpoint without
a row in `matrix.yaml` → the test "every endpoint must have a rule" fails (blocks forgetting authorization).

## 4. Care relationship (`care`)

A doctor has `care` with a patient when **a valid source** exists in `care_relationships` (clinical):

| Source | Created by | Validity |
|---|---|---|
| `appointment` | E1 (booking with that doctor) | from booking to `start + 365 days` |
| `encounter` | E4/`$start` (doctor is the attender) | to `period.start + 365 days` |

- E2/E3 (cancel/reschedule) remove the corresponding `appointment` source; if other sources remain, the relationship remains.
- `validUntil = max(validity of the sources)`. Lookup: `findOne({_id: "<prac>|<pat>", validUntil > now})` — one read by `_id`.
- A Nurse **does not** use `care` (may view any patient within the operational scope at the counter); every access is still audited.
- Consistency: the projection updates from events, so it may lag a few seconds after booking; this is acceptable (after booking, a doctor
  does not need to open the record immediately). If it is needed immediately, the doctor uses break-glass.

## 5. Break-glass (UC-10)

1. A doctor without `care` reads a record → **403 `break-glass-required`** (already audited as `denied`).
2. `POST /break-glass` with `reason` of 20–500 characters → **60-minute** session (TTL), `purpose = ETREAT`.
3. During the session, the `*-READ-DOC-BG` rules allow **reading** (writing diagnoses/vital signs is not allowed — writing still requires `care`).
4. Every read is audited with `purposeOfUse = ETREAT` and `reason`; the audit service notifies the DPO within ≤ 5 minutes (AC-10.1); the DPO marks
   it justified/unjustified (A5). The `unjustified` rate is a monitored indicator.
5. No automatic extension; after expiry it must be started again with a new reason.

## 6. Patients accessing their own data

- `own` matches `fhirUser` with the resource's `patientId`; **never** take `patientId` from the client's body/query.
- Search is always **forced** with the filter `patient = fhirUser` (do not report an error if the client sends a different value; override it and write audit).
- You only see: records, appointments, `Encounter`/`Observation`/`Condition` belonging to a **finished** visit, **released** documents (AC-05.3).
- Accessing a resource that is not yours → **404** (does not reveal existence).

## 7. Service-to-service and AI

| Case | Mechanism |
|---|---|
| Call to `/internal/*` | Workload identity token, `aud` = target service, app role `internal.<service>`; each internal endpoint has a **list of allowed services** (checked by role, and NetworkPolicy blocks at the network layer) |
| AI reads patient data | **RFC 8693 token exchange** (Keycloak) → a token carrying the user's identity, `aud` = clinical/scheduling; the matrix rules apply unchanged to that user |
| AI writes (creates `Observation` from extraction, books via chatbot) | Same mechanism, **only after the user confirms** (AI6, AI8) |
| Background jobs (slot generation, no-show, cleanup) | Service identity; audit `actor.role = system`, `purposeOfUse = HOPERAT` |

## 8. Identity management

- Staff: accounts invited by Admin via email in Keycloak, TOTP or WebAuthn required (do not use SMS as a second factor); `identity_links` links `sub` ↔ `Practitioner`, checking that `PractitionerRole.code` matches the role (`doctor`↔`doctor`, `nurse`↔`nurse`).
- Patients: self-register on the Keycloak page using a phone number (SMS OTP) or email (default role `patient`), then link the `Patient` via `POST /registration` (C1).
- Relative profiles: `profile_links` (ADR-0018). Children under 16: the guardian declares, with full authority until the 16th birthday. Adults: only when **they themselves consent** (reading an OTP sent to their number, or at the counter with documents), scope `booking` or `records`, expires after 12 months, revocation within ≤ 60 seconds. A delegate cannot view unreleased notes, cannot change full name, date of birth, or gender, and cannot change AI consent. Audit every access through the link with `onBehalfOf`.
- Claiming an existing record (C27, C28): only link "self" when the OTP sent to the number **stored in the record** is correct, the record is not yet linked to any account, and a notification is sent to that number.
- Locking: `PUT /admin/users/{sub} {disabled:true}` → writes `identity_links.disabled`; within at most 60 seconds (cache expiry) every service rejects; an unexpired token (≤ 15 minutes) is still rejected because layer 3 checks `disabled`.
- Changing a role: takes effect on the next token (≤ 15 minutes); locking is immediate (60 seconds) as above.

## 9. Points to verify (included in tests and demo scenarios)

| Scenario | Expectation |
|---|---|
| Patient A reads `Patient/B`, B's `Appointment`, B's documents | 404, audit `denied` |
| Patient cancels with 90 minutes left | 422 `cancellation-too-late`; a Nurse can cancel |
| Doctor without Care opens a record | 403 `break-glass-required` → break-glass → can read, DPO receives an alert within ≤ 5 minutes |
| Admin reads `Encounter` | 403 |
| Doctor writes `Condition` for a patient without Care (even during break-glass) | 403 |
| Expired token / missing MFA (staff) | 401 / 403 |
| Locked account, unexpired token | 403 within ≤ 60 seconds |
| AI summarizes a patient's record that the doctor is not authorized for | 403, no data enters the prompt |
| Adding an endpoint not in `matrix.yaml` | CI test fails |
