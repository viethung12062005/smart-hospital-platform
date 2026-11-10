# Phase 2 - Access control matrix

> The assignment requires demonstrating an "access-control matrix in action". Each cell of the table is an automated test
> (NFR-SEC2). Combines RBAC (by role) with ABAC (by relationship to the patient).

**Legend:** Yes = allowed, No = not allowed, **Own** = own data only,
**Care** = only patients with a treatment relationship (an appointment/encounter with the doctor within 12 months or a future appointment),
**BG** = allowed via break-glass (UC-10), **Rel** = only documents already released to the patient.

| Resource / Action | Patient | Doctor | Nurse | Admin | DPO |
|---|---|---|---|---|---|
| Patient: read | Own | Care / BG | Yes | No | No |
| Patient: create / update | Own (administrative information) | No | Yes | No | No |
| Patient: search (name, year of birth) | No | Yes | Yes | No | No |
| Schedule / Slot: view free slots | Yes | Yes | Yes | Yes | No |
| Schedule / Slot: create / update / block | No | No | Yes | Yes | No |
| Appointment: book | Own | No | Yes | No | No |
| Appointment: cancel | Own (>= 2h before the visit) | No | Yes | No | No |
| Appointment: view | Own | The doctor's own | Yes | No | No |
| Encounter / Observation: read | Own (finished) | Care / BG | Yes | No | No |
| Encounter: create / check-in | No | Care | Yes | No | No |
| Condition (diagnosis): write | No | Care | No | No | No |
| Observation (vital signs): write | No | Care | Yes | No | No |
| Document: upload | No | Care | Yes | No | No |
| Document: download | Own + Rel | Care / BG | Yes | No | No |
| Document: release to patient | No | Care | No | No | No |
| RAG assistant: ask | No | Yes | Yes | No | No |
| Knowledge base: manage documents | No | No | No | Yes | No |
| AI record summary | No | Care / BG | No | No | No |
| Approve AI extraction results | No | Care | Yes | No | No |
| Booking chatbot | Own | No | No | No | No |
| Users / roles: manage | No | No | No | Yes | No |
| Audit: view / export reports | No | No | No | Yes (no access to clinical content) | Yes |
| Audit: modify / delete | No | No | No | No | No |
| Break-glass | No | Yes (reason required, 60 minutes) | No | No | No |
| Backup / restore | No | No | No | Yes (via operations pipeline, with approval) | No |

## Enforcement rules
1. **Default deny**: an action not present in the table is denied.
2. Permissions are checked in the **service that owns the data**; the gateway only validates the token and does not decide permissions.
3. A denial returns `403` and **is also written to the audit log** (permission-probing detection).
4. AI (RAG, summary, chatbot) calls other services **using the user's token** (on-behalf-of), so it never has broader permissions than the user.
5. Service-to-service uses workload identity and may call only declared endpoints.
