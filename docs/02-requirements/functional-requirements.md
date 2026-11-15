# Phase 2 - Functional requirements

> Status: **Approved - G2 passed on 2026-10-26**. Priorities follow MoSCoW (M = Must, S = Should, C = Could).
> Acceptance criteria (AC) are written in Given/When/Then form so they can be turned directly into automated tests.

## Actors
`Patient`, `Doctor`, `Nurse` (nurse/clinical staff), `Admin`, `System` (job/worker), `DPO` (audit reader).

## Use case overview

| ID | Use case | Primary actor | Priority | Source (BA) |
|---|---|---|---|---|
| UC-01 | Manage patient records | Patient, Nurse | M | BP-3 |
| UC-02 | Manage doctor schedules | Nurse, Admin, Doctor | M | BP-1 |
| UC-03 | Book / cancel / reschedule an appointment | Patient, Nurse | M | BP-1, BR-01..04 |
| UC-04 | Record a visit (encounter) | Doctor, Nurse | M | BP-3 |
| UC-05 | Upload and access clinical documents | Nurse, Doctor, Patient | M | BP-3, BR-08 |
| UC-06 | Search patient history | Doctor, Nurse | M | BP-3 |
| UC-07 | Reminders and notifications | System | M | BP-2 |
| UC-08 | Manage users and authorization | Admin | M | BP-5 |
| UC-09 | View audit of sensitive data access | DPO, Admin | M | BP-5, BR-07 |
| UC-10 | Emergency access (break-glass) | Doctor | S | BR-06, BR-07 |
| UC-11 | Medical knowledge assistant (RAG) | Doctor, Nurse | M | BP-4, BR-09 |
| UC-12 | Summarize a patient record with AI | Doctor | S | BP-3 |
| UC-13 | Extract data from uploaded documents | System, Nurse | S | BP-6, BR-10 |
| UC-14 | Booking chatbot | Patient | C | BP-1 |
| UC-15 | Predict no-show risk (ML) | System | S | BP-2 |
| UC-16 | Back up and restore data | Admin (operations) | M | Assignment: backup/recovery |

---

## UC-01 Manage patient records
- **Description:** Patients register themselves and update their administrative information; nurses create/edit records at the counter.
- **Data (FHIR):** `Patient`.
- **Main flow:** (1) Patient registers an account -> (2) enters full name, date of birth, sex, phone number, email,
  address -> (3) the system checks for duplicates (phone number + date of birth) -> (4) creates a record linked to the account.
- **Alternative flow:** duplicate record -> request nurse verification and merge.
- **AC:**
  - AC-01.1 Given a patient is logged in, When viewing records, Then they see only their own record.
  - AC-01.2 Given a record already exists with the same phone number + date of birth, When registering, Then the system does not create a new record but reports that verification is required.
  - AC-01.3 Every edit to a record creates a new version; version history is viewable.

## UC-02 Manage doctor schedules
- **Data:** `Schedule` (a doctor's schedule), `Slot` (time slot, default 15 minutes).
- **Main flow:** Nurse/Admin create a schedule from a weekly template (for example Mon-Fri, 8:00-11:30) -> the system generates slots for
  the next 30 days (BR-04) -> slots can be blocked (leave) -> a slot that already has an appointment cannot be deleted.
- **AC:**
  - AC-02.1 Given a schedule template 8:00-11:30 with 15-minute slots, When generating slots for one day, Then there are exactly 14 `free` slots.
  - AC-02.2 Given a slot has been booked, When blocking the slot, Then an error is returned and the appointment must be cancelled/rescheduled first.
  - AC-02.3 A Doctor can view only their own schedule; they cannot edit the schedule.

## UC-03 Book / cancel / reschedule an appointment
- **Data:** `Slot`, `Appointment`.
- **Main flow (booking):** (1) Patient filters slots by specialty, doctor, date -> (2) selects a slot -> (3) sends a
  booking request with an idempotency key -> (4) the system checks BR-01, BR-03, BR-04 -> (5) creates an `Appointment`
  in status `booked` and the slot becomes `busy` -> (6) emits a "booked" event for reminders and audit.
- **Alternative flows:**
  - The slot was just booked by someone else -> report "slot no longer available" and suggest the 3 nearest slots.
  - The client resends the same request (timeout, network loss) -> return the same appointment already created, do not create a duplicate.
  - Cancellation: at least 2h before the visit (BR-02) -> `cancelled`, the slot returns to `free`.
  - Rescheduling = book a new slot + cancel the old slot in the same operation; on failure the old appointment is kept.
- **AC:**
  - AC-03.1 Given 1 free slot and 50 concurrent booking requests from 50 patients, Then exactly 1 request succeeds and 49 receive a conflict error.
  - AC-03.2 Given the same idempotency key sent twice, Then both receive the same appointment and only 1 appointment is created.
  - AC-03.3 Given the same idempotency key but different content, Then the request is rejected.
  - AC-03.4 Given a patient already has 3 future appointments, When booking another, Then it is rejected (BR-03).
  - AC-03.5 Given 1 hour remains until the visit, When the patient cancels, Then it is rejected; a Nurse can cancel.
  - AC-03.6 A patient cannot book an appointment for another patient.

## UC-04 Record a visit
- **Data:** `Encounter`, `Observation` (vital signs), `Condition` (diagnosis, ICD-10 code).
- **Main flow:** Nurse check-in (Appointment -> `arrived`, create `Encounter` `in-progress`) -> measure vital signs ->
  Doctor records history and diagnosis -> finish (`finished`, Appointment -> `fulfilled`).
- **AC:**
  - AC-04.1 Only a Doctor with a treatment relationship can record a diagnosis.
  - AC-04.2 A `finished` Encounter cannot be edited; additions must be recorded as an addendum (the original is preserved).

## UC-05 Upload and access clinical documents
- **Data:** `DocumentReference` (metadata), file in object storage.
- **Main flow:** Nurse/Doctor request an upload link -> the client uploads directly to storage -> the system confirms,
  checks the file type (PDF, JPEG, PNG; <= 20 MB) and scans for malware -> creates a `DocumentReference` -> emits a "document uploaded" event.
- **Access:** an authorized person requests a download link, valid for <= 5 minutes (BR-08); every link request is written to the audit log.
- **AC:**
  - AC-05.1 A file in the wrong format or larger than 20 MB is rejected.
  - AC-05.2 A download link that has expired after 5 minutes returns 403.
  - AC-05.3 A patient can download only their own documents, and only those the doctor has "released to the patient".

## UC-06 Search patient history
- **Main flow:** Doctor/Nurse search for a patient by name, phone number, patient ID, date of birth -> open the timeline: visits,
  diagnoses, vital signs, documents, in chronological order; filter by time range and data type.
- **AC:**
  - AC-06.1 A search by a partial unaccented name ("nguyen van") still returns accented results.
  - AC-06.2 A Doctor without a treatment relationship sees search results (name, year of birth) but cannot open the timeline (except break-glass).

## UC-07 Reminders and notifications
- **Main flow:** When a "booked" event occurs, the system schedules T-24h and T-2h reminders via email/SMS/push; if the
  appointment is cancelled, the reminders are cancelled too; notify when new results are available.
- **Alternative flow:** the provider reports a delivery error -> retry with backoff (at most 5 times) -> move to the dead-letter queue
  and alert operations; if the primary channel fails, switch to the fallback channel (push -> email).
- **AC:**
  - AC-07.1 An appointment cancelled before the reminder time does not receive a reminder.
  - AC-07.2 A temporary provider error does not lose the notification; no more than 1 duplicate is sent for the same reminder.

## UC-08 Manage users and authorization
- Admin invites staff, assigns roles (Doctor/Nurse/Admin), locks accounts; links a Doctor account to a `Practitioner`.
- **AC:** AC-08.1 A locked account has its current token rejected within <= 5 minutes. AC-08.2 Staff must use MFA.

## UC-09 View audit
- DPO/Admin filter audit by patient, accessor, time, action type; export reports.
- **AC:** AC-09.1 Every read/write of patient data in UC-01, 04, 05, 06, 10, 11-13 has an audit entry (who, when, what, result, from where).
  AC-09.2 No one (including Admin) can modify or delete audit entries.

## UC-10 Emergency access (break-glass)
- A Doctor without a treatment relationship can still open a record in an emergency: a reason is mandatory, access is valid for 60 minutes.
- **AC:** AC-10.1 Every break-glass event sends an alert to the DPO within <= 5 minutes.

## UC-11 Medical knowledge assistant (RAG)
- **Source:** approved care protocols and internal procedures (synthetic/public documents), managed by Admin/head of department.
- **Main flow:** the user asks a question -> the system searches only the documents that user is allowed to view -> generates an answer
  with citations (document, section) -> the user rates it helpful/not helpful.
- **AC:**
  - AC-11.1 Every answer has >= 1 citation pointing to the correct source passage; if no source is found, return "no information in the documents".
  - AC-11.2 A document containing an injected instruction ("ignore the previous instructions...") does not change behavior (checked by the prompt injection test suite).
  - AC-11.3 If the primary model fails, the system switches to a fallback model automatically; if all models fail, return the list of related documents.

## UC-12 Summarize a patient record
- Doctor opens a record -> clicks "summarize" -> receives a summary (comorbidities, allergies, most recent visit, medications, abnormal results),
  with every point linked to the source record.
- **AC:** AC-12.1 Only data the user is allowed to view is used. AC-12.2 Every point in the summary links to the source record. AC-12.3 Labeled "generated by AI, needs review".

## UC-13 Extract data from uploaded documents
- When a "document uploaded" event occurs -> OCR -> classify (lab results, prescription, referral letter...) ->
  extract fields -> validate -> if confidence >= threshold, propose saving; if lower, place in the review queue (BR-10).
- **AC:** AC-13.1 No field is written to the record until a person confirms (the MVP always requires confirmation). AC-13.2 Processing errors are retried; after too many attempts they go to dead-letter and do not block the upload.

## UC-14 Booking chatbot
- The patient chats ("I want a cardiology appointment on Thursday morning") -> the bot finds slots -> proposes -> **the patient confirms** -> the bot calls UC-03 with the patient's own permissions.
- **AC:** AC-14.1 The bot never books/cancels without explicit confirmation. AC-14.2 The bot does not access another patient's data. AC-14.3 If the bot fails, it switches to the booking form.

## UC-15 Predict no-show risk
- When an appointment is created -> compute the no-show probability -> if >= threshold, UC-07 adds a reminder (T-6h) and flags it for the nurse.
- **AC:** AC-15.1 If the ML service fails, reminders still run normally (no hard dependency). AC-15.2 Every prediction stores the model version for traceability.

## UC-16 Back up and restore
- **AC:** AC-16.1 Appointment and record data can be restored to any point in the last 7 days. AC-16.2 Restore drills record the actual time (against the RTO).
