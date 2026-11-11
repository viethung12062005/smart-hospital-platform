# Definition of Done audit — Smart Hospital Platform

Date: 2026-11-11. Scope: every backlog task whose Project #1 status was `Done` or `In Progress` on that day (125 tasks).
Audited tree: `ee959d9` (`main` plus the English translation of GOV-08/GOV-09; no behavior differs from `main`), in a
separate read-only worktree. The audit fixed nothing; repairs are tracked task by task and this page is not updated
as they land — check the task issues for the current state.

Each task is checked against two things:

1. its own **Done when** line in `docs/05-plan/backlog.csv`;
2. the shared **Definition of Done** in `docs/00-workflow.md` §4 (tests green, line coverage >= 70%, contract matches code,
   `@RequireShpAuthz` + matrix row, no secrets/PII, scans clean, tried on the local cluster, every AC has a catalog test case
   and an automated test, docs updated).

Verdicts: **PASS** (met, evidence given) · **PARTIAL** (met in substance, a named piece is missing) · **FAIL** (the Done-when
is not met) · **UNVERIFIED** (cannot be checked without a cloud environment; says what is needed).

## Summary

125 tasks audited: **84 pass, 32 partial, 6 fail**, 3 are still `In Progress` (OBS-04, GOV-05, GOV-08) and were not judged.

Build evidence on the audited tree: `mvn -B -o verify` BUILD SUCCESS (16 modules, every module >= 80% line coverage);
`pytest` libs/python 61 passed (83%), services/ai 56 passed (88%, `TZ=UTC`), services/ml 46 passed (87%); web 30 Vitest
tests (86.6% lines); every workflow green on `main`.

### Tasks that fail their Done-when

| Task | What is missing | Size of the fix |
|---|---|---|
| ARC-02 | 40 of 49 NFRs have no measurement method; the cost table is still "pending team agreement" | Docs, ~2 h |
| SHR-21 | Identity resolver: no stale-cache fallback, no 503, no `linkedProfiles`; clinical endpoint it calls does not exist | Code + tests, ~4 h (endpoint is a separate dev task) |
| LOC-02 | Realm has no `fhir_user` claim and no staff MFA; `dev/README.md` itself lists LOC-02 as not available | Realm + docs, ~3 h |
| ML-12 | "AC-15.1 has a test in notification" — notification is still a scaffold | Blocked on the notification reminder tasks; reopen |
| CIT-07 | No CodeQL (no workflow, default setup `not-configured`) | Config, < 1 h |
| GOV-06 | No `dependabot.yml` in `shp-infra`, `shp-gitops`, `shp-platform-templates` | Config, < 1 h |

### Tasks that are partial

| Task | Missing piece |
|---|---|
| ARC-03 | Seven ADRs do not name the FR/NFR they serve (trace exists only in traceability.md) |
| ARC-06 | `clinical.yaml` drops or malforms ~10 operations (C9, C10, C16, C17, C18, C21); bogus path `/{id}` |
| ARC-10, GOV-03 | PR template checklist does not match the Definition of Done in `00-workflow.md` §4 |
| SHR-08 | Service Bus retry/DLQ path is configuration only, never exercised |
| SHR-20 | `shp-security` coverage gate overridden to 0.00 (real coverage 95%) — stale override |
| SCF-04, SCF-05 | No Blob (or Log Analytics) wiring in document/audit |
| SCF-06 | ai values have no `nodeSelector`/`tolerations` for the ai node pool |
| NTF-01, ML-11 | `mongo-notification-ml.test.js` is never run; bootstrap only runs in `make up`; no `score` validation |
| GW-01 | `/api/v1/me/**` (7 contract paths) is not routed |
| LOC-01 | Documented first run is 10–15 min vs the "< 10 minutes" criterion; stale verification note |
| LOC-08 | Nothing seeds synthetic data into the cluster |
| WEB-02 | No re-login/retry when the session expires |
| PY-02 | Test does not check `subject_token`/`audience`; realm cannot do token exchange |
| PY-06 | No synthetic-data suite for the PII scrubber (one hand-written sentence) |
| AI-01 | Azure AI Search index never executed; no schema-parity test |
| AI-06 | Embedding-failure degradation untested |
| AI-14 | Question set is Python data, not the JSON file the criterion names |
| AI-29 | Bad tool parameters and the 10-second deadline untested |
| AI-33 | Report does not compare with NFR-C2; one time-zone-dependent test |
| ML-03, ML-04, ML-13, ML-18 | Nothing has ever been recorded in MLflow (`MlflowRegistry` is uncovered and unused) |
| ML-10 | Drift/AUC metrics exist but no alert rule consumes them |
| ML-14 | pandera is named but not used |
| CIT-05 | No PR `terraform plan` with a read-only identity |
| GOP-08 | Default-deny covers ingress only; egress is unrestricted |
| GOV-02 | No GitOps bot bypass; admin role can merge without review |
| IAC-04 | No tag policy (tags are set by convention only) |

### Cross-cutting observations

1. **The PR template is not the Definition of Done.** `00-workflow.md` §4 asks for coverage, scans, a catalog test case
   per acceptance criterion and updated docs; the template asks for none of these. Tasks were closed with the template
   fully ticked. Fixing ARC-10 first makes every later PR honest.
2. **"Done" was set where the other half of a criterion lives in a service that does not exist yet** (ML-12, SHR-21,
   and the AI flows that only ran against fakes). `clinical`, `scheduling`, `notification`, `document`, `audit` are
   scaffolds with no controllers.
3. **Cloud-only paths are untested everywhere**: Service Bus (SHR-08), Azure AI Search (AI-01), MLflow (ML-03/04/13/18),
   ACR push/sign (CIT-03), promotion (CIT-06), Argo CD bootstrap (GOP-03), Terraform apply (IAC-*). They are marked
   `pragma: no cover - exercised in UAT/cluster`, and there has been no UAT. MLflow is the exception that could be closed
   today: it runs on the local cluster.
4. **Documents that contradict the status board**: `dev/README.md` ("LOC-02 not yet available", "`make quickstart` has not
   been run end-to-end"), `shp-platform-templates/README.md` ("Not yet run on real GitHub Actions", "Dependabot proposes
   upgrades").
5. **The test catalog was not kept up by the AI/ML tasks** — see "Catalog trace per task" below: 36 of 41
   task–criterion pairs lack full automation in the catalog, most of them because existing tests were never linked.

### Suggested order of repair

1. Quick configuration wins: CIT-07 (CodeQL), GOV-06 (Dependabot), SHR-20 (remove the override), GW-01 (route).
2. Make the checklist real: ARC-10 / GOV-03 (PR template = DoD §4).
3. Contract and document truth: ARC-06 (clinical.yaml), ARC-02 (NFR measurement), ARC-03, LOC-01/LOC-02 README notes, ML-14.
4. Code gaps with tests: SHR-21, WEB-02, PY-02, PY-06, AI-06, AI-29, AI-33, ML-11, NTF-01 (run the Mongo test in CI).
5. Close the MLflow gap on the local cluster: ML-03, ML-04, ML-13, ML-18.
6. Platform hardening: GOP-08 (egress), SCF-04/05/06, IAC-04, CIT-05, ML-10 alerts.
7. Link the existing AI/ML/PY tests in `catalog/automation.yaml`, add `TC-AC-12.3`, raise the trace baseline.
8. Reopen what cannot be done yet and say why: ML-12 (needs notification), SHR-08 / AI-01 / CIT-06 / GOP-03 (need UAT).

## Catalog trace per task (DoD §4: every acceptance criterion has a test case and an automated test)

Method: for every audited task, the `AC-x.y` and `NFR-*` ids named in its backlog line (Refs, Done-when, title) were looked
up in `docs/06-testing/catalog/*.yaml` (`covers`), and each covering test case was looked up in
`docs/06-testing/catalog/automation.yaml` and in the Playwright titles (`web/e2e`). 41 task–criterion pairs result;
84 of the 125 tasks name no AC/NFR id and are not in the table.

Result: **5 pairs are fully automated** (ARC-11, DAT-01 → NFR-SEC9; IAC-08 → NFR-SEC4; WEB-01 → NFR-I2); 36 are not.

### Findings

1. **The AI and ML tasks never updated the catalog.** Every test case behind AC-11.x, AC-12.x, AC-14.x, AC-15.x,
   NFR-AI1..AI3, NFR-AI6, NFR-C2, NFR-SEC8 and TC-SEC-17/18/19 has **no entry in `automation.yaml`** — although the
   automated tests exist in `services/ai/tests`, `services/ml/tests` and `libs/python/tests` (see the AI, ML and PY
   sections). Ten of these test cases are **P0** (TC-AC-11.2, TC-AC-12.1, TC-AC-14.2, TC-NFR-SEC8, TC-SEC-17, TC-SEC-18,
   TC-SEC-19 and, on the platform side, TC-NFR-SEC7, TC-SEC-20/21), and §4 says P0 test cases "must have automated tests
   before merge". For the AI/ML cases the work was done and the link was never recorded, which is one reason the trace report shows only 25 of 121 P0 cases automated or partial.
   - Fix: add `automated`/`partial` entries with `refs` to the existing pytest functions (the tool accepts any
     `path::name`), then `python3 -I tools/qa/trace.py --update-baseline`. Use `partial` with a `gap` where the test runs
     against a fake provider or a fake downstream service.
2. **AC-12.3 (the "AI-generated" label) has no test case at all**, yet AI-21 names it and
   `services/ai/tests/test_summary.py` asserts the label. Add `TC-AC-12.3` to `catalog/ai.yaml` and link it.
3. **Platform criteria are honest but open:** TC-NFR-SEC6 / TC-SEC-24 (`partial`), TC-NFR-D1, TC-NFR-D3 (`planned`),
   TC-NFR-SEC7 / TC-SEC-20 / TC-SEC-21, TC-NFR-C1, TC-NFR-C3, TC-NFR-I3 (no entry). These need a cloud environment or an
   explicit `planned` entry with `blocked_by` and `owner` — a missing entry hides them from the plan.
4. `tools/qa/trace.py --check` passes in spite of all this because the gate only fails on requirements without a test
   case, broken refs and a falling baseline. It does not fail on a P0 test case without automation. Consider making
   "P0 test case of a closed task has no automation entry" a failure once the entries above exist.

### Table

| Task | Criterion | Test case | Priority | Automation |
|---|---|---|---|---|
| AI-08 | AC-11.1 | TC-AC-11.1 | P1 | **no entry in automation.yaml** |
| AI-10 | AC-11.2 | TC-AC-11.2 | P0 | **no entry in automation.yaml** |
| AI-10 | AC-11.2 | TC-SEC-17 | P0 | **no entry in automation.yaml** |
| AI-11 | AC-11.2 | TC-AC-11.2 | P0 | **no entry in automation.yaml** |
| AI-11 | AC-11.2 | TC-SEC-17 | P0 | **no entry in automation.yaml** |
| AI-13 | NFR-C2 | TC-NFR-C2 | P1 | **no entry in automation.yaml** |
| AI-14 | NFR-AI1 | TC-NFR-AI1 | P1 | **no entry in automation.yaml** |
| AI-15 | NFR-AI2 | TC-NFR-AI2 | P1 | **no entry in automation.yaml** |
| AI-16 | NFR-AI1 | TC-NFR-AI1 | P1 | **no entry in automation.yaml** |
| AI-16 | NFR-AI2 | TC-NFR-AI2 | P1 | **no entry in automation.yaml** |
| AI-17 | NFR-AI3 | TC-NFR-AI3 | P1 | **no entry in automation.yaml** |
| AI-17 | NFR-AI3 | TC-SEC-17 | P0 | **no entry in automation.yaml** |
| AI-19 | AC-12.1 | TC-AC-12.1 | P0 | **no entry in automation.yaml** |
| AI-19 | AC-12.1 | TC-SEC-19 | P0 | **no entry in automation.yaml** |
| AI-20 | AC-12.2 | TC-AC-12.2 | P1 | **no entry in automation.yaml** |
| AI-21 | AC-12.3 | — | — | **no test case in the catalog** |
| AI-29 | AC-14.2 | TC-AC-14.2 | P0 | **no entry in automation.yaml** |
| AI-30 | AC-14.1 | TC-AC-14.1 | P1 | **no entry in automation.yaml** |
| AI-31 | AC-14.3 | TC-AC-14.3 | P1 | **no entry in automation.yaml** |
| AI-33 | NFR-C2 | TC-NFR-C2 | P1 | **no entry in automation.yaml** |
| ARC-09 | NFR-O4 | TC-NFR-O4 | P1 | partial |
| ARC-09 | NFR-O4 | TC-SEC-11 | P0 | partial |
| ARC-10 | NFR-D1 | TC-NFR-D1 | P1 | planned |
| ARC-11 | NFR-SEC9 | TC-NFR-SEC9 | P0 | automated |
| CIT-03 | NFR-SEC6 | TC-NFR-SEC6 | P0 | partial |
| CIT-03 | NFR-SEC6 | TC-SEC-24 | P0 | partial |
| CIT-05 | NFR-D3 | TC-NFR-D3 | P1 | planned |
| CIT-07 | NFR-SEC6 | TC-NFR-SEC6 | P0 | partial |
| CIT-07 | NFR-SEC6 | TC-SEC-24 | P0 | partial |
| DAT-01 | NFR-SEC9 | TC-NFR-SEC9 | P0 | automated |
| GOP-08 | NFR-SEC7 | TC-NFR-SEC7 | P0 | **no entry in automation.yaml** |
| GOP-08 | NFR-SEC7 | TC-SEC-20 | P0 | **no entry in automation.yaml** |
| GOP-08 | NFR-SEC7 | TC-SEC-21 | P0 | **no entry in automation.yaml** |
| GOV-03 | NFR-D1 | TC-NFR-D1 | P1 | planned |
| GOV-06 | NFR-SEC6 | TC-NFR-SEC6 | P0 | partial |
| GOV-06 | NFR-SEC6 | TC-SEC-24 | P0 | partial |
| GW-02 | NFR-S3 | TC-NFR-S3 | P1 | partial |
| IAC-04 | NFR-C1 | TC-NFR-C1 | P1 | **no entry in automation.yaml** |
| IAC-04 | NFR-C3 | TC-NFR-C3 | P1 | **no entry in automation.yaml** |
| IAC-08 | NFR-SEC4 | TC-NFR-SEC4 | P0 | automated |
| IAC-08 | NFR-SEC4 | TC-SEC-23 | P0 | automated |
| LOC-01 | NFR-I3 | TC-NFR-I3 | P1 | **no entry in automation.yaml** |
| LOC-02 | NFR-SEC1 | TC-NFR-SEC1 | P0 | partial |
| LOC-02 | NFR-SEC1 | TC-SEC-04 | P0 | partial |
| LOC-02 | NFR-SEC1 | TC-SEC-05 | P0 | **no entry in automation.yaml** |
| LOC-02 | NFR-SEC1 | TC-SEC-06 | P0 | automated |
| LOC-02 | NFR-SEC1 | TC-SEC-28 | P0 | automated(e2e) |
| LOC-08 | NFR-I3 | TC-NFR-I3 | P1 | **no entry in automation.yaml** |
| ML-07 | AC-15.2 | TC-AC-15.2 | P1 | **no entry in automation.yaml** |
| ML-10 | NFR-AI6 | TC-NFR-AI6 | P1 | **no entry in automation.yaml** |
| ML-12 | AC-15.1 | TC-AC-15.1 | P1 | **no entry in automation.yaml** |
| OBS-04 | NFR-O1 | TC-NFR-O1 | P1 | partial |
| OBS-04 | NFR-O4 | TC-NFR-O4 | P1 | partial |
| OBS-04 | NFR-O4 | TC-SEC-11 | P0 | partial |
| PY-06 | NFR-SEC8 | TC-NFR-SEC8 | P0 | **no entry in automation.yaml** |
| PY-06 | NFR-SEC8 | TC-SEC-17 | P0 | **no entry in automation.yaml** |
| PY-06 | NFR-SEC8 | TC-SEC-18 | P0 | **no entry in automation.yaml** |
| PY-06 | NFR-SEC8 | TC-SEC-19 | P0 | **no entry in automation.yaml** |
| PY-09 | NFR-C2 | TC-NFR-C2 | P1 | **no entry in automation.yaml** |
| WEB-01 | NFR-I2 | TC-NFR-I2 | P1 | automated(e2e) |

## Findings by task

### ARC — architecture and governance documents

#### ARC-01 — G1 sign-off: business analysis · PASS
- Done when: scope, KPIs and business rules signed off by the team; doc status = Approved.
- Evidence: `docs/01-business-analysis/business-analysis.md:3` — "Status: **Approved - G1 passed on 2026-10-26**".

#### ARC-02 — G2 sign-off: finalize NFRs and MoSCoW · FAIL
- Done when: every NFR has a number + measurement method; Must/Should/Could list finalized.
- Evidence: MoSCoW is final (`functional-requirements.md`: 11 M, 4 S, 1 C; status Approved).
- Gap 1: `docs/02-requirements/non-functional-requirements.md` — only §1 Performance has a `Measurement method` column
  (9 rows). The 40 rows in §2–§9 (NFR-S*, A*, SEC*, O*, D*, C*, AI*, I*) have `ID | Requirement | Target` only, while line 3 of
  the same file claims "Every NFR has a number, a measurement method and a measurement environment".
- Gap 2: the §7 Cost table header still reads "Target (pending team agreement based on the existing Azure infrastructure)" —
  not finalized, in a document marked Approved.
- Gap 3 (numbers): several targets are not numbers — NFR-S4, NFR-SEC3, NFR-SEC4, NFR-SEC7, NFR-O2, NFR-O3, NFR-O4, NFR-C3,
  NFR-I1, NFR-I2.
- Fix: add a `Measurement method` (and environment) column to §2–§9; most methods already exist in
  `docs/06-testing/content/10-test-plan.yaml` (Performance and resilience table) and `docs/06-testing/catalog/nfr.yaml` and
  only need to be referenced. Remove the "pending" wording or record the agreed budget.

#### ARC-03 — G3 sign-off: move ADR 0001-0010 to Accepted · PARTIAL
- Done when: each ADR traces back to FR/NFR; status Accepted.
- Evidence: ADR-0001, 0003–0010 are `Accepted (2026-10-26)`; ADR-0002 is `Superseded` by ADR-0015 (acceptable).
  `docs/02-requirements/traceability.md` maps ADR-0001, 0003–0010 to BP/UC/NFR.
- Gap: inside the ADR files only 0004 and 0009 name the requirements they serve; 0001, 0003, 0005, 0006, 0007, 0008, 0010
  have no FR/NFR reference, so the trace only exists in the reverse direction (traceability.md → ADR).
- Fix: add a one-line "Related requirements" to the Context of those seven ADRs (copy from traceability.md).

#### ARC-04 — C4 Context and Container diagrams · PASS
- Evidence: `docs/03-architecture/architecture-diagrams.md` §1 (Context) and §2 (Container), Mermaid; all eight services of
  the catalog (`overview.md` §6) plus gateway and web app appear in the container diagram.

#### ARC-05 — Data model per service · PASS
- Evidence: `detailed-design/01-domain-model.md` §1 (bounded contexts and data ownership), §4 (profile per resource);
  search parameters are tabulated per endpoint in `08-api-scheduling.md` (S3, S8, S13), `09-api-clinical.md`, `10-api-document.md`.

#### ARC-06 — Skeleton API contracts (OpenAPI) · PARTIAL
- Done when: openapi.yaml per service with endpoints and OperationOutcome error codes; lint passes.
- Evidence: `contracts/openapi/{scheduling,clinical,document,notification,audit,ai,ml}.yaml`;
  `tools/contracts/validate_openapi.py` → OK, 93 operations, 7 services.
- Gap: `contracts/openapi/clinical.yaml` does not contain every endpoint of `09-api-clinical.md` §1. `tools/contracts/gen_openapi.py`
  does not expand the shorthand used in rows C9, C10, C16, C17, C18, C21, so these operations are missing or malformed:
  - C9 `GET /fhir/Practitioner/{id}` — missing;
  - C10 `PUT` (Practitioner / PractitionerRole) — missing;
  - C16 `GET /fhir/Observation` — missing; `GET /fhir/Observation/{id}` — emitted as the bogus path **`/{id}`** (operation `C16-2`, line 1115);
  - C17 `GET`, `PUT` on `/fhir/Condition` — missing;
  - C18 `GET`, `PUT` on `/fhir/AllergyIntolerance` — missing;
  - C21 `GET /api/v1/admin/users`, `POST /api/v1/admin/users` — missing (only the `PUT .../{subject}` exists).
  The validator still reports OK because it only checks what the generator emitted.
- Fix: write the six rows out in full in `09-api-clinical.md` (one method + path per back-tick pair) or teach the generator
  the shorthand, regenerate, and make `validate_openapi.py` reject any path that does not start with `/fhir/`, `/api/v1/` or `/internal/`.

#### ARC-07 — Event contracts (JSON Schema) · PASS
- Evidence: `contracts/events/**.v1.json` with examples for appointment-booked/cancelled, document-uploaded, audit recorded,
  no-show-scored (and more); `validate_events.py` → OK, 11 schemas.

#### ARC-09 — Coding standards & conventions · PASS
- Evidence: `docs/00-conventions.md` §1 directory layout, §2 errors, §3 logging/tracing/metrics, §4 configuration,
  §5 API and versioning.

#### ARC-10 — Definition of Ready/Done, PR and branch workflow · PARTIAL
- Done when: document in docs/00; PR template reflects the DoD.
- Evidence: `docs/00-workflow.md` §3 (DoR), §4 (DoD); `docs/00-git-github.md`.
- Gap: `.github/pull_request_template.md` does not reflect §4. Missing from the template checklist: line coverage >= 70%;
  image/dependency scans without High/Critical; "every AC has a test case in `docs/06-testing/catalog` + automated test,
  `automation.yaml` and the trace baseline updated"; "related documentation updated"; "audit emitted for every decision /
  row in the authorization matrix". The template also carries items §4 does not have (outbox/inbox, self-review).
- Why it matters: PRs have been merged with the template fully ticked while §4 items were never asked.
- Fix: make the template checklist a 1:1 copy of §4 (or make §4 point at the template as the single list).

#### ARC-11 — Synthetic data specification · PASS
- Evidence: `docs/00-synthetic-data.md` §1–§7 (principles, entities, per-field rules, clinical data, distributions, checks).

#### ARC-12 — Test strategy · PASS
- Evidence: `docs/06-testing/content/10-test-plan.yaml` — "Test levels and types", "Performance and resilience testing"
  (rows mapped to NFR-P2, S1–S3, A3–A7), entry/exit criteria with the coverage and automation targets.

#### ARC-19 — contracts/authz/matrix.yaml · PASS
- Evidence: `tools/contracts/validate_authz.py` → OK, 72 rules, 16 resources, 2 denyAll; the validator fails on any
  (resource, action) without a rule or a denyAll entry.
- Note: there is no automated cross-check between `docs/02-requirements/access-control-matrix.md` and the YAML; the two can drift.

### SHR — shared Java libraries (`libs/java`)

Coverage figures for this group are in the "Coverage" table at the end of the section.

#### SHR-01 — FHIR R4 JSON serialize/parse + round-trip tests · PASS
- Evidence: `libs/java/shp-fhir/.../FhirJsonTest.java` — round-trip and untyped parse over 12 fixtures covering all 11
  in-scope resource types (01-domain-model §4); `invalidPatientIsValidationFailed`,
  `untypedParseRejectsMissingOrUnknownResourceType` assert `validation-failed` (400).

#### SHR-02 — Error model + error code catalog · PASS
- Evidence: `UnitTest.catalogMatchesTheErrorTableInTheDesignDocument` checks every code of 07 §5 against the catalog in both
  directions; `WebTest.apiErrorIsProblemJsonWithVietnameseMessage`, `fhirErrorIsOperationOutcome` (English via
  `Accept-Language: en`), `unexpectedFailureIsAuditedAsErrorWithoutLeaking`.
- Note: "every code has a test" is satisfied by the table-driven status check, not by one HTTP test per code; the vi/en text
  is asserted for two codes only.

#### SHR-05 — ETag/If-Match filter and mandatory Idempotency-Key · PASS
- Evidence: `PreconditionTest` — `putWithoutIfMatchIs428`, `stateChangeWithoutIdempotencyKeyIs428`,
  `staleOrMalformedIfMatchIs412`, `etagComesFromVersionedDtoOrFhirMetaVersionId`; `ETagAdviceTest` (5 tests).

#### SHR-06 — CloudEvents envelope + JSON Schema validation + contract test helper · PASS
- Evidence: `EventTest` (10 tests): every contract example validates, missing field / wrong format / unknown type / bad
  envelope are rejected. Validation is enforced centrally in `PostgresOutbox`, `MongoOutbox` (publish) and `EventConsumers`
  (consume), so every service that uses them inherits it.
- Note: no service test calls `EventSchemas` directly yet — no Java service publishes a business event so far.

#### SHR-07 — Shared outbox relay (PostgreSQL and MongoDB) · PASS
- Evidence: `OutboxTest` / `MongoOutboxTest` — `busOutageLosesNothingAndRecoversInOrder`,
  `severalReplicasNeverPublishAnEventTwice`, `backgroundLoopPublishesWithoutBeingAsked`,
  `eventLockedByADeadReplicaIsReclaimedAfterTheLeaseExpires` (Mongo).

#### SHR-08 — Platform consumer: idempotent inbox + Spring Cloud Stream, retry/DLQ · PARTIAL
- Evidence: `MessagingRabbitTest` — `duplicateDeliveryIsHandledOnce`, `failingHandlerIsDeliveredFiveTimesThenDeadLettered`,
  `eventWithInvalidDataGoesToDlqWithoutReachingTheHandler`; `InboxTest`, `MongoInboxIdempotencyTest`.
- Gap: the Service Bus path (title: "Service Bus cloud") is configuration only
  (`META-INF/shp/messaging.properties`, binder dependency). Nothing exercises 5 attempts → DLQ on Service Bus; it has never
  run against a namespace. UNVERIFIED until a UAT environment exists (depends on IAC/ENV tasks).

#### SHR-09 — Audit emitter library · PASS
- Evidence: `AuditTest` (16 tests): IP masking, PII/values rejected in `resource`, long reason rejected;
  `WebTest.searchAuditRecordsParameterNamesButNeverValues`, `patientReadsOwnRecordAndTheAccessIsAudited`.

#### SHR-12 — Idempotency-Key for MongoDB · PASS
- Evidence: `MongoInboxIdempotencyTest` — `firstRequestClaimsAndSameRequestReplays`,
  `sameKeyWithADifferentBodyOrOperationIs422`, `parallelRequestsWithOneKeyProduceExactlyOneNewClaim`, `ttlIndexesExist`.

#### SHR-20 — JWT bearer from Keycloak · PARTIAL
- Evidence: `shp-security/.../KeycloakTokenTest` — valid, tampered signature, expired, wrong audience/issuer, staff without
  MFA; settings come from `ShpAuthProperties` (environment variables).
- Gap: `libs/java/shp-security/pom.xml:17` sets `shp.coverage.minimum` to `0.00` with the comment "this module has no tests
  of its own". The module now has `KeycloakTokenTest`, so the comment is stale and the 70% gate of the shared DoD is switched
  off for the module that validates every token.
- Fix: delete the override (and the comment); add tests if the real figure is under 70%.

#### SHR-21 — Identity resolver · FAIL
- Done when: locked accounts rejected within <= 60 seconds; cache expires correctly; on clinical failure use the cache,
  return 503 when there is no cache. Title: `sub → fhirUser + roles + disabled + linkedProfiles (record, scope)`.
- Evidence for what works: `IdentityResolver` (60 s hit TTL, 5 s miss TTL), `UnitTest.linkIsCachedForSixtySecondsThenRefreshed`,
  `missingLinkIsCachedOnlyBriefly`, `WebTest.disabledAccountIs403EvenOnRegistration`.
- Gap 1 — no failure fallback: `IdentityResolver.resolve` (`libs/java/shp-web/.../auth/IdentityResolver.java:30-45`) calls
  `client.find` whenever the entry has expired and lets the exception through. An expired entry is never reused when
  clinical is down, so "on clinical failure use the cache" is not implemented.
- Gap 2 — no 503: `HttpIdentityLinkClient` throws `IllegalStateException` on a non-200/404 answer; nothing maps it to a
  503 catalog code, so the caller gets `internal-error` (500). No test covers either case.
- Gap 3 — `linkedProfiles` and `roles` are not resolved: `IdentityLink` is `(fhirUserType, fhirUserId, disabled)`. The
  `roles` field of the clinical answer is parsed away, and there is no `linkedProfiles (record, scope)` at all. `Principal`
  carries only `subject, roles (from the token), fhirUserId`. The linked predicates of SHR-30 therefore depend on each
  service filling `ResourceContext.linkedProfile/linkedRecords/guardianLink` by itself, with no shared source.
- Gap 4 — the issue's own closing note says "the internal clinical endpoint is a dev task": the endpoint the resolver
  calls does not exist yet, so the resolver has only ever run against a stub.
- Fix: (a) keep the last known link and serve it when `client.find` fails; throw a 503 catalog error when there is none;
  (b) extend `IdentityLink` with `linkedProfiles`; (c) tests for stale-on-failure, 503-without-cache, linked profiles.

#### SHR-22 — Authz: schema + loader + validator · PASS
- Evidence: `MatrixDrivenTest.loaderRejectsUnsafeMatrix`, `loaderReportsMissingRuleDuplicateIdAndDenyAllViolation`,
  `embeddedMatrixEqualsTheContractFile`; `tools/contracts/validate_authz.py` in CI.

#### SHR-23 — Authz: predicates · PASS
- Evidence: `Predicates` defines all eight (plus `linked`, `linked-records`, `guardian`, `unfinished-encounter`);
  `ruleAllowsWhenAllPredicatesHold` / `ruleDeniesWhenAnySinglePredicateFails` run for every rule id of the matrix;
  `cancelWindowIsExactlyTwoHours`. Care and break-glass facts arrive through `ResourceContext` (supplied by the
  `ResourceContextResolver` interface), so they are mockable.

#### SHR-24 — Authz: default-deny decision engine · PASS
- Evidence: `defaultDenyForRolesWithoutRuleEvenWhenEveryPredicateHolds`, `unknownResourceOrActionIsDenied`,
  `multipleRolesUseAnyMatchingRule`, `careTakesPrecedenceOverBreakglassForPurpose`, `decisionCarriesFieldAndHideConstraints`.

#### SHR-25 — Authz: Spring endpoint filter + "every endpoint must have a rule" test · PASS
- Evidence: `WebTest.scannerFindsEndpointsWithoutADeclaredRule`; the scanner (`UnprotectedEndpoints`) is asserted in the
  application test of scheduling, clinical, document, notification and audit; `denyAsNotFoundHidesExistenceButStillAudits`.

#### SHR-27 — Authz: emit audit for every decision · PASS
- Evidence: `AuditTest.deniedDecisionIsAuditedWithReasonAndAllowedWithPurpose`; `WebTest.doctorNeedsACareRelationship`,
  `denyAsNotFoundHidesExistenceButStillAudits`, `unauthenticatedRequestNeverReachesHandlerOrAudit`.

#### SHR-28 — Idempotency-Key middleware for PostgreSQL · PASS
- Evidence: `IdempotencyTest` — replay, `sameKeyWithADifferentBodyOrOperationIs422`,
  `parallelRequestsWithOneKeyProduceExactlyOneNewClaim`; missing key → 428 in `PreconditionTest`.

#### SHR-30 — Authz: linked, linked-records, guardian; onBehalfOf in audit · PASS
- Evidence: matrix-driven allow/deny tests include every `*-LINKED` rule; `bookingScopeLinkAllowsAppointmentsButNotClinicalRecords`,
  `onlyGuardianMayUpdateAdministrativeFieldsOfAnotherProfile`; `AuditTest.onBehalfOfKeepsTheActingAccountAndTheAccessedProfile`.
- Note: see SHR-21 gap 3 — nothing in the shared library produces the link facts these rules consume.


#### Coverage (shared DoD: line coverage >= 70%) — `mvn -B -o verify`, JaCoCo, tree `ee959d9`

| Module | Lines | Covered | Ratio |
|---|---|---|---|
| libs/java/shp-authz | 190 | 176 | 0.93 |
| libs/java/shp-data-mongo | 198 | 179 | 0.90 |
| libs/java/shp-data-postgres | 142 | 134 | 0.94 |
| libs/java/shp-events | 161 | 155 | 0.96 |
| libs/java/shp-fhir | 41 | 41 | 1.00 |
| libs/java/shp-messaging | 38 | 31 | 0.82 |
| libs/java/shp-security | 38 | 36 | 0.95 |
| libs/java/shp-service-defaults | 20 | 18 | 0.90 |
| libs/java/shp-web | 368 | 328 | 0.89 |
| services/clinical | 26 | 24 | 0.92 |
| services/document | 17 | 15 | 0.88 |
| services/gateway | 92 | 81 | 0.88 |
| services/notification | 17 | 15 | 0.88 |
| services/scheduling | 10 | 8 | 0.80 |

Every module is above the gate. `shp-security` is at 0.95, so removing its `0.00` override (SHR-20) costs nothing.

### SCF — service scaffolds

Common evidence: each service has a `Dockerfile`, `deploy/values-local.yaml`, a `svc-<name>.yml` workflow whose latest run on
`main` is green (2026-11-10, `e00d73f`), and `envs/{uat,prod}/<name>/values.yaml` in `shp-gitops`.

#### SCF-01 — Scaffold scheduling service · PASS
- Evidence: skeleton packages, `application.yml` with PostgreSQL/Flyway/bus settings, `SchedulingApplicationTest`
  (`platformEndpointsWork`, `outboxEventsArePublishedToTheBus`, `apiRequiresAToken`), local/uat/prod values.

#### SCF-02 — Scaffold notification worker · PASS
- Evidence: skeleton + MongoDB settings + `svc-notification.yml`; `NotificationApplicationTest`.

#### SCF-03 — Scaffold clinical service · PASS
- Evidence: skeleton + MongoDB settings + `MongoIdentityLinkClient` + `svc-clinical.yml`; `ClinicalApplicationTest`.

#### SCF-04 — Scaffold document service · PARTIAL
- Done when: service from template; `svc-document.yml` — met.
- Gap (title: "values, Blob/MongoDB"): only MongoDB is wired. There is no Blob client, no storage setting in
  `application.yml` or `deploy/values-local.yaml`; "Blob" appears only in `infra/package-info.java`. The local Azurite that
  LOC-01 provisions is not reachable from the service configuration.
- Fix: add the storage endpoint/container settings (environment variables, Azurite locally, Workload Identity in the cloud).

#### SCF-05 — Scaffold audit worker · PARTIAL
- Done when: service from template; `svc-audit.yml` — met.
- Gap (title: "Blob WORM/Log Analytics"): same as SCF-04 — no Blob or Log Analytics setting anywhere in the service.

#### SCF-06 — Scaffold ai-service FastAPI · PARTIAL
- Done when: FastAPI from template; `svc-ai.yml`; **ai node pool**.
- Gap: `shp-infra/modules/aks` has a tainted `ai_pool` (`enable_ai_pool`), but `shp-gitops/envs/{uat,prod}/ai/values.yaml`
  sets no `nodeSelector` and no `tolerations`, so the ai pods can never land on that pool.
- Fix: add `nodeSelector` + `tolerations` matching the pool's label/taint to the ai (and Ollama) values.

#### SCF-07 — Scaffold ml-service · PASS
- Evidence: `services/ml` skeleton, Dockerfile, `svc-ml.yml` green on `main`.

### SCH / NTF — first service tasks

#### SCH-01 — Migration V001: schedule, schedule_template, slot · PASS
- Evidence: `V001__schedule_template_slot.sql` (`btree_gist`, `ex_slot_no_overlap EXCLUDE USING gist`);
  `SchedulingMigrationV001Test` on `postgres:16-alpine` via Testcontainers — `overlappingSlotsOnSameScheduleAreRejected`,
  `adjacentSlotsAreAllowed`, `overlappingTimeOnDifferentSchedulesAllowed`, 6 more.

#### NTF-01 — Bootstrap MongoDB notification · PARTIAL
- Evidence: `contracts/db/mongo-notification-ml.js` — idempotent `ensureCollection` (create or `collMod`), channel validator
  (`push|sms|email`), unique `{subjectRef, kind}` (`ux_reminder_once`), TTL `ttl_reminder_90d`, inbox/outbox; matches
  04 §1.2; run by `dev/scripts/up.sh`.
- Gap 1: the test `contracts/db/mongo-notification-ml.test.js` is never executed — no workflow, Makefile target or tool
  references it. The validator/unique/TTL behavior is therefore not protected by CI.
- Gap 2: the script only runs in `make up`. Nothing applies it in uat/prod (no Job, no hook, no ENV step).
- Fix: run the pair of scripts against a MongoDB container in `contracts.yml` (or a Testcontainers test in
  `services/notification`); decide where the bootstrap runs in the cloud.

### GW — API gateway

#### GW-01 — API Gateway Spring Cloud Gateway · PARTIAL
- Done when: routes to the services; propagates trace context.
- Evidence: 7 routes in `services/gateway/src/main/resources/application.yml`; `GatewayTest` —
  `validTokenIsRoutedToTheOwningServiceWithTheTokenForwarded`, `internalEndpointsAreNeverExposed`,
  `incomingTraceContextIsContinuedToTheUpstream`, `gatewayStartsATraceWhenTheClientSendsNone`.
- Gap: a cross-check of every public path in `contracts/openapi/*.yaml` against the route predicates finds 7 clinical paths
  with no route — the route lists `/api/v1/me` exactly, so everything below it returns 404 at the edge:
  `/api/v1/me/profiles`, `/api/v1/me/profiles/dependents`, `/api/v1/me/profile-links`,
  `/api/v1/me/profile-links/{id}/confirm`, `/api/v1/me/profile-links/{id}`, `/api/v1/me/claim-record`,
  `/api/v1/me/claim-record/verify` (C22–C28, ADR-0018).
- Fix: add `/api/v1/me/**` to the clinical route and add a test that walks the OpenAPI paths so the two cannot drift again.

#### GW-02 — Authentication + per-user rate limiting · PASS
- Evidence: `GatewayTest.missingOrInvalidTokenIs401ProblemJson`;
  `GatewayRateLimitTest.defaultLimitAllowsTheBurstThenReturns429WithRetryAfter`, `limitsAreCountedPerUser`,
  `invalidTokenIs401NotRateLimited`.

### LOC — local development toolkit

Live evidence for this group: the kind cluster `shp-local` on the auditor's machine has all core and full-profile dependencies
`Running` in `shp-deps` (PostgreSQL, MongoDB + rs-init, Redis, RabbitMQ, Azurite, Keycloak, ClamAV, OpenSearch, Ollama,
MLflow, Loki, Grafana, OTel Collector) and 8 application pods `Running` in `shp-apps`.

#### LOC-01 — Local dev toolkit on kind (core profile) · PARTIAL
- Done when: `make up` provisions the cluster and all core dependencies in < 10 minutes on a 16 GB machine; `make down`
  cleans up; README included.
- Evidence: `dev/Makefile` (`up`, `down`, `status`, `doctor`, …), `dev/chart/shp-local-deps`, `dev/README.md`; cluster live.
- Gap 1: the documented figure contradicts the criterion — `dev/README.md:26` "The first run takes 10–15 minutes" and
  `docs/onboarding.md:19` "(10-15 minutes the first time)". No measurement under 10 minutes is recorded.
- Gap 2: the "Verification status" note at the end of `dev/README.md` still says "`make quickstart` has not been run
  end-to-end on a real kind cluster", while line 102 of the same file reports a measurement on a kind cluster. One of the two is stale.
- Fix: time `make up` (core only, images cached and not cached) and record it; either meet < 10 minutes or change the
  criterion; delete the stale verification note.

#### LOC-02 — Local Keycloak realm · FAIL
- Done when: realm export with patient/doctor/nurse/admin/dpo roles, **fhir_user claim**, fake users.
- Evidence: `dev/chart/shp-local-deps/files/realm-shp.json` — realm `shp`, the five roles, clients `shp-web`, `shp-api`,
  `shp-test`, a `roles` claim mapper; fake users come from `dev/scripts/seed-users.sh`.
- Gap 1: there is no `fhir_user` claim (no mapper, zero occurrences in the export). The design has since moved to the
  identity link (15-authorization §8, SHR-21), so either the criterion or the realm is out of date — the backlog line was never updated.
- Gap 2: the export contains no users; they exist only after `make seed-users` (acceptable, but the criterion says "realm
  export with … fake users").
- Gap 3: `dev/README.md` "Not yet available (backlog)" lists **LOC-02 — complete realm (MFA for staff, additional
  claims)** (twice). The repo itself says this task is not finished, and the realm has no `amr`/MFA configuration, so the
  staff-MFA rule (SHR-20) only runs locally with MFA switched off.
- Fix: decide the claim question (drop `fhir_user` from the criterion in `tools/plan` or add the mapper), add the staff
  MFA flow (`amr` claim) to the realm, then remove the README entry.

#### LOC-03 — Walking skeleton Java · PASS
- Evidence: `services/scheduling/Dockerfile` (`gcr.io/distroless/java21-debian13:nonroot`, `USER nonroot`);
  `SchedulingApplicationTest.platformEndpointsWork` (health, info); OTel via `shp-service-defaults`; `svc-scheduling` green on `main`.

#### LOC-04 — Walking skeleton Python · PASS
- Evidence: `services/ai` and `services/ml` FastAPI skeletons, `USER 10001`, health/info from `shp_platform`; `svc-ai`,
  `svc-ml` green on `main`.

#### LOC-07 — Developer onboarding guide · PASS
- Evidence: `docs/onboarding.md` (5 steps, 15 + 20 + 20 minutes, first PR), `docs/onboarding-windows.md`.
- Note: the "< 1 hour" claim has not been timed with a new team member.

#### LOC-08 — Full local dev profile · PARTIAL
- Done when: `make up PROFILE=full` provisions infrastructure for every service; healthchecks; resource requirements
  documented. Title also says "seed synthetic data into the cluster".
- Evidence: `dev/chart/shp-local-full` (ClamAV, OpenSearch, Ollama, MLflow, Loki, Grafana, OTel Collector; 6 probes);
  `dev/README.md` documents RAM (core ~6 GB, full ~5 GB more); all seven components are `Running` on the auditor's machine.
- Gap: nothing seeds synthetic data into the cluster. `dev/README.md:113` says fake data is created "by `make seed-users`
  and by each service's seed" — `seed-users` only creates Keycloak accounts and no service has a seed. The generator
  (`tools/synthetic/generate.py`, DAT-01) writes files only; there is no loader.
- Fix: a `make seed-data` target that loads the DAT-01 output into the cluster databases (possible only once the owning
  services have their collections/tables), or remove the claim from the title until then.

### WEB — web application

Coverage: `vitest run --coverage` → 30 tests pass; lines 86.55%, statements 84.98%, functions 82.35%, branches 69.81%
(thresholds 70/70/60/60 in `web/vite.config.ts`).

#### WEB-01 — React/Vite web shell · PASS
- Evidence: `src/auth/session.ts` (oidc-client-ts), `src/auth/RequireRole.tsx`, `src/layout/menus.tsx` + `menus.test.ts`
  (role-based menu), `src/components/ErrorBoundary.tsx` wired in `AppShell.tsx:56`, `src/i18n/{vi,en}.ts`; Playwright
  `TC-NFR-SEC2`, `TC-NFR-U1-LANG`; `svc-web` green on `main`.
- Note: `ErrorBoundary` has no test of its own.

#### WEB-02 — API client · PARTIAL
- Done when: show errors per Accept-Language; **auto retry login when session expires**.
- Evidence: `src/api/client.ts` attaches the token, `Accept-Language`, `Idempotency-Key`, `If-Match`; `client.test.ts`
  (6 tests) covers the headers, OperationOutcome and Problem Details mapping.
- Gap: nothing reacts to an expired session. `accessToken()` returns `undefined` for an expired user, the request goes out
  without a token, the 401 becomes an ordinary `ApiError` and the page shows an error. There is no `signinRedirect` /
  silent renew + retry on 401 anywhere in `src/api` or `src/auth` (only `automaticSilentRenew: true`, which does not cover a
  refresh that failed or a tab that slept). No test for it either.
- Fix: on 401 `unauthenticated` (or a missing token) attempt one silent renew and replay the request; if that fails,
  `login(returnTo)`; add a unit test.

### DAT / OBS

#### DAT-01 — Vietnamese synthetic data generator · PASS
- Evidence: `tools/synthetic/generate.py` + `generators/{patients,practitioners,names,ids}.py`; `tests/synthetic/test_generators.py`
  (23 tests) — `test_same_seed_same_patients`, `test_phones_are_fake_and_unique`, `test_birth_dates_are_valid_and_follow_age_bands`,
  `test_fake_markers`, `test_emails_use_test_domain_and_are_unique`.

#### OBS-04 — Standard observability library · IN PROGRESS (not audited as done)
- State: issue #501 is open and `In Progress`. Present today: OTel wiring in `shp-service-defaults` and
  `shp_platform`, PII scrubber `libs/python/shp_platform/pii.py`. No Java PII-redacting log enricher and no "logs contain
  no PII" test exist yet — those are the remaining work of the task itself, not an audit finding.

### PY — shared Python library (`libs/python/shp_platform`)

`pytest libs/python` → 61 passed; coverage 83% overall. Two modules have **0%** coverage (no test imports them):
`shp_platform/idempotency.py` (42 statements, used by `services/ai/src/shp_ai/api_summary.py`) and
`shp_platform/service_token.py` (35 statements, used by `services/ai/src/shp_ai/wiring.py`). Neither belongs to a PY task of
its own; they are recorded here and again under the AI tasks that introduced them (AI-21, AI-19).

#### PY-01 — JWT authentication, role dependency for FastAPI · PASS
- Evidence: `tests/test_security.py` — valid token, `test_invalid_tokens_are_unauthenticated`,
  `test_expired_within_clock_skew_is_accepted`, `test_staff_without_mfa_is_rejected_and_with_mfa_accepted`,
  `test_fastapi_dependencies_return_problem_details`.

#### PY-02 — On-behalf-of token exchange client · PARTIAL
- Done when: exchanged token carries user identity, correct aud; test with fake IdP.
- Evidence: `shp_platform/token_exchange.py`; `test_token_exchange_caches_and_maps_errors` (mock transport: cache, expiry, 403 → `forbidden`).
- Gap 1: the test asserts only `grant_type` in the request. It never checks that `subject_token` is the user's token or
  that `audience` is the requested service, and the fake IdP returns the constant string `"exchanged"`, so "carries user
  identity, correct aud" is not verified.
- Gap 2: the local realm cannot perform the exchange — `realm-shp.json` has only the clients `shp-web`, `shp-api`,
  `shp-test`; there is no confidential `ai` client and no token-exchange permission. The flow has never run against Keycloak.
- Fix: assert `subject_token`/`audience`/`requested_token_type` in the unit test and decode a signed fake token for
  `sub`/`aud`; add the `ai` client with token exchange to the realm (ties in with LOC-02).

#### PY-03 — CloudEvents consumer: schema validation, inbox, retry/DLQ · PASS
- Evidence: `tests/test_messaging.py` — `test_handle_once_is_atomic_and_idempotent`,
  `test_rabbit_roundtrip_retry_and_dead_letter`; `test_consume_is_idempotent_retries_then_dead_letters`.
- Note: the title says "inbox (Mongo/Postgres)"; only the MongoDB inbox exists in Python. AI and ML both use MongoDB, so
  nothing depends on a PostgreSQL inbox today.

#### PY-04 — Audit emitter (E11) for AI/ML · PASS
- Evidence: `test_audit_record_masks_ip_and_matches_schema`, `test_audit_rejects_search_values_in_resource`,
  `test_audit_records_on_behalf_of_when_acting_for_a_linked_profile`.

#### PY-05 — Problem Details + error code catalog for FastAPI · PASS
- Evidence: `test_every_python_error_code_is_documented_in_07`, `test_error_handlers_never_echo_input_or_exception_text`.

#### PY-06 — PII scrubber · PARTIAL
- Done when: **synthetic data test suite**: no PII left in prompts sent outside Azure.
- Evidence: `shp_platform/pii.py` (email, UUID, insurance card, CCCD/CMND, phone, date patterns + `known` values for names
  and addresses); `test_redact_removes_all_pii_and_restores`, `test_medical_numbers_are_not_redacted`,
  `test_external_provider_always_redacted_even_if_config_says_no`.
- Gap: the "suite" is one hand-written sentence. Nothing runs the scrubber over the synthetic patients of DAT-01
  (`tools/synthetic`), so name/phone/address variety (diacritics, spacing, `+84` forms, multi-part addresses) is untested.
  Names and addresses are only removed when the caller passes them in `known`; no test proves each caller does.
- Fix: a parametrized test that renders prompts from N generated patients and asserts that no generated name, phone,
  email, address or identifier survives `redact`.

#### PY-07 — LLM provider interface + adapters + fake provider · PASS
- Evidence: `shp_platform/llm/provider.py`; `test_openai_adapter_maps_errors` (429, 503, 400),
  `test_openai_adapter_parses_completion_and_timeout`, `tests/test_tool_calling.py` (6 tests).

#### PY-08 — Router: model chain, timeout, failover · PASS
- Evidence: `test_router_falls_back_in_order_then_reports_final_fallback` (asserts `fallback_level == 2`, the error list and
  `final_fallback == "retrieval-only"`), `test_router_rejects_unknown_provider`.

#### PY-09 — Circuit breaker + token/day quota + per-model metrics · PASS
- Evidence: `test_circuit_opens_after_5_failures_and_half_opens` (window 60 s), `test_budget_exhausted_stops_calls`,
  `on_call` metric hook in `router.py:123`.
- Note: `TokenBudget` is per process; `router.py:95` itself says multi-replica setups need a shared counter. See AI-13.

### AI — ai-service (`services/ai`)

`TZ=UTC pytest services/ai` → 56 passed; coverage 88% overall. Modules under 70%: `directory.py` 40%, `tokens.py` 47%,
`kb/embedding.py` 63% (the Ollama client). One test is time-zone dependent — see AI-33.

Group-wide limits that apply to several tasks below (recorded once):
- **Downstream services are fakes.** `clinical` and `scheduling` are still scaffolds (no controllers), so every AI flow
  that reads records (AI-19..21) or books/cancels (AI-29..31) has only run against `httpx.MockTransport` doubles written
  from the design. Contract drift cannot be detected yet.
- **No real model has been evaluated.** The RAG and attack suites run with an oracle/"gullible" fake provider, a hashing
  embedder and the in-memory index. They prove the pipeline and guardrail logic, not the quality of DeepSeek/Gemini/bge-m3
  (NFR-AI1, AI2, AI7 remain unmeasured).
- **Azure AI Search is unexercised.** `AzureSearchIndex` is `# pragma: no cover - exercised in UAT`.

#### AI-01 — Bootstrap MongoDB ai + AI Search index · PARTIAL
- Done when: idempotent script; same schema for both backends.
- Evidence: bootstrap runs at startup — `wiring.py:85-89` (`ensure_indexes` for kb, conversations, usage, idempotency,
  summary cache) and `SearchIndex.ensure()`; `test_opensearch_index_roundtrip` runs against a real OpenSearch container.
- Gap: the Azure AI Search definition (`kb/index.py:269`, `kb-chunks-v1`) has never been executed and no test compares its
  field list with the OpenSearch mapping, so "same schema for both backends" rests on reading the code.
- Fix: a unit test that derives the field names/types of both `ensure()` payloads and asserts they match; run once in UAT.

#### AI-02 — Document parser · PASS
- Evidence: `test_parsers_keep_sections_and_pages` (Markdown, generated DOCX, generated PDF; section titles and page
  numbers asserted; PDF without a text layer → `ParseError`), `test_upload_rejects_bad_types_and_spoofed_content`.

#### AI-03 — Chunker · PASS
- Evidence: `test_chunker_respects_sections_size_and_overlap`.

#### AI-04 — bge-m3 embedding + index upsert/delete, version swap · PASS
- Evidence: `test_lifecycle_versioning_without_gap_and_retire`.
- Note: the Ollama embedding client (`kb/embedding.py`, 63%) is only covered through a fake embedder.

#### AI-05 — Knowledge base API (AI9) · PASS
- Evidence: `test_lifecycle_versioning_without_gap_and_retire`, `test_index_failure_retries_then_keeps_approved`,
  `test_api_permissions_and_audit`.

#### AI-06 — Hybrid retrieval + security trimming + degradation · PARTIAL
- Done when: unauthorized users never see chunks; filtering tests — met (`test_security_trimming_happens_in_query`,
  `test_security_trimming_by_role_and_department`).
- Gap (title: "degrade when embedding/reranker fails"): `retrieval.py:61` falls back to BM25 on `EmbeddingError`, but no
  test triggers it. No reranker exists (documented as optional pending eval).
- Fix: one test with an embedder that raises, asserting `retrieval_mode` and that an answer is still produced.

#### AI-07 — Versioned prompt template + context assembly · PASS
- Evidence: `prompts/{rag-answer,patient-summary,booking-agent}`; `api_assistant.py:98` stores `promptVersion` on the
  assistant message.
- Note: no test asserts the stored `promptVersion`.

#### AI-08 — Answer generation + citation check + answered=false · PASS
- Evidence: `test_no_relevant_context_never_calls_llm`, `test_grounded_answer_with_valid_citation_audit_and_usage`.

#### AI-10 — Input guardrail · PASS
- Evidence: `test_input_guard_blocks_injection`, `test_input_guard_allows_medical_questions`,
  `test_prompt_injection_blocked_and_audited`.

#### AI-11 — Output guardrail · PASS
- Evidence: `test_bad_citation_retries_next_model_once`, `test_output_guard_rejects_directives_and_unsupported_doses`,
  `test_document_cannot_break_out_of_data_block`.

#### AI-12 — Conversations/messages + feedback and history API · PASS
- Evidence: `conversations.py` (TTL 30 days on conversations and messages, unique feedback index);
  `test_feedback_and_history_owner_only` (second rating → 409).

#### AI-13 — Token/cost/fallback tracking + usage API + 80% alert · PASS
- Evidence: `usage.py` (`MongoTokenBudget` shared across replicas, `shp_ai_budget_alert` gauge at 0.8);
  `api_assistant.py:158` `groupBy: task|model|day`; `test_usage_report_admin_only`, `test_budget_exhausted_returns_503_with_citations`.
- Note: the "alert" is a Prometheus gauge only; no alert rule consumes it yet (OBS tasks).

#### AI-14 — RAG eval set part 1 · PARTIAL
- Done when: **JSON file** with schema check.
- Evidence: 50 part-1 questions, validated against `rag.QUESTION_SCHEMA` in `test_question_sets_are_large_enough_and_valid`.
- Gap: the set is Python data (`eval/corpus.py`, `rag.questions()`), not a JSON file. Substance met, form differs.
- Fix: export to `services/ai/eval/questions.json` (or amend the criterion).

#### AI-15 — RAG eval set part 2 · PASS
- Evidence: the same test asserts `>= 100` questions and `>= 15` unanswerable, no duplicates.

#### AI-16 — Eval harness · PASS
- Evidence: `python -m shp_ai.eval.rag` writes `rag-eval.json` + `rag-eval.md`; `test_rag_pipeline_meets_nfr_ai1_ai2`.
- Note: see the group-wide limit — the figures come from `rag.oracle_router()`, not from a real model.

#### AI-17 — Prompt injection attack set + runner · PASS
- Evidence: `eval/attacks.py` (`QUESTION_ATTACKS` + `DOCUMENT_ATTACKS`), `test_attack_set_has_zero_bypass` (`samples >= 50`,
  `bypasses == 0`) runs in `svc-ai` CI; `test_ingest_drops_injected_chunks`.

#### AI-19 — Gather data for summary via OBO · PASS (against a fake clinical service)
- Evidence: `test_summary_keeps_only_sourced_items_and_uses_obo`, `test_permissions` (403 `no-care-relationship` before
  any prompt), `test_clinical_denial_is_passed_through`.
- Note: depends on PY-02 (exchange unverified against Keycloak) and on clinical endpoints that do not exist yet.

#### AI-20 — Prompt + output schema + source validator · PASS
- Evidence: `test_summary_keeps_only_sourced_items_and_uses_obo` (`droppedItems == 2`).

#### AI-21 — Summary API (AI4) · PASS
- Evidence: `test_idempotency_replay_and_cache` (10-minute cache, `CACHE_TTL`), `test_all_models_fail_returns_structured_timeline`,
  label "Do AI tạo, cần bác sĩ kiểm tra" asserted.

#### AI-29 — Booking bot: 4 tools, parameter schema, 6 turns, 10-second timeout · PARTIAL
- Done when: unknown tool rejected; **bad parameters blocked**.
- Evidence: `booking/tools.py` (`ALLOWED_TOOLS`, `validate_args`), `MAX_ROUNDS = 6`, `TOTAL_TIMEOUT_SECONDS = 10.0`;
  `test_bot_has_no_clinical_tool_and_rejects_unknown_tool`, `test_round_limit_stops_with_generic_reply`.
- Gap: no test sends invalid tool arguments (bad specialty, malformed dates, range > 31 days, bad ids) and none exercises the
  10-second deadline; those `validate_args` branches are the uncovered 18% of `tools.py`.
- Fix: a parametrized unit test over `validate_args` and one deadline test with a fake clock.

#### AI-30 — pending_action + confirmation API (AI8) · PASS (against a fake scheduling service)
- Evidence: `test_propose_booking_creates_pending_action_without_booking`,
  `test_confirm_books_with_patients_own_token_and_is_idempotent`, `test_confirm_rejects_non_owner_and_expired`,
  `test_slot_conflict_on_confirm_suggests_alternatives`.

#### AI-31 — Booking chat API (AI7) + fallback · PASS
- Evidence: `test_all_models_down_returns_503_booking_form_fallback`, `test_only_patients_can_use_booking_chat`.

#### AI-33 — Token and cost report by task/model · PARTIAL
- Done when: numbers from AI10; **compared with NFR-C2**.
- Evidence: `python -m shp_ai.report_usage` renders by task / model / day and lists days over 80% of the budget.
- Gap 1: the report never compares against NFR-C2 (average <= 0.005 USD per RAG request): no cost-per-request figure, no
  pass/fail line. No report has been produced and committed.
- Gap 2: `tests/test_eval.py::test_usage_report_renders_from_usage_rows` fails on any machine whose time zone is ahead of
  UTC (the usage row is stamped with the UTC date and compared with the local date). It passes in CI only because the
  runners are on UTC.
- Fix: add "average cost per `rag-answer` request vs 0.005 USD" to the report; make the test use one clock.

### ML — ml-service (`services/ml`)

`pytest services/ml` → 46 passed; coverage 87% overall, every module >= 71%.

Group-wide limit (recorded once, referenced below): **nothing has ever been recorded in MLflow.** `MlflowRegistry`
(`registry.py:103`) is `# pragma: no cover - needs an MLflow server; exercised in the cluster (LOC-08)`; every test uses
`FileRegistry`. The MLflow instance of the local cluster (`http://localhost:15000`) holds only the `Default` experiment and
zero registered models, so the class has not been exercised in the cluster either.

#### ML-01 — Appointment data with no-show patterns · PASS
- Evidence: `synth.py`; `test_generator_matches_spec_and_is_reproducible`; patterns and the ~18% rate documented in `16-mlops.md` §1.

#### ML-02 — Feature engineering + time-based split · PASS
- Evidence: `test_features_ignore_outcomes_not_yet_known`, `test_time_split_has_no_overlap`.

#### ML-03 — Baseline Logistic Regression + metrics · PARTIAL
- Done when: **recorded in MLflow**.
- Evidence: `test_baseline_learns_signal`; the pipeline logs the baseline metrics through `Registry.log_run`.
- Gap: see the group-wide limit — the run is recorded in a `FileRegistry` directory in tests and nowhere in MLflow.
- Fix (covers ML-03, ML-04, ML-13, ML-18): run the training pipeline once against the cluster MLflow and add one
  integration test for `MlflowRegistry` (Testcontainers MLflow, or the `mlflow` extra with a `file:` tracking URI).

#### ML-04 — LightGBM + hyperparameter search + MLflow tracking · PARTIAL
- Evidence: `pipeline.py:40` `PARAM_GRID` search, comparison with the baseline in the gate
  (`test_gate_passes_registers_candidate_only`, `test_gate_rules`), model saved as an artifact of the registry.
- Gap: MLflow tracking unverified (group-wide limit).

#### ML-06 — Consumer E1/E2/E3/E5/E7 · PASS
- Evidence: `test_booked_is_scored_once_with_model_version_and_features`, `test_late_cancel_and_reschedule`,
  `test_outcomes_feed_next_prediction_and_old_versions_are_ignored`, `test_patient_merge_moves_stats`.

#### ML-07 — Score on E1, emit no-show-scored via outbox · PASS
- Evidence: `test_booked_is_scored_once_with_model_version_and_features`, `test_no_model_still_records_fact_but_never_scores`.

#### ML-08 — API M1, M2, M3 · PASS
- Evidence: `test_api_m1_m2_m3_permissions_audit_and_latency` (p95 < 0.1 s asserted for M3).
- Note: the latency is measured in-process with TestClient on the developer machine/CI runner, not on a deployed pod.

#### ML-09 — Reload model on production alias change · PASS
- Evidence: `serving.py` polls every 30 s; `test_alias_change_is_picked_up_without_redeploy`.

#### ML-10 — Daily drift job, metrics and alerts · PARTIAL
- Done when: actual PSI/AUC metrics in Prometheus; **alert when above threshold**.
- Evidence: `monitor.py` (`run_daily`, 02:00 Vietnam time or `python -m shp_ml.monitor`); gauges `shp_ml_feature_psi`,
  `shp_ml_score_psi`, `shp_ml_live_auc`, `shp_ml_drift_alert`; `tests/test_monitor_retrain.py`.
- Gap: no alert exists. There is no `PrometheusRule`/alert definition in `dev/`, `shp-gitops` or `shp-infra`; the only
  consumer of these metrics is the canary analysis in `envs/prod/ml/values.yaml`. `shp_ml_drift_alert` is a gauge nobody watches.
- Fix: alert rules for `shp_ml_drift_alert == 1` and the AUC drop (with OBS tasks), or a notification from the job itself.

#### ML-11 — Bootstrap MongoDB ml · PARTIAL
- Done when: idempotent script; validator enforces ageBand, **score**; index (modelVersion, predictedAt).
- Evidence: `contracts/db/mongo-notification-ml.js` — `patient_stats`, `appointment_facts` (ageBand/channel/outcome
  validator), `predictions` (time-series with fallback), index `{modelVersion, predictedAt}`, inbox/outbox;
  `store.py` `ensure_schema` mirrors it.
- Gap 1: nothing enforces `score` (0..1) — `predictions` has no validator in the script or in `store.py`
  (the word `score` does not occur in the script). Design 04 §2.2 specifies `score` 0..1 and `threshold` 0..1.
- Gap 2: as for NTF-01, the companion test `mongo-notification-ml.test.js` is never run by CI.
- Fix: validate the range in `store.py` before insert (a time-series collection cannot carry a `$jsonSchema` validator on
  every engine) with a unit test, or add the validator where the engine allows it.

#### ML-12 — Contract test: consume E1/E5, emit E10; ML failure does not affect reminders · FAIL
- Done when: green in CI; **AC-15.1 has a test in notification**.
- Evidence: the ML side is covered (`tests/test_scoring_service.py`, schema-validated E10 through the outbox).
- Gap: `services/notification` is still the scaffold — one application test, no reminder logic and no test referencing
  no-show scoring or AC-15.1. The half of the criterion that proves reminders survive an ML outage does not exist.
- Fix: can only be completed with the notification reminder tasks (NTF-02+); until then the task should not be `Done`.

#### ML-13 — Versioned Parquet dataset, hash in the MLflow run · PARTIAL
- Evidence: `retrain.py:62-67` (immutable `datasets/no-show/<date>/<sha256>.parquet`),
  `test_same_dataset_and_seed_give_same_model`.
- Gap: "write dataset hash into MLflow run" unverified (group-wide limit).

#### ML-14 — Data validation before training **with pandera** · PARTIAL
- Evidence: `checks.py` (schema, ranges, missing rate, temporal leakage); `test_checks_stop_pipeline_on_bad_data`.
- Gap: pandera is not used and is not a dependency (`pyproject.toml`); the checks are hand-written. Behavior meets the
  Done-when ("bad data stops the pipeline with a clear reason; failure cases tested"); the named tool does not.
- Fix: amend the task title/design to "hand-written checks" or port `checks.py` to a pandera schema.

#### ML-15 — Shared feature package · PASS
- Evidence: `features.py` (100% covered) used by `pipeline.py` and `consumer.py`.

#### ML-16 — Train/serve skew test · PASS
- Evidence: `test_training_features_equal_serving_features`.

#### ML-17 — Subgroup evaluation + calibration + latency gate · PASS
- Evidence: `evaluate.py` (`MAX_SUBGROUP_GAP = 0.07`, `MAX_P95_MS = 100.0`, ECE); `test_gate_rules`, `test_ece_and_threshold`,
  `test_failed_gate_registers_nothing_and_logs_reason`.

#### ML-18 — Model card on registration · PARTIAL
- Evidence: `card.py` (purpose and limits, data, results, subgroup AUC, gates, provenance — the sections of 16-mlops §3),
  stored as `card.md` next to the model by `FileRegistry`.
- Gap: "stored with the artifact in MLflow" unverified (group-wide limit).

#### ML-19 — Drift detector · PASS
- Evidence: `test_psi_is_zero_for_same_distribution_and_large_for_shift`, `test_shifted_dataset_triggers_drift_alert`,
  `test_no_alert_on_fresh_data_from_same_generator`.

#### ML-20 — Live performance monitoring · PASS
- Evidence: `test_live_performance_needs_500_labels`; AUC-drop rule in `drift.py`.
- Note: the alert delivery gap of ML-10 applies.

#### ML-21 — Shifted synthetic dataset · PASS
- Evidence: `synth.SHIFTED`; `test_shifted_dataset_moves_lead_time_rate_and_channel`; magnitude documented in `16-mlops.md` §1.

#### ML-22 — Retraining trigger logic · PASS
- Evidence: parametrized `test_retrain_decision` (drift, AUC drop, cooldown, insufficient samples, run limit).

#### ML-23 — Retraining job · PASS
- Evidence: `test_drift_creates_request_and_retrain_registers_candidate_only`,
  `test_candidate_worse_than_production_is_rejected`, `test_failed_gate_registers_nothing_and_logs_reason`.

#### ML-24 — Shadow mode in serving · PASS
- Evidence: `test_shadow_scores_candidate_without_affecting_production`; `shp_ml_shadow_agreement_total`.
- Note: "no user-side latency increase" is asserted by design (the candidate is scored off the response path), not measured.

### CIT — reusable CI workflows (`shp-platform-templates`, pinned at `v0.1.7` by the callers)

#### CIT-01 — Reusable workflow Java CI · PASS
- Evidence: `java-ci.yml` (`workflow_call`; Spotless check, `-Werror`, tests, JaCoCo gate); called by 7 workflows in the
  application repo, all green on `main`.
- Note: the "vulnerable dependency scan" is not in `java-ci.yml`; it runs in `pr-checks.yml` (Trivy fs, OSV-Scanner,
  OWASP Dependency-Check), which every repo also calls.

#### CIT-02 — Reusable workflow Python CI · PASS
- Evidence: `python-ci.yml` — ruff check + format, mypy, pytest with coverage, pip-audit.

#### CIT-03 — Reusable workflow container · PASS
- Evidence: `container.yml` — hadolint, buildx, Trivy, SBOM, cosign keyless, push on `main`, digest output; 8 callers green.
- Note: the ACR push/sign steps have only run where OIDC and an ACR exist; not verifiable from the auditor's machine.

#### CIT-04 — Reusable workflow Helm validate · PASS
- Evidence: `helm-validate.yml` — `helm lint`, `helm template`, kubeconform, Trivy config; `chart / validate` required on the templates repo.

#### CIT-05 — Reusable workflow Terraform checks + plan · PARTIAL
- Done when: fmt, validate, tflint, Trivy config, **plan + summary in the job summary; plan via read-only OIDC**.
- Evidence: `terraform-checks.yml` (fmt, validate, tflint, Trivy config), used by `shp-infra/.github/workflows/ci.yml`.
- Gap: the reusable workflow never plans (its header: "NEVER applies. Plans run in the infra repo's own workflow"), and
  `shp-infra` `ci.yml` has no plan step either. The only `terraform plan` + job-summary is inside `env-provision.yml`, a
  manually dispatched workflow that logs in with the provisioning identity — not a PR plan and not a read-only identity.
- Fix: a PR job in `shp-infra` that logs in through a Reader-only federated credential, runs `terraform plan` and writes
  it to `$GITHUB_STEP_SUMMARY`; or amend the criterion to match the decision recorded in the workflow header.

#### CIT-06 — Reusable workflow GitOps update/promote · PASS (unexercised end to end)
- Evidence: `gitops-promote.yml` (digest update, `mode: commit` for uat, `mode: pr` for prod, environment approval).
- Note: every `envs/*/<svc>/values.yaml` in `shp-gitops` still has `digest: ""` — no promotion has ever happened. It
  cannot until the GitHub App of GOV-05 (In Progress) and an ACR exist.

#### CIT-07 — Shared check workflow for every PR · FAIL
- Done when: gitleaks, actionlint, dependency review, **CodeQL (java-kotlin, python, actions)**.
- Evidence: `pr-checks.yml` — gitleaks, actionlint, Trivy fs, OSV-Scanner, OWASP Dependency-Check (these cover the
  dependency-review intent).
- Gap: there is no CodeQL anywhere — no workflow references it and the repository's code-scanning default setup is
  `not-configured` (GitHub API). The repo is public, so CodeQL is available at no cost.
- Fix: enable CodeQL default setup for `java-kotlin`, `python`, `actions` (and `javascript-typescript` for `web/`) or add
  a `codeql.yml`; add its check to the `protect-main` required checks.

#### CIT-08 — Sample caller workflow + generator script · PASS
- Evidence: `shp-platform-templates/examples/svc-scheduling.yml`; `tools/scaffold/java_service.py:473-479` and
  `python_service.py` generate `svc-<name>.yml` with the service's path filters.

### GOP — GitOps (`shp-gitops`, `shp-platform-templates`)

#### GOP-01 — Shared microservice Helm chart · PASS
- Evidence: `charts/microservice/templates/` — workload (Deployment/Rollout), service, hpa, keda, pdb, networkpolicy,
  httproute, serviceaccount, secretproviderclass, analysistemplate; `values.schema.json`; `ci/` value sets validated in CI.

#### GOP-03 — Argo CD bootstrap · PASS (never applied to a cluster)
- Evidence: `bootstrap/argocd-values.yaml`, `bootstrap/root-{uat,prod}.yaml` (app-of-apps), `clusters/<env>/projects.yaml`
  (AppProjects), `platform.yaml` and `services.yaml` (ApplicationSets); `validate` green on `main`.
- Note: delivered as manifests rather than a chart; no cloud cluster has existed to apply them to.

#### GOP-08 — NetworkPolicy default-deny + namespace · PARTIAL
- Done when: namespace labelled PSA restricted; only designed flows opened.
- Evidence: `platform/charts/shp-baseline/templates/namespace.yaml` — `pod-security.kubernetes.io/enforce: restricted`
  and a default-deny NetworkPolicy.
- Gap: the default-deny is `policyTypes: [Ingress]` only, and the service chart leaves `networkPolicy.egress: []`
  ("empty = no egress limit"). Outbound traffic from every pod is unrestricted, which does not match "only designed flows
  opened" / NFR-SEC7 ("deny all traffic between pods by default").
- Fix: add `Egress` to the baseline policy with DNS allowed, and declare each service's egress (database, bus, Keycloak,
  OTel, and for ai the model endpoints) in `envs/services-common.yaml` / per-service values.

### GOV — repository governance

#### GOV-01 — Create 4 repos per ADR-0010 · PASS
- Evidence: `smart-hospital-platform`, `shp-infra`, `shp-gitops`, `shp-platform-templates` exist (all public), each with a README.

#### GOV-02 — Main branch protection ruleset · PARTIAL
- Evidence: ruleset `protect-main` active on all four repos — PR required with 1 approval + code-owner review, required
  status checks, no force-push, no deletion, linear history.
- Gap 1: no GitOps bot bypass exists in `shp-gitops` (the only bypass actor is a repository role). Blocked on GOV-05.
- Gap 2: every ruleset has `bypass_actors: RepositoryRole → pull_request`, i.e. the admin role can merge its own PR without
  the required review. That is how PRs were merged with a single maintainer; it should be a recorded decision, not a default.

#### GOV-03 — CODEOWNERS + PR template + issue template · PARTIAL
- Evidence: `.github/CODEOWNERS`, `pull_request_template.md`, `ISSUE_TEMPLATE/task.md` in all four repos; code-owner
  review required by the rulesets.
- Gap: "templates include a DoD checklist" — the application template's checklist does not match the Definition of Done
  (see ARC-10).

#### GOV-05 — GitHub App bot for GitOps · IN PROGRESS (not audited as done)

#### GOV-06 — Dependabot for actions, maven, pip, docker, terraform · FAIL
- Done when: **dependabot.yml in every repo**; actions pinned by commit SHA.
- Evidence: `smart-hospital-platform/.github/dependabot.yml` (maven, pip, docker, npm, github-actions); every third-party
  `uses:` in all four repos is pinned by a 40-character SHA (0 unpinned).
- Gap: `shp-infra`, `shp-gitops` and `shp-platform-templates` have **no** dependabot file on `main`. So nothing proposes
  upgrades for the terraform providers, the pinned actions in the reusable workflows, or the chart dependencies. The
  templates README even states "Dependabot proposes upgrades".
- Fix: add `.github/dependabot.yml` to the three repos (`github-actions` everywhere, `terraform` in infra, `docker`/`helm`
  where applicable).

#### GOV-08 — Translate docs, contracts, shared Python library · IN PROGRESS — PR #545 open, all checks green.
#### GOV-10 — Convert GitHub records to English · PASS — closed with the verification comment on #544.

### IAC — infrastructure (`shp-infra`)

Neither stack can be shown to have been applied from the auditor's machine (no Azure session was used for the audit); the verdicts
are on the code.

#### IAC-04 — Bootstrap: budget alert + tag policy · PARTIAL
- Done when: budget alerts at 50/80/100%; **shp-env, created-at, ttl-hours tags required**.
- Evidence: `bootstrap/main.tf:105-125` — `azurerm_consumption_budget_subscription` with notifications at 50, 80, 100.
- Gap: there is no tag policy. No `azurerm_policy_definition`/`policy_assignment` exists in the repo; the three tags are
  merely set by `modules/environment/main.tf`. Nothing rejects an untagged resource, and `cost-guard` depends on `ttl-hours`.
- Fix: an Azure Policy assignment (deny or audit) requiring the three tags on resource groups — or, if the sandbox
  subscription forbids policy assignments (see commit `2b563de` on the IAC-01 branch), record that and change the criterion.

#### IAC-08 — Module key vault · PASS
- Evidence: `modules/keyvault/main.tf` — `rbac_authorization_enabled = true`, `public_network_access_enabled = false`,
  private endpoint, `random_string.suffix` in the name; `modules/environment/main.tf:101` `purge_protection_enabled = local.is_prod`.
