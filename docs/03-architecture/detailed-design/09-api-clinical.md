# 09 — API clinical

Conventions: [07-api-conventions.md](07-api-conventions.md). DB: [03-db-clinical-document.md](03-db-clinical-document.md).
Notation: **P** Patient, **D** Doctor, **N** Nurse, **A** Admin, **DPO**. **Care** = has a care relationship
([15-authorization.md](15-authorization.md)); **BG** = or has a valid break-glass session.

## 1. Endpoint summary

| # | Method + path | Purpose | Permission | UC |
|---|---|---|---|---|
| C1 | `POST /api/v1/registration` | Patient self-registration | new account (OIDC token has no `fhirUser` yet) | UC-01 |
| C2 | `GET /api/v1/me` | Who am I (role, `fhirUser`, linked profiles) | all users | UC-08 |
| C3 | `POST /fhir/Patient` | Nurse creates a record at the counter | N | UC-01 |
| C4 | `GET /fhir/Patient/{id}` | Read a record | P (own), D (Care/BG), N | UC-01 |
| C5 | `PUT /fhir/Patient/{id}` | Edit a record | P (own, administrative fields), N | UC-01 |
| C6 | `POST /fhir/Patient/_search` | Find patients | D, N | UC-06 |
| C7 | `GET /fhir/Patient/{id}/_history` | Version history | N | UC-01 |
| C8 | `POST /fhir/Patient/{id}/$merge` | Merge duplicate records | N | UC-01 |
| C9 | `GET /fhir/Practitioner`, `GET /fhir/Practitioner/{id}`, `GET /fhir/PractitionerRole` | Doctor directory | any authenticated user | UC-03 |
| C10 | `POST /fhir/Practitioner`, `POST /fhir/PractitionerRole`, `PUT /fhir/Practitioner/{id}`, `PUT /fhir/PractitionerRole/{id}` | Staff management | A | UC-08 |
| C11 | `POST /fhir/Encounter/{id}/$start` | Doctor opens the visit | D (Care) | UC-04 |
| C12 | `GET /fhir/Encounter/{id}`, `GET /fhir/Encounter` | Read/find visits | P (own, `finished`), D (Care/BG), N | UC-04 |
| C13 | `PUT /fhir/Encounter/{id}` | Record history/reason for visit | D (Care, before `finished`) | UC-04 |
| C14 | `POST /fhir/Encounter/{id}/$finish` | End the visit | D (Care) | UC-04 |
| C15 | `POST /fhir/Encounter/{id}/$addendum` | Addendum after the visit ends | D (Care) | UC-04 |
| C16 | `POST /fhir/Observation`, `GET /fhir/Observation`, `GET /fhir/Observation/{id}` | Vital signs / results | write: D (Care), N; read: P (own), D (Care/BG), N | UC-04 |
| C17 | `POST /fhir/Condition`, `GET /fhir/Condition`, `PUT /fhir/Condition/{id}` | Diagnosis / underlying conditions | write: D (Care); read: P (own), D (Care/BG), N | UC-04 |
| C18 | `POST /fhir/AllergyIntolerance`, `GET /fhir/AllergyIntolerance`, `PUT /fhir/AllergyIntolerance/{id}` | Allergy | write: D (Care), N; read as above | UC-04 |
| C19 | `GET /api/v1/clinical/patients/{id}/timeline` | Patient timeline | D (Care/BG), N | UC-06 |
| C20 | `POST /api/v1/clinical/break-glass` | Emergency access | D | UC-10 |
| C21 | `GET /api/v1/admin/users`, `POST /api/v1/admin/users`, `PUT /api/v1/admin/users/{subject}` | Link/lock accounts | A | UC-08 |
| C22 | `GET /api/v1/me/profiles` | Profiles managed by the account (self, children, relatives) | P | UC-01 |
| C23 | `POST /api/v1/me/profiles/dependents` | Create a profile for a person under 16 | P | UC-01 |
| C24 | `POST /api/v1/me/profile-links` | Request a link to an adult relative's profile (send OTP to the relative) | P | UC-01 |
| C25 | `POST /api/v1/me/profile-links/{id}/confirm` | Confirm the link with the OTP code | P | UC-01 |
| C26 | `DELETE /api/v1/me/profile-links/{id}` | Revoke the link | P, N | UC-01 |
| C27 | `POST /api/v1/me/claim-record` | Claim an existing record: find a matching record and send an OTP | P | UC-01 |
| C28 | `POST /api/v1/me/claim-record/verify` | Enter the OTP to link an existing record | P | UC-01 |
| C29 | `POST /api/v1/clinical/patients/{id}/profile-links` | Nurse records a relative's authorization at the counter | N | UC-01 |

## 2. Patient

### C1 `POST /api/v1/registration`
The user has created an OIDC account (role `patient`) but has no record yet. Body:
```json
{
  "name": { "family": "Nguyen", "given": ["Van", "An"] },
  "phone": "0901234567",
  "email": "an.nguyen@example.org",
  "gender": "male",
  "birthDate": "1985-03-14",
  "address": { "city": "Hanoi", "district": "Cau Giay" },
  "language": "vi"
}
```
| HTTP | Condition | `code` |
|---|---|---|
| 201 | Creates the `Patient`, generates the MRN, creates `identity_links` for `sub`; body `{ "patientId", "mrn" }` | |
| 400 | `phone` is not a valid VN number; `birthDate` is in the future or age > 120 | `validation-failed` |
| 409 | This token already has a record | `already-registered` |
| **422** | A record already exists with the **same phone number + date of birth** (AC-01.2): **do not create a new one**, return `{ "code": "duplicate-patient-suspected" }` with the suggestion "I have visited the hospital before" (C27) or instructions to go to the counter so a nurse can verify; do not reveal data from the duplicate record | `duplicate-patient-suspected` |

### C3 `POST /fhir/Patient`
A Nurse creates a record for a patient who has no account. Same duplicate check; the Nurse may send `?forceCreate=true` + the header
`X-Duplicate-Override-Reason` after verifying in person (audited). 201 returns the `Patient` with `identifier[mrn]`.

### C5 `PUT /fhir/Patient/{id}`
`If-Match` required. A Patient may only edit `telecom`, `address`, `contact`, `communication`; editing `name`, `birthDate`,
`gender`, `identifier` → 403. The **CCCD number** (`identifier[cccd]`) may only be set by a Nurse, with a consent `extension` and (when gender or year of birth differs) a reason; 422 `duplicate-patient-suspected` if it already belongs to another record (compared by hash), 400 `validation-failed` if it is not exactly 12 digits. The response returns only the masked version; to see the full number requires `?reveal=national-id` and emits an audit record every time. Each edit creates a new version in `resource_history` (AC-01.3). 412 if the version is old.

### C6 `POST /fhir/Patient/_search` (form-urlencoded)
| Parameter | Note |
|---|---|
| `name` | search without diacritics, last token matches by prefix (AC-06.1); minimum 2 characters |
| `phone` | exact match after E.164 normalization |
| `identifier` | `urn:shp:mrn\|BN-2026-000123` |
| `birthdate` | `eq` by day |
| `_count`, `_page_token` | |

At least one parameter is required. Each `Patient` in the response is **shortened**: `id`, `identifier[mrn]`, `name`, `birthDate` (only the **year** if the caller is a Doctor without Care), `gender`. A Doctor without Care still sees search results but opening the record (C4) gets 403
`no-care-relationship` (AC-06.2). Each search writes a `search` audit record (it does not record the search values).

### C8 `POST /fhir/Patient/{id}/$merge`
Body `{ "target": Reference(Patient) }`: `{id}` becomes `active=false`, `link[replaced-by] → target`; the old clinical resources
are moved to `target` by an asynchronous job (emits `patient-merged.v1`; scheduling/notification update references).
409 if either record has already been merged. Both records keep version history.

## 3. Visits and clinical data

`Encounter` is **created automatically** when `appointment-checked-in.v1` is received (status `arrived`); there is no public `POST /fhir/Encounter`.

### C11 `POST /fhir/Encounter/{id}/$start`
`arrived → in-progress`, `attender` = the calling doctor. 403 if the caller is not the appointment's doctor; 409 if the status is not `arrived`.

### C13 `PUT /fhir/Encounter/{id}`
Only `reasonCode`, `extension[clinical-note]`, `diagnosis` may be edited. `If-Match` required. **409 `invalid-state-transition`**
if `status = finished` (AC-04.2) — use `$addendum`.

### C14 `POST /fhir/Encounter/{id}/$finish`
Condition: at least 1 `Condition` of type `encounter-diagnosis` (422 `diagnosis-required`). Effects: `status=finished`,
`period.end`; emits `shp.clinical.encounter-finished.v1` (scheduling moves the Appointment to `fulfilled`; ml updates the outcome).

### C15 `POST /fhir/Encounter/{id}/$addendum`
Body `{ "text": "..." }` (1–4,000 characters). Adds an `extension[addendum]` element (author, time); does not modify the original.
Only when `finished`; 409 if the visit has not ended.

### C16 Observation
`POST /fhir/Observation` — validates against the profile ([01](01-domain-model.md) §4.7): vital signs require `encounter`; units
match the table; values within plausible ranges (temperature 30–45 °C, pulse 20–250, SpO2 50–100, systolic blood pressure 40–300 / diastolic
20–200). Outside the range → 422 `value-out-of-range`. `interpretation` is computed by the server. Vital signs: N or D (Care); manual
lab tests: D (Care). Search: `patient`, `encounter`, `category`, `code`, `date`; a Patient sees only observations belonging to their own `finished` Encounter.

### C17 Condition
Only D with Care (`recorder` = caller) → 403 `no-care-relationship` (AC-04.1). `code` must be a valid ICD-10 code (checked
against the preloaded code table, 422 `invalid-code`). Remove an incorrect diagnosis: `PUT` with `verificationStatus=entered-in-error`, do not delete.

### C19 `GET /api/v1/clinical/patients/{id}/timeline`
| Parameter | Note |
|---|---|
| `types` | `encounter,observation,condition,allergy,document` (default all) |
| `from`, `to` | time range (ISO 8601) |
| `limit` ≤ 50, `cursor` | keyset by (`time desc`, `id`) |

Response:
```json
{
  "items": [
    { "time": "2026-11-20T02:15:00Z", "type": "encounter", "id": "0192...", "title": "Cardiology visit", "summary": "Chest pain on exertion",
      "practitioner": { "id": "0192...", "display": "Dr. Le Minh Quan" }, "ref": "Encounter/0192..." },
    { "time": "2026-11-20T02:20:00Z", "type": "observation", "id": "0192...", "title": "Blood pressure", "summary": "128/82 mmHg", "ref": "Observation/0192..." }
  ],
  "nextCursor": "..."
}
```
The server calls 4 Mongo queries in parallel (by `patientId`) and the document service (`/internal/v1/documents?patient=`) with a
300 ms timeout; if document fails → it still returns the rest with `partial: ["document"]` (controlled degradation). Target p95 ≤ 500 ms (NFR-P3). Writes a `read` audit record.

### C20 `POST /api/v1/clinical/break-glass`
Body `{ "patientId": "0192...", "reason": "Emergency in the department, patient unconscious, no family member present" }` (20–500 characters).
Only D **without** Care. Creates a 60-minute session (`break_glass_sessions`); 201 `{ "expiresAt" }`. Emits `audit-recorded` for action
`break-glass-start` (purpose `ETREAT`) → the audit service notifies the DPO within ≤ 5 minutes (AC-10.1). Every read in the session is audited as
`ETREAT`. 400 if `reason` is too short; 409 if Care already exists or a session is still valid.

## 4. Internal API (not through the gateway)

| Endpoint | Called by | Returns |
|---|---|---|
| `GET /internal/v1/identity/{subject}` | every service | `{ "fhirUser": "Patient/...", "roles": [...], "disabled": false }`; 404 if not linked |
| `GET /internal/v1/care-relationships/check?practitioner={id}&patient={id}` | document, ai, notification | `{ "care": true, "via": "appointment|encounter|break-glass", "validUntil": "..." }` |
| `GET /internal/v1/practitioners/{id}/roles` | scheduling | list of `PractitionerRole` (specialty, active) |
| `GET /internal/v1/patients/{id}/contact` | notification | `{ "phone", "email", "locale" }` — **the only endpoint that returns contact information**; only the `internal.notification` role, every call has a `read` audit record |
| `GET /internal/v1/patients/{id}/age-band` | scheduling (when creating the `appointment-booked` event) | `{ "ageBand": "30-39" }` |

## 5. User administration (C21)

`POST /api/v1/admin/users` `{ "subject": "<OIDC sub>", "fhirUser": "Practitioner/0192...", "roles": ["doctor"] }` links
a staff account with a `Practitioner` (must match `PractitionerRole.code`); `PUT .../{subject}` `{ "disabled": true }` locks the
account. Both write audit; locking takes effect within ≤ 60 seconds due to the identity cache (AC-08.1). Admin **cannot** read clinical data.


## 9. Profile links: relatives and claiming a record (C22 to C29)

An account has **one "self" profile** (`identity_links`, as before) and can manage additional profiles through `profile_links`
([ADR-0018](../adr/0018-profile-links-and-claim.md)). Every API in this section runs in clinical; **sending and verifying the OTP code** is
handled by notification (N8, N9). The response **never** reveals whether a record exists before the OTP is correct.

| API | What it does | Main return code |
|---|---|---|
| C22 | Returns `[{ profileId, patientId, displayName, relationship, scope, expiresAt }]`; the relative's name is shortened if the link is not yet active | 200 |
| C23 | Body `{ name, birthDate, gender, declaration: true }`. Checks under 16, at most 10 profiles/account, duplicate prevention; creates `Patient` + `profile_links` (`relationship=guardian`, `scope=full`) | 201; 422 `not-a-dependent`, 422 `profile-limit`, 422 `duplicate-patient-suspected` |
| C24 | Body `{ name, birthDate, phone, scope: "booking"\|"records" }`. If **exactly one** adult profile matches: create a `pending` link, call N8 to send an OTP to the number **in the record** with content stating the scope. Always returns 200 `{ "requestId" }` | 200; 429 `otp-rate-limited` (5 requests/24 hours/account) |
| C25 | Body `{ otp }`. Calls N9; if correct then `status=active`, `expiresAt=+12 months`, notifies the record owner (if they have their own account) | 200; 422 `otp-invalid`; 429 `otp-locked` |
| C26 | Sets `status=revoked`, clears the permission cache (effective ≤ 60 seconds), notifies the other party. The caller is the manager, the record owner (if they have an account), or a Nurse | 204; 404 if not within their authority |
| C27 | Body `{ name, birthDate, phone }`. Finds a matching `active` record (full name without diacritics + date of birth + hashed phone number). If **exactly one** candidate has a phone number, call N8 to the number in the record. Always returns 200 `claim-sent` | 200; 429 `otp-rate-limited` (5 times/24 hours/account, 3 codes/24 hours/number) |
| C28 | Body `{ otp }`. Calls N9; if correct and the record has no "self" `identity_links`, create the link, send the notification "a new account has been linked" to the number in the record, write audit | 200; 409 `record-already-linked` (instruct them to go to the counter); 422 `otp-invalid`; 429 `otp-locked` |
| C29 | Nurse enters the authorized account (phone number or email), `scope`, `consentDeclaration: true` after checking documents; effective immediately, writes audit | 201; 404; 422 `profile-limit` |

The same person matching multiple shared phone numbers (family) is distinguished by full name and date of birth; if there are still **more than one**
candidates, do not send a code and instruct them to go to the counter. The phone number and date of birth entered **never** appear in logs or audit
(only a keyed hash is recorded).
