# Requirements Traceability Matrix

Bidirectional traceability: business problem -> requirement -> architecture decision -> verification.
The "Verification" column is filled in progressively in Phases 4-6 (test names, load test scenarios, dashboards).

| Business problem (BA) | Requirement | Architecture decision (ADR) | Verification |
|---|---|---|---|
| BP-1 Booking overload | UC-03, NFR-P1, P2, S1-S3 | ADR-0001 (split scheduling), ADR-0005 (concurrency + idempotency), ADR-0008 (PostgreSQL + Redis), ADR-0004 (K8s autoscale) | AC-03.1/03.2 tests; load test 1x/5x/10x |
| BP-2 High no-show rate | UC-07, UC-15, NFR-P8, AI6, A6 | ADR-0007 (no-show ML), event-driven notification (outbox) | AC-07.x tests; AUC report; SMS provider failure test |
| BP-3 Fragmented records | UC-01, 04, 05, 06, 12, NFR-P3, P4 | ADR-0003 (FHIR store), ADR-0008 (MongoDB for clinical, Blob for documents) | AC-05.x, 06.x tests; record-read load test |
| BP-4 Slow protocol lookup | UC-11, NFR-P5, AI1-AI3, AI7 | ADR-0006 (LLM router + fallback), ADR-0008 (AI Search) | RAG eval report; prompt injection test |
| BP-5 No audit trail | UC-09, UC-10, NFR-SEC2, SEC5 | ADR-0008 (WORM audit + KQL), [access control matrix](access-control-matrix.md) | Access control matrix tests; "who viewed record X" demo |
| BP-6 Manual paper data entry | UC-13, NFR-P7, AI4 | ADR-0006, asynchronous processing + dead-letter | F1 report; OCR failure test |
| Assignment: backup/recovery | UC-16, NFR-A4, A5 | ADR-0008 (PITR, Blob GZRS/WORM) | Restore drill report |
| Assignment: cloud ops | NFR-D1-D4, O1-O4, SEC6, SEC7 | ADR-0004 (AKS, GitOps), ADR-0009 (CI/CD, ephemeral environments, approvals), ADR-0010 (4 repos) | Pipeline runs for real; rollback demo |
| Assignment: cost awareness | NFR-C1-C3 | ADR-0008 (cost consequences), ADR-0004 | FinOps report |

## Traceability down to user stories and tasks

From each row above, trace further: **AC of the FR -> [user story](user-stories.md) -> each person's tasks** (the "Coverage" table at the top of `user-stories.md`
lists which AC belongs to which story; each story lists tasks, assignee, hours and week). The script `tools/plan/build.py` ensures no AC, API or event is missed.
