# Project plan

> Status: **Approved (2026-10-26)**. Order of work: BA -> Requirements -> **User stories** -> Detailed design -> Backlog -> Code.
> No code written yet; every number below is generated from the data in `tools/plan/` by `python3 -I tools/plan/build.py`
> (the script self-checks: every AC of an FR belongs to a story, every API and every event has a task, dev tasks <= 4 hours, no task scheduled before its dependency, dev <= 30 hours/week).

## 1. Input documents per person

| What you need | Where to read it |
|---|---|
| Context, business rules | [Business analysis](../01-business-analysis/business-analysis.md) |
| Requirements, NFRs, authorization matrix | [02-requirements](../02-requirements/functional-requirements.md) |
| **74 user stories + acceptance criteria + each story's tasks** | [user-stories.md](../02-requirements/user-stories.md) |
| Data model, DB, API, events, sequences, authorization | [detailed-design](../03-architecture/detailed-design/README.md) |
| **Each person's work by week (checklist)** | [SA](by-person/SA.md) · [D1](by-person/D1.md) · [D2](by-person/D2.md) · [D3](by-person/D3.md) |
| The whole backlog / GitHub Projects import | [backlog.md](backlog.md) · [backlog.csv](backlog.csv) |

## 2. Assignment

| Code | Role | Owns |
|---|---|---|
| **SA** | Solution Architect + PM + DevOps | **Everything outside business logic**: architecture, API/event contracts, infrastructure, CI/CD, Helm/GitOps, environments, service scaffold, gateway, observability, load/failure tests, backup, approvals, schedule |
| **D1** | Java developer (logic only) | auth/authorization/idempotency libraries · **scheduling** · **notification** · booking and schedule-management UI |
| **D2** | Java developer (logic only) | FHIR/Mongo/outbox/audit-emitter core · **clinical** · **document** · **audit** · clinical UI |
| **D3** | Python/AI developer (logic only) | Python libraries · synthetic data · **ML no-show** · **AI service** (RAG, summarization, extraction, chatbot, eval) · AI UI |

**Rule:** a developer receives a service already scaffolded by the SA (code skeleton, Dockerfile, pipeline, Helm values, DB/bus/OTel wiring) and writes only logic and tests.
DB migrations and the service's own data-access code are the developer's logic (written against the DDL validated in the design). Developers do not modify
pipelines, Dockerfiles, Helm or infrastructure; if a change is needed, open an issue for the SA. Branch naming: `<ID>-short-description`, one PR per task.

### Application repo layout (`smart-hospital-platform`, created in GOV-01 and ARC-09)

```
services/<scheduling|notification|clinical|document|audit|ai|ml|gateway>/   # per service: src, tests, Dockerfile, deploy/values-<env>.yaml
libs/java/     libs/python/                    # shared libraries (SHR-*, PY-*); libs/java is a Maven module of the root pom.xml
web/                                           # React PWA
contracts/{openapi,events,authz,terminology}/  # API contracts, event schemas, matrix.yaml, ICD-10/LOINC
prompts/<task>/v<N>.md   templates/            # versioned AI prompts, vi/en notification templates
docs/   tools/plan/                            # documentation and the backlog generator source
.github/workflows/svc-<name>.yml               # one pipeline per service, calling the shared template in the shp-platform-templates repo
```

## 3. Roadmap and gates

```
W1   Gate G1/G2/G3: approve BA, requirements, architecture + detailed design; create repos
W2   SA: Azure bootstrap, CI templates, Helm chart | Dev: shared libraries (FHIR, authz, JWT, LLM router), synthetic data, ML data
W3   SA: network/AKS/monitoring, GitOps | Dev: continue libraries, web shell
W4   SA: scaffold 7 services (scheduling, notification, clinical, document, audit, ai, ml) + platform add-ons | Dev starts services (clinical, audit, notification, AI)
W5   SA: data modules, env-provision/destroy | Dev: scheduling, clinical, notification, ML
W6   -- Gate G4 -- (sample service goes commit -> local -> uat -> prod (ephemeral), rollback <= 5 minutes, unsigned images blocked)
W7-W9 Feature development; dev tests on local K8s; after G4 the SA provisions UAT on a schedule (proposed Thursdays) for integration testing
W10  Hardening: load test, failure test, backup/restore | Dev: fix based on results
W11  AI/ML eval, FinOps, security review
W12  Demo script, slides, rehearsal on ephemeral prod
```

| Gate | Week | Condition | Who approves |
|---|---|---|---|
| G1 | W1 | Scope + KPIs finalized | SA (PM) |
| G2 | W1 | FRs have ACs, NFRs are measurable, user stories approved | SA |
| G3 | W1 | ADRs Accepted + detailed design approved | SA (Architect) |
| **G4** | **W6** (approved; previously W5) | Full pipeline + sample service through UAT and prod (ephemeral) + rollback + unsigned-image blocking | SA (DevOps) |
| Feature freeze | end of W9 | Every Must story done and deployable | SA |

**Why G4 moved from W5 to W6:** SA workload for W2-W5 is ~45 hours/week even after moving all non-G4-blocking work later. Devs still work
normally before G4 (code, local tests, integration tests with Testcontainers); only cloud deploys start from W7. This was confirmed by the SA when the plan was approved.

## 4. Workload (hours/week, details in [backlog.md](backlog.md))

| Person | Total | Peak | Notes |
|---|---|---|---|
| SA | 490 | 80 (W4), 65 (W8) | Heaviest in W2-W5, lighter after W8. This is the biggest bottleneck in the plan |
| D1 | 128 | 30 | Scheduling + notification, fully loaded in W4-W7 |
| D2 | 171 | 30 | Most work: clinical + document + audit + FHIR core run almost in parallel |
| D3 | 131 | 30 | ML and AI; the heaviest part is the MLOps and eval work in W5-W7 |

345 tasks. 1,183 hours. Devs are scheduled for at most 30 hours/week (25-30 hours/week is treated as realistic capacity). If actual capacity is lower, cut in the order below.
Numbers were regenerated from the backlog on 2026-11-11 after GOV-08 was split into GOV-08..GOV-10.

### Scope that can be cut when late (in order)
1. **Could** (~20 dev hours): US-040 (flag a document entered by mistake), US-056 (rate/chat history), US-062 (booking chatbot).
   **Advanced MLOps** (US-065, US-071..074, ~42 dev hours + 17 SA hours) is Should per the full requirement set; if forced to cut, keep US-071/072 (data, quality gate), drop automated canary (US-073) first, then automated retraining (US-074).
2. **Should** (~126 dev hours): choose to drop document extraction (US-059..061), record summarization (US-058), rescheduling (US-017), record merging (US-004), break-glass (US-034, US-051) if there really are too few people. Worth repeating: the brief only suggests **one** advanced AI feature but requires quality evaluation.
3. Do not cut: Must (booking, basic clinical, documents, authorization, audit, notifications, RAG), because those are mandatory parts of the brief.

## 5. How to track

- **GitHub Projects**: import [backlog.csv](backlog.csv) (columns `Owner`, `Week`, `Stories`, `Depends on`); each row is an issue in the matching repo. Columns: `Todo -> In progress -> In review -> Done`.
- Labels: `epic:<code>`, `priority:must|should|could`, `story:US-xxx`.
- Every end of week: sprint review (ARC-13..18), status update; to change the plan, edit `tools/plan/*.py` and re-run the build (the script reports broken constraints).
- A task starts only when its dependencies are done; a task is done when it meets its "Done when" column and the Definition of Done.

## 6. Main risks

| Risk | Impact | Mitigation |
|---|---|---|
| SA overloaded in W2-W5 (peaks at 80 hours in W4, three roles) | G4 late, devs blocked from deploying | Move G4 to W6; prioritize tasks that block devs (local env, scaffold, API/event contracts); use AI assistance for Terraform/Helm/workflows per the backlog and have the SA review |
| D2 overloaded (171 hours, many services in parallel) | clinical/document/audit late | D3 helps in W8; cut Should scope per section 4; accept no extra scope |
| Cross-dev dependencies (D3 waits on D2, D1 waits on D2) | Devs idle but blocked | API/event contracts finalized in W1-W3 (OpenAPI, event schemas) so devs can build fakes and integrate later |
| Little experience with FHIR/MongoDB/Argo CD | Slow in W2-W5 | Detailed design and DDL already trialled; core libraries built early (W2-W3) |
| AI/Azure services change models or quotas | AI stops working | Fallback chain includes local Ollama; eval runs offline with fake providers |
| New Year's Day falls in W10 | Fewer people for hardening | W10 is planned with a light load for devs |
