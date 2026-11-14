# Phase 1 - Business Analysis

> Status: **Approved - G1 passed on 2026-10-26**. The hospital context below is **assumed** in order to have
> design figures; all patient data used in the project is synthetic data.

## 1. Organizational context (assumed)

A medium-sized private general hospital:

| Metric | Assumed value |
|---|---|
| Inpatient beds | 300 |
| Outpatient clinics (specialty) | 40 |
| Outpatient doctors | 120 |
| Nurses / clinical staff | 250 |
| Outpatient visits / day | ~1,500 (Monday peak: ~2,200) |
| Patients registered on the system in the first year | ~50,000 |
| Outpatient working hours | 7:00-17:00, Monday-Saturday |

## 2. Business problems

| # | Current problem (as-is) | Consequence | Root cause |
|---|---|---|---|
| BP-1 | Booking by phone/counter, call center overloaded at peak hours | Patients wait long, lose customers | No self-service channel |
| BP-2 | Patient no-show rate ~18% | Wasted doctor slots | Manual appointment reminders, no prioritization of at-risk cases |
| BP-3 | Lab results and documents are loose files, paper | Doctors spend 5-10 minutes finding patient history | No centralized medical record |
| BP-4 | Manual lookup of internal protocols and processes | Slow, inconsistent across departments | Documents are not indexed |
| BP-5 | No knowledge of who viewed a patient's medical record | Legal risk regarding health data (sensitive personal data) | No centralized audit |
| BP-6 | Manual entry of data from uploaded documents | Errors, wastes nurse effort | No automated extraction |

## 3. Stakeholders

| Stakeholder | Role in the system | Main concern | Influence level |
|---|---|---|---|
| Patient | Patient | Fast booking, appointment reminders, view own results, privacy | High |
| Doctor | Doctor | See patient history fast, record encounters with few actions, look up protocols | High |
| Nurse / clinical staff | Nurse | Reception, measure vital signs, upload documents, manage appointment schedule | High |
| System administrator | Administrator | Manage users/permissions, view audit, backup/restore | Medium |
| Board of directors | (does not use directly) | Reduce no-shows, operating cost, legal compliance | High |
| Data protection officer (DPO) | (reads audit reports) | Compliance with personal data protection regulations | High |
| AI/cloud provider | External | Scope of data sent out | - |

## 4. Business process

### 4.1 As-is: outpatient visit
```
Patient calls the call center -> staff records in a book/Excel -> (no appointment reminder) -> patient arrives at the counter
-> nurse looks for paper record -> doctor examines, writes on paper -> lab results printed on paper, patient keeps them
```

### 4.2 To-be: outpatient visit on the platform
```
1. Patient searches free slots by specialty/doctor/date -> booking (app/web or chatbot)
2. System confirms immediately -> reminders at T-24h and T-2h; appointments at high no-show risk get an extra reminder
3. Patient arrives, nurse checks in -> measures vital signs -> uploads documents brought along
   -> AI extracts data fields -> nurse confirms if confidence is low
4. Doctor opens the medical record -> views AI summary + history -> records the encounter (encounter), diagnosis
   -> asks the knowledge assistant about internal protocols (with source citations)
5. Lab results uploaded to the medical record -> patient receives a notification, views via a short-lived secure link
6. Every view/edit of sensitive data is recorded in the audit log; the DPO views access reports
```

## 5. Business rules

| ID | Rule |
|---|---|
| BR-01 | A slot may be booked by only one active appointment. |
| BR-02 | Patients may cancel at the latest 2 hours before the appointment time; after that only staff can cancel. |
| BR-03 | A patient has at most 3 active future appointments (anti-hoarding). |
| BR-04 | Slots can be opened for booking at most 30 days in advance. |
| BR-05 | Patients can only view their own data. |
| BR-06 | Doctors can only view the medical records of patients who have/had an appointment with them (treatment relationship), unless using emergency access (break-glass). |
| BR-07 | Emergency access requires entering a reason, is recorded in the audit log and reported to the DPO. |
| BR-08 | Clinical documents may only be downloaded via a link valid for at most 5 minutes. |
| BR-09 | AI assistant answers must include source citations; without a source it must not answer. AI does not make diagnoses in place of the doctor. |
| BR-10 | AI-extracted data below the confidence threshold must be confirmed by a person before being saved to the medical record. |
| BR-11 | Medical records are archived long-term per legal regulations (assumed 10 years, current legal texts need re-verification). |

## 6. Business KPIs (to evaluate success)

| KPI | Current (assumed) | Target after 6 months |
|---|---|---|
| Self-service booking rate | 0% | >= 60% |
| No-show rate | 18% | <= 12% |
| Doctor time finding patient history | 5-10 minutes | <= 30 seconds |
| Manual document entry time / patient | ~4 minutes | <= 1 minute |
| Rate of medical record access that is audited | not measurable | 100% |

## 7. Scope

**In scope (12-week MVP):** **outpatient** visits: patient medical records, doctor schedules, booking/cancellation,
encounters, document upload, history search, appointment reminders, audit, and 4 AI features + 1 ML model.

**Out of scope:** inpatient, payment/insurance, prescriptions and pharmacy inventory, PACS/DICOM, external HIS
integration, native mobile apps (PWA is used instead).

## 8. Assumptions, constraints, risks

| Type | Content |
|---|---|
| Constraint | 12 weeks, 5-7 people; the team's Azure infrastructure already exists; limited cloud budget (settled in the NFRs); synthetic data only |
| Legal constraint | Health data is sensitive personal data under Vietnamese law on personal data protection -> requires consent, data minimization, access logging (current legal texts need re-verification) |
| Assumption | The hospital has an employee identity system that is OIDC-compliant; patients have an email or phone number |
| Risk | Medical data sent to an external AI provider -> mitigated by redacting PII and preferring models within Azure |
| Risk | Scope too broad for 12 weeks -> prioritize with MoSCoW in Phase 2, cut "Could" items if behind |
