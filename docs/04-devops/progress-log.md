# Phase 4 progress log - step by step in order, for checking and redoing yourself

> How to use: read from top to bottom. Each step has **goal -> work done -> commands -> verification -> result (commit)**.
> To redo a step: run the "Commands" part, then run the "Verification" part; the result must match the "Result" part.
> Status overview and remaining work: [phase4-build-log.md](phase4-build-log.md). This file is the detailed chronological version, **updated at the end of each step**.
> Status symbols: DONE = finished and verified; PARTIAL = done but not yet runnable in a real environment; TODO = not started; YOU = requires your action.
> Common preparation for running commands: Python 3.12+, .NET SDK 10, Docker, `pip install -r tools/contracts/requirements.txt`.

## Template for recording each step (mandatory for every step from now on)
Each step must have all 7 sections, so it can be traced and redone:
1. **Goal**: why do it.
2. **Work done**: the actions in order.
3. **Changes**: table `File | A/M/D | Change content` (A added, M modified, D deleted). Describe **the specific value changing from what to what** for modified files.
4. **Redo commands**.
5. **Verification** and expected result.
6. **Errors encountered and fixes** (if any).
7. **Commit** and the command to see exactly what changed: `git show --stat <commit>` (overview), `git show <commit> -- <file>` (details for one file).

## Part A - Foundation already built earlier (details in build-log)
| # | Step | Status | Commit |
|---|---|---|---|
| A1 | Phases 1-3: BA, requirements, 74 user stories, detailed design, 317 tasks by person (`docs/`, `tools/plan`) | DONE | 3cace72, c378bfc, 4826847 |
| A2 | ADR-0011: 2 cloud environments + dev on kind | DONE | 3a72b78 |
| A3 | Local `dev/` toolkit (kind + dependencies + Keycloak) | PARTIAL - not yet run on real kind | 365405e |
| A4 | Templates repo: `microservice` chart + CI/CD workflows | DONE (static checks) | c352248, ab2a514 |
| A5 | Infra repo: Terraform bootstrap/modules/uat/prod | DONE (validate, no apply) | 3918ddc |
| A6 | 8-service skeleton, per-service pipeline, `make quickstart`, dev guide | DONE / PARTIAL (quickstart) | 20599dd, 157f448 |

## Part B - Decisions on 2026-10-27
| # | Decision | Where recorded | Commit |
|---|---|---|---|
| B1 | Local blob = Azurite (same Azure SDK as cloud); WORM checked on UAT | ADR-0008/0011, tasks DOC-02, AUD-08, LOC-01 | 621de97 |
| B2 | ADR-0012 Accepted: keep .NET + Python | `adr/0012-language-per-service.md` | 621de97 |
| B3 | ADR-0013: DeepSeek (native API) primary, Gemini free tier fallback, bge-m3 embedding local in the cluster, drop Claude and all Azure models | `adr/0013-*.md`, `06-data-ai.md`, `12-api-ai.md`, `14-sequences.md`, NFR-C2, tasks PY-07/AI-04/AI-06/AI-23/IAC-19 | eb38e94 |

**Changes (commit 621de97 for B1+B2; eb38e94 for B3)** - view with `git show --stat 621de97` and `git show --stat eb38e94`

| File | | Change content |
|---|---|---|
| `tools/plan/tasks_sa.py` (LOC-01) | M | "RabbitMQ, MinIO, Keycloak" -> "RabbitMQ, Azurite, Keycloak" |
| `tools/plan/tasks_dev.py` (DOC-02) | M | "Azure Blob (user delegation SAS) + MinIO" -> "Azure Blob SDK single code for local Azurite and cloud; choose the SAS signing method via configuration"; test "with MinIO" -> "with Azurite (Testcontainers)" |
| `tools/plan/tasks_dev.py` (AUD-08) | M | "green in CI (MinIO object lock)" -> hash chain checked in CI with Azurite, WORM checked by a smoke test on UAT |
| `docs/03-architecture/adr/0008-...`, `0011-...` | M | drop MinIO; blob lifecycle "-> MinIO" -> "Cold after 180 days"; local uses Azurite |
| `docs/03-architecture/adr/0012-...` | M | `Proposed` -> `Accepted (2026-10-27)`; sentence "Azure OpenAI SDK" -> "OpenAI-compatible/Gemini client" |
| `docs/03-architecture/adr/0013-llm-deepseek-gemini-local-embedding.md` | A | new ADR: model chain table, mandatory PII redaction, local embedding |
| `docs/03-architecture/adr/0006-...` | M | status explicitly notes the model chain table superseded by ADR-0013 |
| `docs/03-architecture/detailed-design/06-data-ai.md` | M | embedding `text-embedding-3-small` 1536 dimensions -> `bge-m3` 1024 dimensions (`dimensions: 1536` -> `1024`); drop the `semantic` block of AI Search; router configuration: azure-openai/anthropic -> deepseek/gemini/ollama + `embedding` |
| `12-api-ai.md`, `14-sequences.md`, `overview.md` | M | provider/model names in examples and diagrams |
| `docs/02-requirements/non-functional-requirements.md` | M | NFR-C2: <= 0.02 -> <= 0.005 USD/request RAG, Gemini only within the free tier |
| `tools/plan/tasks_dev.py`, `tasks_sa.py` | M | PY-07 (DeepSeek/Gemini/Ollama adapter), AI-04 (bge-m3), AI-06 (RRF, drop semantic ranker), AI-23 (Tesseract), IAC-19 (`ai` node pool + LLM key in Key Vault, replacing the Azure OpenAI module) |
| `docs/05-plan/*`, `docs/02-requirements/user-stories.md` | M | **regenerated** with `python3 -I tools/plan/build.py` (not hand-edited) |

To redo B1-B3: edit the files above per the ADRs, then `python3 -I tools/plan/build.py` (it must print `OK: 74 stories, 317 tasks`).

## Part C - Shared contracts - the standard used by devs and services
Purpose: turn the design (text) into **machine-readable files + automated validators**, so that a service written incorrectly turns CI red.
Order and rationale: authorization -> events -> API -> terminology (devs need authorization and APIs first), finally wrap everything in one check command + CI.

### C1. Authorization matrix `contracts/authz/matrix.yaml` DONE
- **Work done:** convert `access-control-matrix.md` + `15-authorization.md` into 64 rules (resource x action x role x condition `when` x purposeOfUse), declare 16 resources (marked `clinical`), 9 predicates, `denyAll` (Audit edit/delete).
- **Invariants checked automatically** (`tools/contracts/validate_authz.py`): no duplicate ids; valid role/predicate/purpose; every declared action has a rule or is in `denyAll`; admin and dpo **have no** rules on clinical data; dpo only has rules for Audit; `breakglass` only for doctor; `own` only for patient.
- **Verification:** `python3 -I tools/contracts/validate_authz.py` -> `OK: 64 rules, 16 resources, 2 denyAll`; `python3 -I -m unittest discover -s tools/contracts -p 'test_*.py'` -> 7 tests (deliberately insert wrong rules and check that the validators catch them).
- **Dev usage:** task SHR-09 loads this file to generate tests for every cell of the matrix; the policy library reads the same file.

### C2. Event schemas `contracts/events/` DONE
- **Work done:** `tools/contracts/gen_events.py` holds the definitions of 11 events (E1-E11) and **generates** `envelope.json`, `<context>/<event>.v1.json` (JSON Schema 2020-12) and `.example.json`. Edit the schema in the generator, re-run; do not hand-edit generated files.
- **Verification:** `python3 -I tools/contracts/validate_events.py` -> all schemas valid, examples match schemas, examples wrapped in a valid CloudEvents envelope, **no field name suspected of PII**, event set matches the catalog in `13-events.md`.
- **Errors encountered and fixes:** `modelName` was flagged as PII (matched "name") -> added to the deliberate exception list; the booking channel enum initially had `mobile`, contrary to the design -> fixed to `web|chatbot|staff` per `01-domain-model.md`.

### C3. OpenAPI skeleton `contracts/openapi/<service>.yaml` DONE
- **Work done:** `tools/contracts/gen_openapi.py` reads the endpoint tables in `08..12-api-*.md` and generates OpenAPI 3.1 for 7 services: 83 operations (operationId = the ID in the document such as `S11`), permissions (`x-roles`, `x-permission`), path parameters, standard errors (FHIR `OperationOutcome` / `problem+json`), `/internal/*` tagged `x-internal`.
- **Known limitation:** no request/response body schemas yet; devs add them when doing endpoint tasks, taken from the corresponding section in the documents.
- **Design document fix:** the AI9 row in `12-api-ai.md` was written combined (`GET/POST/PUT ... , POST .../approve`) -> split into 5 explicit endpoints so machines can read it (same content).
- **Verification:** `python3 -I tools/contracts/validate_openapi.py` -> `OK: 83 operations, 7 services match the documents` (check OpenAPI validity with `openapi-spec-validator`, complete and no extra endpoints).

### C4. Terminology `contracts/terminology/code-systems.yaml` DONE (with caveat)
- 12 specialties, 6 cancellation reasons, 3 booking channels, 10 vital-sign LOINC codes, 6 LOINC document types, 12 common ICD-10 codes.
- **Honest caveat:** the LOINC/ICD-10 codes are a subset cross-checked by the SA against standard knowledge, **not yet cross-checked against the official editions**; sufficient for the capstone project's synthetic data.
- **Verification:** `python3 -I tools/contracts/validate_terminology.py` -> code formats correct, matching `01-domain-model.md` section 3 and example E8.

### C5. Check package and CI DONE (CI not yet run on GitHub)
- `tools/contracts/check_all.sh`: run tests, the 4 validators, then regenerate events/OpenAPI and `git diff --exit-code` (detect generated files that were hand-edited or out of sync with the generator).
- `.github/workflows/contracts.yml`: run `check_all.sh` when `contracts/**`, `tools/contracts/**` or the detailed design change (actions pinned by SHA).
- **Verification:** `tools/contracts/check_all.sh` -> `ALL CONTRACT CHECKS PASSED`; `actionlint` clean.

**Changes for C1-C5 (commit cd8c789)** - view with `git show --stat cd8c789`

| File / directory | | Content |
|---|---|---|
| `contracts/authz/matrix.yaml` | A | 64 rules, 16 resources, 9 predicates, 2 denyAll |
| `contracts/events/envelope.json` + `contracts/events/<ctx>/<event>.v1{,.example}.json` (23 files) | A | **generated by** `gen_events.py` |
| `contracts/openapi/{scheduling,clinical,document,notification,audit,ml,ai}.yaml` | A | **generated by** `gen_openapi.py` (83 operations) |
| `contracts/terminology/code-systems.yaml` | A | 6 code systems, 49 codes |
| `tools/contracts/gen_events.py`, `gen_openapi.py` | A | generators (source of truth for generated files) |
| `tools/contracts/validate_{authz,events,openapi,terminology}.py`, `test_validators.py`, `check_all.sh`, `requirements.txt` | A | validators + tests of the validators |
| `.github/workflows/contracts.yml` | A | CI for contracts, actions pinned by SHA |
| `docs/03-architecture/detailed-design/12-api-ai.md` | M | AI9 row: `GET/POST/PUT /api/v1/ai/kb/documents`, `POST .../{id}/approve`, `POST .../{id}/retire` -> 5 endpoints with full paths |
| `docs/04-devops/progress-log.md` | A | this file |
| `docs/04-devops/phase4-build-log.md` | M | add a link to this file |

## Part D1 - Shared .NET library: authorization (SHR-22, SHR-23, SHR-24) DONE
1. **Goal:** devs should not have to write authorization logic themselves; every service uses one engine that reads the correct `contracts/authz/matrix.yaml` (NFR-SEC2). Chosen first because it is pure logic, fully testable, and every clinical endpoint needs it.
2. **Work done (in order):**
   1. Add `YamlDotNet 16.3.0` to `Directory.Packages.props` (centralized version management).
   2. Create `libs/dotnet/Shp.Authorization`: `AuthzMatrix` (load YAML + check safety invariants like `validate_authz.py`), `Model.cs` (`Principal`, `ResourceContext`, `AuthzDecision`, predicate names), `AuthorizationEngine` (pure function `Decide`).
   3. Embed `contracts/authz/matrix.yaml` into the assembly (`EmbeddedResource`), so the service always runs the reviewed version.
   4. Create `libs/dotnet/Shp.Authorization.Tests` with tests **generated from the matrix itself** (each rule: allow when all conditions are met, deny when each condition is missing; every role pair without a rule is denied even if all predicates hold).
   5. Create `libs/dotnet/Shp.Libs.slnx` grouping the libraries; add workflow `libs-dotnet.yml`.
3. **Changes (commit 4bf82ef; view with `git show --stat 4bf82ef`):**

| File | | Content |
|---|---|---|
| `Directory.Packages.props` | M | add `<PackageVersion Include="YamlDotNet" Version="16.3.0" />` |
| `libs/dotnet/Shp.Authorization/Shp.Authorization.csproj` | A | reference YamlDotNet; embed matrix.yaml with LogicalName `matrix.yaml` |
| `libs/dotnet/Shp.Authorization/AuthzMatrix.cs` | A | load + check: duplicate ids, unknown role/predicate/purpose, denyAll, admin/dpo do not touch clinical, dpo only Audit, breakglass only doctor, own only patient, action without a rule |
| `libs/dotnet/Shp.Authorization/Model.cs` | A | `Principal(Subject, Roles, FhirUserId)`, `ResourceContext` (pre-fetched facts), `AuthzDecision(Allowed, RuleId, Purpose, Fields, Hide, DenyReason)` |
| `libs/dotnet/Shp.Authorization/AuthorizationEngine.cs` | A | rules: deny by default; first rule in file order that matches is Allow (so care -> purpose TREAT, before break-glass -> ETREAT); missing fact = predicate not met; `before-2h` computed with `TimeProvider`; deny reasons: `no-rule`, `no-care-relationship`, `unmet-<predicate>`, `role-not-allowed` |
| `libs/dotnet/Shp.Authorization.Tests/*` | A | 236 tests |
| `libs/dotnet/Shp.Libs.slnx` | A | ServiceDefaults + Authorization + Tests |
| `.github/workflows/libs-dotnet.yml` | A | call `dotnet-ci` (format, build warnings=errors, test, coverage >= 70%) |

4. **How devs use it (task SHR-25 remains for devs):** the service looks up facts (treatment relationship, break-glass, encounter status...) -> builds a `ResourceContext` -> `engine.Decide(principal, "Appointment", "cancel", ctx)`. If `Allowed=false`: return 403 and emit audit with `DenyReason`.
5. **Redo / verification commands:**
```bash
dotnet test libs/dotnet/Shp.Libs.slnx -c Release        # expected: Passed 236, Failed 0
cd libs/dotnet && dotnet format --verify-no-changes       # expected: no output
```
6. **Mutation verification (done):** temporarily modify the engine then run tests: `own` always true -> 10 tests red; cancel window 2h changed to 1h -> 2 tests red. The third mutation (unknown predicate -> allow) **was not caught**, because the loader rejects unknown predicates before they reach the engine: this is a defensive branch, unreachable through a valid matrix.
7. **Errors encountered and fixes:** test `BG-START-DOC` (`not-care`) was red because the context was built incorrectly (`care` and `not-care` are mutually exclusive) -> add a `notCareFails` parameter to the context-building function in the test; do not modify the engine.
8. **Impact on the plan:** SHR-22/23/24 (assigned to devs) were done in advance by the SA; devs only have SHR-25 (endpoint filter), SHR-26 (generate tests for each endpoint), SHR-27 (audit every decision). Ownership in `tools/plan` has not been changed yet (see D-next).

## Part D1b - Plan update: move SHR-22/23/24 to SA and add task status tracking DONE
1. **Goal:** the plan reflects who does what after D1; there is a place to record which tasks are done (previously checkboxes were always `[ ]`).
2. **Work done:** (1) remove SHR-22/23/24 from `tasks_dev.py`, add them to `tasks_sa.py` with owner `SA` (keep ID, hours 3/4/3, dependencies); (2) add `tools/plan/status.py` (ID -> `done|partial`, commit, note); (3) modify `build.py` to draw `[x]/[~]/[ ]` and the done total in `docs/05-plan/by-person/*.md`, error if `status.py` mentions a nonexistent task; (4) regenerate the documents.
3. **Changes (commit 23bc32a):**

| File | | Content |
|---|---|---|
| `tools/plan/tasks_dev.py` | M | remove 3 tuples SHR-22/23/24 (owner `D1`) |
| `tools/plan/tasks_sa.py` | M | add those 3 tuples with owner `SA` at the end of the list |
| `tools/plan/status.py` | A | 7 entries: ARC-06 (partial), ARC-07, ARC-19, ARC-20 (partial), SHR-22/23/24 |
| `tools/plan/build.py` | M | import `STATUS`; `mark`, `status_note` functions; summary line "Done/partially done"; validate status |
| `docs/05-plan/*`, `docs/02-requirements/user-stories.md` | M | **regenerated** |

   Load after the change (hours/week): SA 375h total (W2: 50, W3: 50), D1 202h (from 212h). The checks in `build.py` (dependency order, <= 4h/dev task, <= 8h/SA task, <= 30h/dev week) still pass.
4. **Redo / verification commands:** `python3 -I tools/plan/build.py` -> `OK: 74 stories, 317 tasks, 1070h`; open `docs/05-plan/by-person/SA.md` and see `[x] SHR-22`.
5. **Convention from now on:** when a task is done, add a line to `tools/plan/status.py` (with the commit) and re-run `build.py`, **in the same commit** as the work.
6. **Honest caveat:** ARC-06 and ARC-20 are only `partial` (OpenAPI has no body schemas; ICD-10 only 12/~300 codes). Other SA tasks already had deliverables earlier (IaC, workflows, `dev/`) **not yet cross-checked and recorded in status.py**; cross-checking is a separate step (D-recon) to avoid recording incorrectly.

## Part D2 - Shared .NET library: errors, JWT authentication, identity lookup (SHR-02, SHR-20, SHR-21) DONE / SHR-20 partial
1. **Goal:** every service returns errors in the same format, authenticates tokens the same way (Entra ID cloud, Keycloak local) and resolves `fhirUser` by `sub` with a 60-second cache (AC-08.1), without devs writing it themselves.
2. **Work done (in order):**
   1. Add 2 packages to `Directory.Packages.props`: `Microsoft.AspNetCore.Authentication.JwtBearer 10.0.14`, `Microsoft.AspNetCore.TestHost 10.0.14`.
   2. Create `libs/dotnet/Shp.Web` (references `Shp.Authorization`): `Errors/` (error catalog, exceptions, error writer, middleware) and `Auth/` (configuration, JWT, resolver, identity middleware).
   3. Create `libs/dotnet/Shp.Web.Tests` (19 tests) using TestServer + locally signed tokens (no Keycloak/Entra needed).
   4. Add 2 projects to `libs/dotnet/Shp.Libs.slnx`.
   5. Move SHR-02/20/21 to SA, record status, change the weekly load calculation (done tasks do not count hours).
3. **Changes (commit 55c56a5):**

| File | | Content |
|---|---|---|
| `Directory.Packages.props` | M | + JwtBearer, TestHost (10.0.14) |
| `libs/dotnet/Shp.Web/Errors/ErrorCatalog.cs` | A | 27 error codes: HTTP, FHIR issue type, vi/en messages; table cross-referenced with `07-api-conventions.md section 5` |
| `libs/dotnet/Shp.Web/Errors/ShpException.cs` | A | throw an error with a code; undeclared code -> `ArgumentException` immediately when created; optional `RetryAfter` |
| `libs/dotnet/Shp.Web/Errors/ErrorWriter.cs` | A | `/fhir/*` -> `OperationOutcome` (`application/fhir+json`); the rest -> Problem Details with `code`; `X-Request-Id` = trace id; `Retry-After` for 429/503; language per `Accept-Language` |
| `libs/dotnet/Shp.Web/Errors/ErrorHandlingMiddleware.cs` | A | `UseShpErrors()`; unknown error -> 500 `internal-error` **without leaking exception content** |
| `libs/dotnet/Shp.Web/Auth/ShpAuthOptions.cs` | A | `Auth:Authority`, `Auth:Audience` (or env `OIDC_AUTHORITY`, `OIDC_AUDIENCE`), `RequireHttpsMetadata`, `RequireMfaForStaff`, `ClockSkew` |
| `libs/dotnet/Shp.Web/Auth/AuthExtensions.cs` | A | `AddShpAuthentication`: verify signature, issuer, audience, expiry; do not rename claims; MFA (`amr` contains `mfa`) required for doctor/nurse/admin/dpo (-> 401); challenge/forbidden return the correct error format |
| `libs/dotnet/Shp.Web/Auth/Roles.cs` | A | 5 valid roles + staff group |
| `libs/dotnet/Shp.Web/Auth/IdentityResolver.cs` | A | `IIdentityLinkClient`, `IdentityResolver` (link cached 60s, "not linked" cached 5s), `HttpIdentityLinkClient` calls `GET /internal/v1/identity/{subject}` |
| `libs/dotnet/Shp.Web/Auth/IdentityMiddleware.cs` | A | `UseShpIdentity()`: missing valid role -> 403; not linked or locked -> 403 (except `[AllowUnlinkedIdentity]` endpoints for registration, but a locked account still gets 403); creates `Principal` for `AuthorizationEngine`; `HttpContext.GetShpPrincipal()` |
| `libs/dotnet/Shp.Web.Tests/*` | A | 19 tests |
| `libs/dotnet/Shp.Libs.slnx` | M | + Shp.Web, Shp.Web.Tests |
| `tools/plan/tasks_dev.py`, `tasks_sa.py`, `status.py`, `build.py` | M | SHR-02/20/21 -> SA; status; weekly load counts only unfinished tasks |
| `docs/05-plan/*`, `docs/02-requirements/user-stories.md` | M | **regenerated** |

4. **How devs use it** (in the service's `Program.cs`, in order):
```csharp
builder.Services.AddShpAuthentication(builder.Configuration);
builder.Services.AddShpIdentity<TClient>();           // clinical: DB-reading client; other services: HttpIdentityLinkClient
...
app.UseShpErrors(); app.UseRouting(); app.UseAuthentication(); app.UseShpIdentity(); app.UseAuthorization();
// in the endpoint: var principal = ctx.GetShpPrincipal(); engine.Decide(principal, "Appointment", "cancel", context);
// business error: throw new ShpException("slot-unavailable");
```
5. **Verification:**
```bash
dotnet test libs/dotnet/Shp.Libs.slnx -c Release         # expected: Shp.Web.Tests 19 passed; Shp.Authorization.Tests 236 passed
cd libs/dotnet && dotnet format --verify-no-changes       # no output
```
   **Mutation verification (done, all 5 caught by tests):** remove MFA check; remove audience check; allow unlinked accounts on every endpoint; ignore the `Disabled` flag; change cache TTL 60s -> 600s.
6. **Errors encountered and fixes:** `JsonSerializer.SerializeAsync` needs `JsonSerializerOptions.Default` added before `CancellationToken`; analyzer CA1018 requires `[AttributeUsage]`; xunit 2.9.3 uses `Task` (not `ValueTask`) for `IAsyncLifetime`; the first "remove MFA" mutation caused a compile error (`if (false)`) so it was invalid, redone with `hasMfa = true`.
7. **Known limitations (do not skip):**
   - **SHR-20 is only `partial`:** not yet run with real Keycloak/Entra. Needs testing with `make -C dev up` + `make -C dev token` (next task D-int).
   - The library is not yet wired into the 6 .NET services (the skeleton currently only uses `Shp.ServiceDefaults`); the wiring step needs Dockerfile changes to `COPY contracts/authz` and `libs/dotnet` sufficiently.
   - The `GET /internal/v1/identity/{subject}` endpoint on the clinical side is a dev task; the workload identity token for `HttpIdentityLinkClient` does not exist yet.
   - Plan load: after moving the completed tasks, D1 has only 11h in W2 and 10h in W3; the scheduler can pull work earlier if dependencies allow.

## Part D3 - Shared .NET library: CloudEvents, audit, endpoint-attached authorization filter (SHR-06, SHR-09, SHR-25, SHR-27)
1. **Goal:** (a) every event uses the same envelope and is validated against schemas in `contracts/events`; (b) every access to patient data, **including denied access**, produces an audit record that follows the rules (no PII, IP masked); (c) devs attach authorization to an endpoint with **one line**, and forgetting authorization turns tests red.
2. **Work done (in order):**
   1. Add `JsonSchema.Net 7.3.4` to `Directory.Packages.props` (package restore tried before use).
   2. Create `libs/dotnet/Shp.Events` (embeds all schemas in `contracts/events/**`, except example files): `CloudEvent`, `CloudEvents.Create`, `EventSchemas.Validate`, `Audit/` directory.
   3. Create `libs/dotnet/Shp.Events.Tests` (42 tests).
   4. Extend `Shp.Web` (additional references `Shp.Events`, `Shp.ServiceDefaults`): `Auth/AuthzEndpoint.cs`.
   5. Extend `Shp.Web.Tests` (29 tests, 10 added) and `Shp.Libs.slnx`.
   6. Move SHR-06/09/25/27 to SA, record status.
3. **Changes (commit c81d3ec):**

| File | | Content |
|---|---|---|
| `Directory.Packages.props` | M | + `JsonSchema.Net 7.3.4` |
| `libs/dotnet/Shp.Events/Shp.Events.csproj` | A | embed `contracts/events/**/*.json` (LogicalName `events/<ctx>/<event>.v1.json`) |
| `libs/dotnet/Shp.Events/CloudEvent.cs` | A | envelope; `CloudEvents.Create(service, type, subject, data)`: UUIDv7 id, `source=/services/<service>`, `dataschema` inferred from type, `traceparent` from Activity, 64 KB limit, drop null fields |
| `libs/dotnet/Shp.Events/EventSchemas.cs` | A | `Validate(evt)`: validate envelope + data (including `format` checks); unknown type is an error; `KnownTypes` |
| `libs/dotnet/Shp.Events/Audit/AuditRecord.cs` | A | E11 record, action/outcome constants; `ToCloudEvent()` |
| `libs/dotnet/Shp.Events/Audit/AuditRecordBuilder.cs` | A | mask IP (IPv4 `a.b.c.x`, IPv6 first 3 groups), truncate user-agent (120), `reason` <= 200, **`resource` only type/id/PARAMETER NAME** (search values rejected) |
| `libs/dotnet/Shp.Events/Audit/IAuditSink.cs` | A | interface + `InMemoryAuditSink` (production will be an outbox-writing sink, SHR-07) |
| `libs/dotnet/Shp.Events/Audit/AuthzAudit.cs` | A | `FromDecision`: map matrix action -> audit action (19 actions); outcome success/denied/error; representative role priority dpo>admin>doctor>nurse>patient |
| `libs/dotnet/Shp.Web/Auth/AuthzEndpoint.cs` | A | `AddShpAuthorization()`; `RequireShpAuthz(resource, action, contextFactory?, denyAsNotFound?)`; `GetShpDecision()` (to apply `Fields`/`Hide`); `FindUnprotectedEndpoints()`; `[ShpPublicEndpoint]` |
| `libs/dotnet/Shp.Web/Shp.Web.csproj` | M | + references Shp.Events, Shp.ServiceDefaults |
| `libs/dotnet/Shp.Events.Tests/*`, `libs/dotnet/Shp.Web.Tests/*`, `libs/dotnet/Shp.Libs.slnx` | A/M | new tests |
| `tools/plan/*`, `docs/05-plan/*`, `docs/02-requirements/user-stories.md` | M | SHR-06/09/25/27 -> SA; status; regenerate |

4. **Filter behavior (so devs know they do not have to build it):** deny -> write audit `denied` + reason -> 403 `forbidden` / `no-care-relationship` (or 404 if `denyAsNotFound`); allow -> run the handler then write audit `success` (including when the handler throws a business error 409/422); unexpected error -> audit `error` then 500 without leaking details; not logged in -> 401, no audit.
5. **Dev usage:**
```csharp
builder.Services.AddShpAuthorization();                       // + AddSingleton<IAuditSink, ...>() chosen by the service
app.MapPost("/fhir/Appointment/{id}/$cancel", Cancel).RequireShpAuthz("Appointment", "cancel", LoadAppointmentFacts);
// service test:  Assert.Empty(factory.Services.FindUnprotectedEndpoints());
// producer contract test: Assert.Empty(EventSchemas.Validate(CloudEvents.Create("scheduling", "shp.scheduling.appointment-booked.v1", subject, payload)));
```
6. **Verification:**
```bash
dotnet test libs/dotnet/Shp.Libs.slnx -c Release     # Shp.Authorization 236, Shp.Events 42, Shp.Web 29: all passed
cd libs/dotnet && dotnet format --verify-no-changes    # no output
```
   **Mutations (done, all 5 caught):** remove audit on deny; always return `forbidden` (losing the `no-care-relationship` code); put query values into the audit `resource`; disable schema `format` checks; disable IP masking. One first mutation had a compile error so it was invalid, redone.
7. **Errors encountered and fixes:** (a) **plan broken after moving tasks** - `build.py` reported `MLO-04 (W8) scheduled before dependency ML-23 (W10)`: SA tasks were fixed by sprint so they did not shift with dev tasks that were scheduled later; at that point I had already committed (`c81d3ec`, `ff09115`) without checking the result, so the generated files in `docs/05-plan` of those two commits were **not updated**; fixed in step D3b right below. Lesson: run `python3 -I tools/plan/build.py` and stop if it prints `validation error` **before** committing. (b) `TheoryData` cannot list directly -> extract a function returning an array of paths; the embedded resource LogicalName uses `%(RecursiveDir)` so normalize `\` to `/` for Windows.
8. **Known limitations:**
   - SHR-09 `partial`: no outbox-writing sink yet (SHR-07, next step) so audit is not durably stored; no separate audit middleware for FHIR reads/searches outside `RequireShpAuthz`.
   - IP is taken from `RemoteIpAddress`: behind the gateway requires configuring `ForwardedHeaders` (part of wiring into the services).
   - The library is not yet wired into the 6 .NET services.

## Part D3b - Fix the plan scheduler: SA tasks must also respect dependencies DONE
1. **Goal:** `build.py` re-runs without errors after many tasks were moved between dev and SA.
2. **Cause:** `schedule()` fixed the week of every SA task by sprint, and only re-scheduled dev tasks. When dev tasks slipped (because D1/D2/D3 got lighter/heavier), SA tasks depending on them (MLO-04 depends on ML-23) landed in the week before their dependency.
3. **Changes (commit 483f2b5):**

| File | | Content |
|---|---|---|
| `tools/plan/build.py` | M | `schedule()`: every task goes into the same scheduling loop; an SA task starts in its sprint week or when dependencies allow (whichever is later), **no hour cap**; SA may go to W12 (defense week), dev to W11; error messages state the correct limit |
| `docs/05-plan/*`, `docs/02-requirements/user-stories.md` | M | **regenerated** (including the part missed in D3) |

4. **Verification:** `python3 -I tools/plan/build.py` -> `OK: 74 stories, 317 tasks, 1070h`. Remaining load: SA 364h (W4 = 52h, above the 45h warning threshold, printed in bold), D1 190h, D2 225h, D3 258h.
5. **Consequence to note:** D1 has only 11h in W2 and 4h in W3 (many D1 foundation tasks were already done by the SA); the dev schedule really starts at W4. D1 could take extra work earlier or be kept as backup (PM decision).

## Part D4 - Shared .NET library for PostgreSQL: outbox, inbox, Idempotency-Key (SHR-07/08/28) - partial
1. **Goal:** events are always written **in the same transaction** as the data change and are never lost when the bus fails; consumers process **exactly once** even if the bus delivers multiple times; resending the same `Idempotency-Key` does not create duplicates. The PostgreSQL version is used for scheduling (per proposed ADR-0014, the only service still using PostgreSQL).
2. **Work done (in order):**
   1. Try package restore before use: `Npgsql 10.0.3`, `Testcontainers.PostgreSql 4.16.0` (version 4.6.0 pulls `SSH.NET` with vulnerability warning NU1903 and would make the build red because warnings = errors, so 4.16.0 was chosen, confirmed warning-free).
   2. Create `libs/dotnet/Shp.Data.Postgres`: embedded DDL, `PostgresOutbox`, `OutboxRelay`, `PostgresInbox`, `PostgresIdempotency`, DI registration.
   3. Create `libs/dotnet/Shp.Data.Postgres.Tests` running on **real PostgreSQL 16** with Testcontainers (25 tests).
   4. Add 2 projects to `Shp.Libs.slnx`; move SHR-07/08/28 to SA and record status.
3. **Changes (commit b74e54a):**

| File | | Content |
|---|---|---|
| `Directory.Packages.props` | M | + `Npgsql 10.0.3`, `Testcontainers.PostgreSql 4.16.0` |
| `libs/dotnet/Shp.Data.Postgres/Sql/messaging.sql` | A | DDL `outbox`, `inbox`, `idempotency_key` (matches `02-db-scheduling.md`, re-runnable) |
| `.../MessagingSchema.cs` | A | read the embedded DDL; `ApplyAsync` |
| `.../PostgresOutbox.cs` | A | `AddAsync(tx, evt, validate)` writes to the outbox in the caller's transaction; `validate:true` checks the schema |
| `.../OutboxRelay.cs` | A | `IEventPublisher`; `OutboxRelay` (BackgroundService): lock up to 100 rows `FOR UPDATE SKIP LOCKED`, publish in order, mark `published_at`; bus failure -> record `attempts`/`last_error` (do not record payload), **stop the batch to preserve order**, backoff up to 30 seconds; metrics `shp_outbox_published_total`, `shp_outbox_publish_failures_total` |
| `.../PostgresInbox.cs` | A | `TryBeginAsync`, `HandleOnceAsync(consumer, evt, handler)`: record the inbox marker + run the handler + commit in one transaction; handler failure -> roll back the marker too |
| `.../PostgresIdempotency.cs` | A | `RequireKey` (missing -> 428, invalid -> 400), `HashBody` (canonical JSON: sort properties, remove whitespace), `ClaimAsync` (see below) |
| `.../DependencyInjection.cs` | A | `AddShpOutboxRelay()` |
| `libs/dotnet/Shp.Data.Postgres.Tests/*` | A | Testcontainers fixture + 25 tests |
| `libs/dotnet/Shp.Libs.slnx`, `tools/plan/*`, `docs/05-plan/*`, `docs/02-requirements/user-stories.md` | M | new projects; SHR-07/08/28 -> SA; regenerate the plan |

4. **`ClaimAsync` behavior (so devs know they do not have to build it):** first time -> insert a row, allocate `ResourceId` (UUIDv7); same key + same operation + same body -> `IsReplay=true` with the same `ResourceId` (service reloads the previous result, does not process again); different body/operation -> 422 `idempotency-key-reused`; 20 concurrent requests with the same key -> exactly 1 new request; claim rollback -> claim can be retaken; past 24 hours -> treated as new; keys are partitioned by caller.
5. **Verification:**
```bash
# requires Docker. If Docker Hub rate-limits pulls: export SHP_TEST_POSTGRES_IMAGE=mirror.gcr.io/library/postgres:16-alpine
dotnet test libs/dotnet/Shp.Libs.slnx -c Release      # Shp.Data.Postgres.Tests: 25 passed (along with 236 + 42 + 29 of the other libraries)
cd libs/dotnet && dotnet format --verify-no-changes
```
   **Mutations (done, all 3 caught):** remove 422 when the body differs; remove `SKIP LOCKED` (4 replicas publish duplicates); inbox overwrites instead of skipping.
   Scenarios checked on a real DB: rollback preserves both the data and the outbox; time ordering; the bus dying mid-way and coming back still publishes everything in the right order (1 publish, 1 failure with `attempts=1`, 1 untouched); 4 relays x 60 events -> each event exactly once; 20 duplicate deliveries -> the handler runs exactly once.
6. **Errors encountered and fixes:** analyzer CA1711 forbids class names ending in `Collection` -> rename `PostgresTestGroup`; after **the work session restarted**, the Docker daemon was no longer running and left a stale pid file -> delete `/var/run/docker.pid` then restart `dockerd`, re-run all tests; Testcontainers needs `TESTCONTAINERS_RYUK_DISABLED=true` in this build environment (cannot pull the Ryuk image).
7. **Known limitations:** SHR-07 `partial` (no MongoDB version yet, no real `IEventPublisher`); SHR-08 `partial` (no MassTransit RabbitMQ/Service Bus, no retry/DLQ, no MongoDB version). Ordering across multiple relay replicas is only guaranteed within a batch; consumers must skip events with an older `version` (noted in 13-events section 1). **If ADR-0014 is approved, the MongoDB version becomes the most used one (5 services).**

## Part D4b - Proposed ADR-0014: database review per service (NOT applied yet, pending approval)
1. **Goal:** answer the PM's comment "always PostgreSQL, optimize the DB" with real load figures, without changing engines based on feeling.
2. **Work done:** read `workload-analysis.md` and ADR-0008; build a table of actual volumes (notification ~2,000 reminders/day, ml ~900 predictions/day and ~330k facts/year); evaluate each service against alternative options; write the ADR; count affected files.
3. **Changes (commit b74e54a):**

| File | | Content |
|---|---|---|
| `docs/03-architecture/adr/0014-database-per-service-review.md` | A | **Proposed**: scheduling keeps PostgreSQL (DB constraint against duplicate booking, tested with 50 concurrent bookings); notification -> MongoDB (document + atomic work pickup `findOneAndUpdate` + TTL); ml -> MongoDB (feature state + prediction log) + Parquet on Blob (immutable training set); optimization table per engine and how to measure; risks (time-series collection on Cosmos vCore not tested) |
| `docs/03-architecture/adr/README.md` | M | add line 0014 (Proposed) |

4. **Deliberately NOT done:** do not modify `04-db-notification-ml.md`, DDL, local chart, NTF/ML/IAC tasks until the PM approves (avoid rewriting the design based on an unapproved proposal).
5. **Decisions needed from the PM:** approve/modify ADR-0014, especially (a) notification -> MongoDB or Redis; (b) ml -> MongoDB + Parquet; (c) accept checking time-series collection availability on Cosmos DB vCore before finalizing.

## Part J - Move the entire code base to Java (PM decision on 2026-10-28)
> PM: "move the entire code base to Java... I approve every design you propose". Order of work: J1 decision + documents -> J2 Maven + first foundation library -> J3 -> J4 (port the .NET libraries already written to Java, keeping behavior and tests) -> J5 (8 services, CI, Docker, local toolkit; delete .NET/Python).
> Versions checked directly on Maven Central on 2026-10-28 (not chosen from memory): Spring Boot **4.1.2**, Spring AI **2.0.2**, HAPI FHIR **8.12.1**, Testcontainers **2.0.5**, networknt json-schema-validator **3.0.8**, CloudEvents SDK **5.1.0**, Flyway **13.11.1**, JaCoCo **0.8.16**, ArchUnit **1.5.1**, XGBoost4J **3.4.0**, DuckDB JDBC **1.5.6.0**, MLflow client **3.11.1**, Tess4J **5.20.0**, PostgreSQL JDBC **42.7.14**, MongoDB driver **5.14.0**. JDK in the build environment: **21.0.12**, Maven **3.9.11**.

### J1 - Java decision, approve ADR-0014 and update documents/plan DONE
1. **Goal:** record the decision as an ADR, apply ADR-0014 (approved by the PM) to the detailed design, and change every task/document still naming .NET/Python to Java before writing code.
2. **Work done (in order):**
   1. Write ADR-0015 (technology-by-technology mapping from .NET/Python -> Java); mark ADR-0002, ADR-0012 **Superseded**; ADR-0014 -> **Accepted**; strike the notification/ml lines in ADR-0008.
   2. Fix architecture documents still mentioning .NET/Python/Firely/MassTransit/YARP/LightGBM/pandera (`overview.md`, ADR-0001/0003/0004/0007/0009, `13-events.md`, `16-mlops.md`, `04-devops/README.md`).
   3. **Rewrite `04-db-notification-ml.md` to MongoDB** (ADR-0014): collections, sample documents, indexes (unique anti-duplicate, partial for work pickup, TTL), work pickup operation with `findOneAndUpdate`; ml = `patient_stats` + `appointment_facts` + `predictions` (time-series) + Parquet/DuckDB for training.
   4. Write initialization script `contracts/db/mongo-notification-ml.js` and test suite `contracts/db/mongo-notification-ml.test.js`; **run on real MongoDB 8** (replica set): 12/12 checks pass, running the script twice is error-free.
   5. Fix the plan: .NET/Python tasks -> Java; tasks PY-01/03/04/05 reduced to "wire up the shared Java library" (since the shared Java library already does that part); NTF-01/05 and ML-11 -> MongoDB; person labels D1/D2/D3 become Java Dev.
3. **Changes (commit 3b7390f):**

| File | | Content |
|---|---|---|
| `docs/03-architecture/adr/0015-java-for-all-services.md` | A | Java 21 LTS + Spring Boot 4.1; 14-item mapping table; consequences (one CI pipeline, 1-1 library port, SA tools keep Python/Bash) |
| `docs/03-architecture/adr/0002-...`, `0012-...` | M | `Accepted` -> `Superseded (2026-10-28) by ADR-0015` |
| `docs/03-architecture/adr/0014-...` | M | `Proposed` -> `Accepted (2026-10-28)` |
| `docs/03-architecture/adr/0008-...` | M | notification, ml lines struck, point to ADR-0014 |
| `docs/03-architecture/adr/README.md` | M | status of 0002/0012/0014; add 0015 |
| `docs/03-architecture/overview.md` | M | technology column for 8 services -> Java (gateway: Spring Cloud Gateway, ai: Spring AI, ml: XGBoost4J + DuckDB); notification/ml DB -> MongoDB; bus: Spring Cloud Stream |
| `docs/03-architecture/adr/0001,0003,0004,0007,0009`, `detailed-design/13-events.md`, `16-mlops.md`, `docs/04-devops/README.md` | M | Firely -> HAPI FHIR; MassTransit -> Spring Cloud Stream; LightGBM -> XGBoost4J; pandera -> Java rules; ".NET CI, Python CI" -> "Java CI (Maven)" |
| `docs/03-architecture/detailed-design/04-db-notification-ml.md` | M | **fully rewritten**: PostgreSQL DDL -> MongoDB collections (see step 2.3) |
| `contracts/db/mongo-notification-ml.js`, `contracts/db/mongo-notification-ml.test.js` | A | idempotent initialization script + 12 checks |
| `tools/plan/tasks_dev.py` | M | SHR-01 (HAPI), PY-01 (3h->1h), PY-03 (4h->2h), PY-04, PY-05 (2h->1h), PY-07 (Spring AI), ML-04 (XGBoost4J), ML-14 (Java rules), ML-11/NTF-01/NTF-05 (MongoDB) |
| `tools/plan/tasks_sa.py` | M | GOV-06 (maven), CIT-01 (Java CI), CIT-02 (CI for contracts + SA tools), CIT-07 (CodeQL java), LOC-03/04, CIT-10 (Maven packages), GW-01 (Spring Cloud Gateway), SCF-06/07, SHR-25, SHR-08 (Spring Cloud Stream) |
| `tools/plan/stories.py` | M | AC "LightGBM" -> "XGBoost" |
| `tools/plan/build.py` | M | SHR/PY epic labels and D1-D3 person labels |
| `docs/05-plan/*`, `docs/02-requirements/user-stories.md` | M | **regenerated** - total 1,065 hours (5 hours less because PY-xx shrank) |

4. **Redo / verification commands:**
```bash
python3 -I tools/plan/build.py                       # OK: 74 stories, 317 tasks, 1065h
docker run -d --name mtest mongo:8 --replSet rs0 && docker exec mtest mongosh --eval 'rs.initiate()'
docker cp contracts/db/mongo-notification-ml.js mtest:/i.js && docker cp contracts/db/mongo-notification-ml.test.js mtest:/t.js
docker exec mtest mongosh --quiet /i.js && docker exec mtest mongosh --quiet /t.js   # ALL PASSED
```
5. **Errors encountered and fixes:** the Docker daemon was off again (after the session restarted) -> delete the stale pid, restart it; the Mongo image was pulled from `mirror.gcr.io` (Docker Hub rate limits pulls). The original wording in the document said "2 parallel workers" while the mongosh verification is **sequential** -> corrected; the real parallel test will be in the Java library.
6. **Limitations:** time-series collections and transactions on **Cosmos DB for MongoDB vCore** not yet tested (can only be tested when UAT is provisioned). The SHR-xx statuses in `status.py` still point to the .NET version; they will be updated to the Java commits in J2-J4. .NET/Python code remains in the repo until J5.

### J2 - Root Maven + `shp-service-defaults` + `shp-authz` (replace `Shp.ServiceDefaults`, `Shp.Authorization`) DONE
1. **Goal:** have a common Java build skeleton for every module and port the first two foundation libraries, **keeping the behavior and test suite** of the .NET version.
2. **Work done (in order):**
   1. Test that Maven can download through the proxy with `spring-boot-starter-parent 4.1.2`; read the versions managed by Boot (Testcontainers 2.0.5, SnakeYAML 2.6, Jackson 3.1.7, OpenTelemetry 1.62.0, Flyway 12.4.0).
   2. Write the root `pom.xml`: parent Spring Boot 4.1.2, Java 21, `-Xlint:all -Werror` (warnings = errors), Spotless (google-java-format) running `check` in the `verify` phase, JaCoCo line coverage threshold **70%** per module; `.mvn/jvm.config` opens `--add-exports` for google-java-format on JDK 21.
   3. `libs/java/shp-authz`: `AuthzMatrix` (SnakeYAML SafeConstructor + all invariants like the .NET/Python version), `AuthorizationEngine` (uses `java.time.Clock`), `Principal`, `ResourceContext` (with builder), `AuthzDecision`, `Predicates`; `matrix.yaml` is **copied into the jar** at build time (`shp/authz/matrix.yaml`).
   4. Move the matrix-generated tests to JUnit 5 (`@ParameterizedTest` + `@MethodSource`): **238 tests** (.NET version 236; 2 more: `role-not-allowed` reason, loader reporting duplicate id/denyAll/missing rule).
   5. `libs/java/shp-service-defaults`: auto-configuration loading `shp-defaults.properties` (lowest precedence): `/health/live`, `/health/ready`, `/health`, `/info` (name, version, gitSha), `/prometheus`; tracing 100%, export OTLP only when `SHP_OTLP_EXPORT=true`; virtual threads; graceful shutdown. 5 tests run a real server on a random port.
3. **Changes (commit 95d2f74):**

| File | | Content |
|---|---|---|
| `pom.xml` | A | parent + modules + BOM (Spring AI 2.0.2, HAPI 8.12.1, networknt 3.0.8, CloudEvents 5.1.0, ArchUnit 1.5.1) + compiler/Spotless/JaCoCo |
| `.mvn/jvm.config` | A | `--add-exports jdk.compiler/...` (6 lines) for Spotless |
| `.gitignore` | M | + `target/` |
| `libs/java/shp-authz/pom.xml` | A | SnakeYAML; resource `../../../contracts/authz/matrix.yaml` -> `shp/authz/` |
| `libs/java/shp-authz/src/main/java/org/shp/authz/*.java` | A | 8 classes (see step 2.3) |
| `libs/java/shp-authz/src/test/java/org/shp/authz/MatrixDrivenTest.java` | A | 238 tests |
| `libs/java/shp-service-defaults/pom.xml` | A | actuator, opentelemetry starter, micrometer prometheus, jackson-annotations |
| `.../src/main/resources/shp-defaults.properties` | A | probe paths match the Helm chart; `validate-group-membership=false` |
| `.../ServiceDefaultsAutoConfiguration.java`, `ServiceInfo.java`, `META-INF/spring/...AutoConfiguration.imports` | A | auto-config + `InfoContributor` |
| `.../src/test/java/.../ServiceDefaultsTest.java` | A | 5 tests |

4. **Redo / verification commands:**
```bash
mvn -B verify                                   # BUILD SUCCESS: shp-service-defaults 5 tests, shp-authz 238 tests, coverage >= 70%
mvn -B spotless:apply                           # auto-format before committing (CI runs spotless:check)
```
   **Mutations (done, all 3 caught):** `own` always true -> 10 tests red; cancel window 2h -> 1h -> 2 tests red; `not-care` always true -> 1 test red. (The first sed for the 2h mutation did not match because Spotless had reflowed the line; checked with `grep -c` then redid it.)
5. **Errors encountered and fixes:**
   - javac warned `unknown enum constant JsonInclude.Include.NON_EMPTY` (actuator types reference a Jackson annotation not on the classpath) -> add `jackson-annotations`.
   - Context failed to start: `Health contributor 'db' ... does not exist` -> `management.endpoint.health.validate-group-membership=false` so the `ready` group automatically includes whichever DB a service has.
6. **Differences from the .NET version:** the metric path is `/prometheus` (Micrometer) instead of the OTel metrics exporter; OTLP is enabled with `SHP_OTLP_EXPORT` (Helm will set it in UAT/prod).

### J3 - `shp-events` + `shp-web` (replace `Shp.Events`, `Shp.Web`) DONE
1. **Goal:** port the CloudEvents envelope, schema validation, audit, standard errors, JWT authentication, identity lookup and endpoint-attached authorization to Java/Spring, keeping the behavior verified in the .NET version.
2. **Work done (in order):**
   1. Read the POM of `json-schema-validator` 3.0.8 -> uses **Jackson 3** (`tools.jackson`), matching Boot 4; read the real API with `javap` (`SchemaRegistry`, `Schema.validate(node, ctx -> ctx.executionConfig(c -> c.formatAssertionsEnabled(true)))`).
   2. **ADR-0015 adjustment:** do not use the CloudEvents Java SDK (its JSON module uses Jackson 2) -> the envelope is a Java record.
   3. `libs/java/shp-events`: `CloudEvent`, `CloudEvents.create` (hand-written UUIDv7 `Uuids.v7`, `traceparent` from OpenTelemetry `Span.current()`, <= 64 KB, drop null fields), `EventSchemas.validate` (schemas embedded in the jar from `contracts/events`, excluding examples), `audit/` (`AuditRecord`, `AuditRecordBuilder` - masks IP **without DNS lookup**, `AuditSink`, `InMemoryAuditSink`, `AuthzAudit`). 47 tests.
   4. `libs/java/shp-web` (auto-configuration):
      - `errors/`: `ErrorCatalog` (27 codes), `ShpException`, `ErrorWriter` (FHIR -> OperationOutcome, the rest -> Problem Details), `ShpExceptionHandler` (`@RestControllerAdvice`: ShpException, 400 bad input, 404 unknown route, 500 without leaking details).
      - `auth/`: `ShpAuthProperties` (`shp.auth.*`, defaults from `OIDC_AUTHORITY`/`OIDC_AUDIENCE`), `ShpJwt` (validator issuer + audience + expiry with clock skew + MFA for staff; map `roles` to authorities), `IdentityResolver` (60s/5s cache), `HttpIdentityLinkClient` (`RestClient`), `IdentityInterceptor`, `@AllowUnlinkedIdentity`.
      - `authz/`: `@RequireShpAuthz(resource, action, context, denyAsNotFound)`, `ResourceContextResolver` (bean), `AuthzInterceptor` (deny -> audit `denied` -> 403/404; allow -> audit `success` after processing, `error` if 5xx), `@ShpPublicEndpoint`, `UnprotectedEndpoints.find(ctx)`.
      - `ShpWebAutoConfiguration`: `SecurityFilterChain` (stateless, public `/health/**`, `/info`, `/prometheus`), **lazy** `JwtDecoder` (`SupplierJwtDecoder`: the service can start when the IdP is not ready), authorization engine, two interceptors in the order identity -> authorization.
      31 tests (25 integration tests running a real server + locally signed HMAC tokens; 6 unit tests).
3. **Changes (commit 5e423ab):**

| File | | Content |
|---|---|---|
| `pom.xml` | M | + modules `shp-events`, `shp-web`; + `shp-events` in dependencyManagement |
| `libs/java/shp-events/**` | A | 7 main classes + `EventTest` (22) + `AuditTest` (25) |
| `libs/java/shp-web/**` | A | 16 main classes, `shp-web-defaults.properties`, `AutoConfiguration.imports`; `TestApp`, `WebTest` (25), `UnitTest` (6) |
| `docs/03-architecture/adr/0015-java-for-all-services.md` | M | "Event" row: Java record instead of the CloudEvents SDK (Jackson 2/3 reason) |

4. **How devs use it (replaces the .NET guide in D2/D3):**
```java
@GetMapping("/fhir/Appointment/{id}")
@RequireShpAuthz(resource = "Appointment", action = "read", context = "appointmentFacts")
Appointment read(@PathVariable String id, HttpServletRequest req) { ... }      // throw new ShpException("not-found") when absent
@Bean ResourceContextResolver appointmentFacts(...) { return (req, vars, principal) -> ResourceContext.builder().patientId(...).build(); }
// service test: assertThat(UnprotectedEndpoints.find(applicationContext)).isEmpty();
// contract test: assertThat(EventSchemas.validate(CloudEvents.create("scheduling", "shp.scheduling.appointment-booked.v1", subject, payload))).isEmpty();
```
   A service only has to provide the `IdentityLinkClient` bean (clinical: reads the DB; others: `HttpIdentityLinkClient`) and `AuditSink`.
5. **Verification:**
```bash
mvn -B verify      # shp-service-defaults 5, shp-authz 238, shp-events 47, shp-web 31 - BUILD SUCCESS, coverage >= 70%
```
   **Mutations (done, all caught):** shp-events - disable `format` checks (1 red), disable IP masking (3), remove blocking of values in `resource` (5). shp-web - remove MFA (2), remove audience (1), allow unlinked accounts (1), ignore the locked flag (1), no audit on deny (3), always `forbidden` (1), TTL 600s (1), do not record `error` on 5xx (1).
6. **Errors encountered and fixes:**
   - **The first shp-events mutation run gave an empty result - invalid:** running `mvn -pl libs/java/shp-events` without `-am` meant Maven could not find `shp-authz` (not installed in `~/.m2`) and stopped before testing. Redone with `-am`. **Lesson:** always have a green "baseline" line before trusting mutation results; use `-am` (or `mvn install`) for modules with internal dependencies.
   - Mutation by `sed` did not match when Spotless had reflowed the line -> use a Python script with `assert` to report "NO MATCH" instead of staying silent.
   - javac `-Werror`: try-with-resources with an unused variable -> change to `try/finally`.
   - `MockRestServiceServer` does not allow adding expectations after a call -> one server per scenario.
7. **Differences from the .NET version:** a "deliberately public" endpoint still **requires login** (the default security chain blocks every path except health/info/prometheus); `@ShpPublicEndpoint` only means "no matrix rule needed". 405 returns `validation-failed` (400) because the error table in section 5 has no dedicated code.

### J4 - Data libraries: `shp-data-postgres` (ported from .NET) + `shp-data-mongo` (new, per ADR-0014) DONE / partial
1. **Goal:** events are always written in the same transaction as the data and are not lost when the bus fails; consumers process exactly once; Idempotency-Key does not create duplicates - for **both PostgreSQL (scheduling) and MongoDB (clinical, document, ai, notification, ml)**.
2. **Work done (in order):**
   1. Add `EventPublisher` (common interface) to `shp-events`; add `IdempotencyKeys` (header validation + canonical body hashing, DB-independent) to `shp-web`.
   2. Check the Testcontainers 2.x module names in the BOM: `testcontainers-postgresql`, `testcontainers-mongodb` (new Java packages `org.testcontainers.postgresql` / `org.testcontainers.mongodb`).
   3. `libs/java/shp-data-postgres`: `PostgresOutbox` (JdbcClient, mandatory inside a transaction), `OutboxRelay` (`SmartLifecycle`, virtual thread, `FOR UPDATE SKIP LOCKED`, backoff <= 30s), `PostgresInbox`, `PostgresIdempotency`, `IdempotencyClaim`; DDL becomes a **Flyway migration** `db/shp-messaging/postgres/V0_1__shp_messaging.sql` (services add it to `spring.flyway.locations`). 28 tests on real PostgreSQL 16.
   4. `libs/java/shp-data-mongo`: `MongoOutbox`, `MongoOutboxRelay` (MongoDB has no `SKIP LOCKED` -> **claim each event with `findAndModify`** setting `lockedUntil`/`lockedBy`, 30s lock; if a replica dies the lock expires and the event is reclaimed), `MongoInbox` (`_id = consumer|messageId`), `MongoIdempotency` (`_id = subject|key`), `MongoMessaging.inTransaction` (**retry up to 8 times** when MongoDB reports `TransientTransactionError`/WriteConflict/DuplicateKey - a mandatory requirement of multi-document transactions), `ensureIndexes` (partial outbox index, inbox TTL 14 days, idempotency TTL 24 hours). 21 tests on a real MongoDB 8 replica set.
3. **Changes (commit 3764d57):**

| File | | Content |
|---|---|---|
| `pom.xml` | M | + modules/dependencyManagement `shp-web`, `shp-data-postgres`, `shp-data-mongo` |
| `libs/java/shp-events/.../EventPublisher.java` | A | `void publish(CloudEvent)` |
| `libs/java/shp-web/.../idempotency/IdempotencyKeys.java` | A | `require(header)` (428/400), `hashBody(bytes)` |
| `libs/java/shp-data-postgres/**` | A | 5 classes + Flyway migration + 3 test classes (28 tests) |
| `libs/java/shp-data-mongo/**` | A | 5 classes + 3 test classes (21 tests) |

4. **How devs use it:**
```java
// PostgreSQL (scheduling) - inside @Transactional:
var claim = idempotency.claim(principal.subject(), IdempotencyKeys.require(req.getHeader("Idempotency-Key")), "Appointment/$book", IdempotencyKeys.hashBody(body));
if (claim.replay()) return loadAppointment(claim.resourceId());
... write appointment ...; outbox.add(CloudEvents.create("scheduling", "shp.scheduling.appointment-booked.v1", "Appointment/" + id, payload));
// MongoDB: wrap everything in MongoMessaging.inTransaction(tx, () -> { ... idempotency.claim(...); ... outbox.add(...); })
// Consumer: inbox.handleOnce("notification", event, () -> { ... });
```
5. **Verification:**
```bash
# requires Docker; if Docker Hub rate-limits pulls use the mirror:
export SHP_TEST_POSTGRES_IMAGE=mirror.gcr.io/library/postgres:16-alpine SHP_TEST_MONGO_IMAGE=mirror.gcr.io/library/mongo:8
mvn -B verify      # 7 modules, 370 tests: 5 + 238 + 47 + 31 + 28 + 21 - BUILD SUCCESS
```
   **Mutations (all caught):** PostgreSQL - remove 422 (1 red), remove `SKIP LOCKED` (1), inbox overwrite (2). MongoDB - remove the relay lock condition (2), non-unique inbox lock (2), remove 422 (1), remove the 24h expiry (1).
   Scenarios checked on real DBs (both engines): rollback preserves both data and outbox; ordering; the bus dying mid-way and coming back still publishes everything in the right order; 4 relays x 60 events -> each event exactly once; 20 duplicate deliveries -> exactly 1 commit; 20 concurrent requests with the same key -> exactly 1 new claim; claim rollback -> claim can be retaken; key expires after 24h. For MongoDB specifically: when a dead replica's lock expires, the event is published.
6. **Errors encountered and fixes:** Testcontainers requires declaring the mirror image as a substitute (`asCompatibleSubstituteFor("postgres"|"mongo")`); **Testcontainers 2.x does not enable the replica set automatically** for MongoDB like 1.x -> `.withReplicaSet()` (without it: "Transaction numbers are only allowed on a replica set member"); `ExecutorService.submit(this::claim)` was ambiguous -> lambda; one mutation did not match the string because Spotless reflowed it -> use regex.
7. **Known limitations:** SHR-07/08 still `partial`: **no real `EventPublisher` yet** (Spring Cloud Stream + RabbitMQ/Service Bus) and no consumer-side retry/DLQ - step J5b. SHR-10 (FHIR resource store on MongoDB: version CAS + history) not done. With MongoDB, the handler inside `inbox.handleOnce` **may run again** when a transaction conflicts (only one commit succeeds) -> the handler must not call external services with side effects (sending SMS...) directly; it must write work to the DB and let a worker do it later (this is the notification design).

### J4b - PM addition: **ai and ml keep Python**; split out `shp-security`; audit durably stored via outbox DONE
1. **Goal:** PM addition (2026-10-28): *"whatever is required to use Python such as AI still keeps it"* -> fix the scope of ADR-0015 and everything J1 changed for ai/ml; also prepare for the gateway (WebFlux) and for durable audit storage.
2. **Work done (in order):**
   1. **ADR-0015** renamed and scoped: Java for `gateway, scheduling, notification, clinical, document, audit`; **Python kept for `ai`, `ml`** (reason: LightGBM, pandas, pandera, MLflow, eval tools have no equivalent Java version). The mapping table says "Keep Python" for AI, ML, OCR; consequences: two CI pipelines (`java-ci`, `python-ci`), ai/ml use **the same contracts** in `contracts/`.
   2. **Revert** what J1 changed to Java for ai/ml (take the exact text from the commit before J1 with `git show 3b7390f^:...`, without retyping): tasks PY-01, PY-03, PY-04, PY-05 (hours need their own Python library again), ML-04 (LightGBM), ML-14 (pandera), CIT-02 (Python CI), LOC-04 (Python skeleton), SCF-06 (FastAPI); SCF-07 -> "Python code skeleton... connecting to **MongoDB**" (keep ADR-0014); PY-07 drop "Spring AI"; the story AC "LightGBM"; D3 label "Dev Python/AI". The plan total returns to **1,070 hours**.
   3. Documents: `overview.md` (ai: Python FastAPI, ml: Python LightGBM/MLflow), ADR-0007 (LightGBM), `16-mlops.md` (pandera, LightGBM), ADR-0009 (Java CI + Python CI), `04-devops/README.md`, `04-db-notification-ml.md` (notification Java, ml Python/pymongo, drift with pandas/DuckDB Python).
   4. Remove the Spring AI BOM from the root `pom.xml` (no Java module uses it anymore).
   5. **Split out `libs/java/shp-security`** (`ShpJwt`, `ShpAuthProperties`, `Roles`; package `org.shp.security`) from `shp-web`: the gateway is WebFlux and must not pull Spring MVC from `shp-web`; both share the same JWT rules. Use `git mv` to preserve file history.
   6. Add `PostgresOutboxAuditSink`, `MongoOutboxAuditSink`: write audit as event E11 into the outbox in a **separate transaction (REQUIRES_NEW)** -> audit is still stored when the business transaction rolls back, and is not lost when the bus fails. One test each.
3. **Changes (commit 638b780):**

| File | | Content |
|---|---|---|
| `docs/03-architecture/adr/0015-java-for-all-services.md` | M | title, scope, 9 table rows, considered options, consequences (see step 2.1) |
| `docs/03-architecture/adr/README.md`, `overview.md`, `adr/0007`, `adr/0009`, `detailed-design/16-mlops.md`, `detailed-design/04-db-notification-ml.md`, `docs/04-devops/README.md` | M | ai/ml back to Python |
| `tools/plan/tasks_dev.py`, `tasks_sa.py`, `stories.py`, `build.py` | M | revert ai/ml tasks (step 2.2) |
| `docs/05-plan/*`, `docs/02-requirements/user-stories.md` | M | regenerated (1,070 hours) |
| `pom.xml` | M | - Spring AI BOM; + module `shp-security` |
| `libs/java/shp-security/**` | A (git mv) | 3 classes moved from `shp-web/auth`, package `org.shp.security` |
| `libs/java/shp-web/pom.xml`, `IdentityInterceptor.java`, `ShpWebAutoConfiguration.java`, tests | M | import `org.shp.security.*` |
| `libs/java/shp-data-postgres/.../PostgresOutboxAuditSink.java` + `AuditSinkTest.java` | A | |
| `libs/java/shp-data-mongo/.../MongoOutboxAuditSink.java` + `AuditSinkTest.java` | A | |

4. **Verification:** `python3 -I tools/plan/build.py` -> `OK: 74 stories, 317 tasks, 1070h`; `mvn -B verify` -> 8 modules, **372 tests** (5 + 238 + 47 + 31 + 29 + 22), BUILD SUCCESS.
5. **Impact:** the existing Python `ai`, `ml` services (FastAPI) are **kept**, not deleted in J5; the `python-ci` template is kept. The Python foundation library for ai/ml (PY-01..05) remains D3's work as in the original plan.

### J5a - Java gateway (Spring Cloud Gateway) + fix how default configuration is loaded DONE
1. **Goal:** replace the .NET gateway (YARP) with Spring Cloud Gateway, keeping the exact routing table in 07 section 1 and layer 1 of 15-authorization (token validation at the edge).
2. **Work done (in order):**
   1. Check compatibility: `spring-cloud-dependencies` **2025.1.2** (gateway 5.0.2) resolves with Boot 4.1.2 (the 2026.0 release is only at RC). `git rm -r services/gateway` (the .NET version).
   2. `services/gateway`: `GatewayApplication`, `GatewaySecurity` (WebFlux: `/internal/**` -> deny, health/info/prometheus public, every other route requires a valid JWT using the **same** `ShpJwt.validator` as the services; lazy decoder `SupplierReactiveJwtDecoder`; errors as Problem Details), `application.yml` generated from the old route table (7 upstreams, 32 paths; addresses changed with `SHP_UPSTREAM_<SERVICE>`).
   3. Test 7 scenarios with fake upstreams (real HTTP server in the test): routes to the correct service and **forwards the token unchanged** (services re-validate); 401 when missing/wrong audience; staff without MFA blocked at the edge; `/internal/**` -> 404; unknown path -> 404; upstream dead -> 5xx; health public.
   4. **Found a latent bug in `shp-service-defaults`:** `/info`, `/prometheus` returned 404 at the gateway. Enabled condition reporting (`-Ddebug=true`): `InfoEndpointAutoConfiguration ... not exposed` - `@PropertySource` on auto-configuration is loaded **after** Boot evaluates conditions; the MVC version passed in tests only thanks to lucky ordering. Fix: `ShpDefaultsEnvironmentPostProcessor` (registered in `META-INF/spring.factories`, keyed by Boot 4's `org.springframework.boot.EnvironmentPostProcessor`) loads every `classpath*:META-INF/shp/*.properties` with the lowest precedence **before** auto-configuration; remove `@PropertySource` from both `shp-service-defaults` and `shp-web`.
3. **Changes (commit e2558f0):**

| File | | Content |
|---|---|---|
| `services/gateway/**` (.NET) | D | YARP, Dockerfile, .NET tests |
| `services/gateway/pom.xml`, `src/main/**`, `src/test/**` | A | Java gateway + 7 tests |
| `pom.xml` | M | + module `services/gateway` |
| `libs/java/shp-service-defaults/.../ShpDefaultsEnvironmentPostProcessor.java`, `META-INF/spring.factories` | A | load defaults at the right time |
| `.../shp-defaults.properties` -> `META-INF/shp/00-service-defaults.properties`; `shp-web-defaults.properties` -> `META-INF/shp/10-web-defaults.properties` | R | moved (git mv) |
| `ServiceDefaultsAutoConfiguration.java`, `ShpWebAutoConfiguration.java` | M | remove `@PropertySource` |

4. **Verification:** `mvn -B verify` -> 9 modules, **379 tests** (7 more for the gateway), BUILD SUCCESS.
5. **Error encountered:** two `ReactiveJwtDecoder`s in the tests (`@ConditionalOnMissingBean` is unreliable in a plain `@Configuration`) -> test bean marked `@Primary`.
6. **Limitations:** no per-`sub` rate limit yet (needs Redis, GW task) and no CORS for the web.

### J5b - Remove every mention of the old PoC name from the project repos (PM request) DONE
1. **Work done:** `git grep -i <old PoC name>` across the 4 repos -> 13 occurrences, all in the app repo. Deleted `docs/03-architecture/<old PoC name>-migration.md` and section 5 of `overview.md` pointing to it; rewrote neutrally: README, `overview.md`, ADR-0002/0005/0008 (say "a common approach", "for example Medplum" instead of the PoC name), `phase4-build-log.md`, this log ("old PoC repo").
2. **Verification:** `git grep -i -c <old PoC name>` across all 4 repos -> 0.
3. **Caveat:** the app repo's **git history** still contains old commits with this word; removing it from history requires rewriting history and force-pushing - not done, pending PM decision (when moving to the real repo by copying content, the new history will be clean).
4. **Commit:** e2558f0 (same commit as J5a).

### J5c - The remaining 5 Java services, gateway packaging, local dev toolkit moved to Java DONE / partial
1. **Goal:** each Java service has a runnable skeleton (build, test, image, local deploy, pipeline) so devs only write business logic; delete all remaining .NET code.
2. **Work done (in order):**
   1. Write `tools/scaffold/java_service.py` (replacing `dotnet_service.py`) and generate 5 services: `scheduling` (PostgreSQL) and `notification`, `clinical`, `document`, `audit` (MongoDB).
      Each service has:
      - `pom.xml` (`finalName app`, `shp-data-*`, Testcontainers) and `*Application`.
      - `config/PlatformConfig`: beans for outbox, inbox, idempotency, audit sink. With Mongo there is also a `MongoTransactionManager` and collection + index creation at startup.
      - `IdentityLinkClient`: clinical reads its own Mongo directly; other services call `SHP_CLINICAL_URL`.
      - `application.yml` takes every sensitive value from environment variables.
      - Tests: public health, empty `UnprotectedEndpoints`, 401 without a token; clinical has an additional identity-link test.
      - Dockerfile (Maven builder -> `distroless/java21:nonroot`), `deploy/values-local.yaml`, README "what devs write, where".
      - `.github/workflows/svc-<name>.yml` using `java-ci` from the templates repo.
   2. Gateway: add `Dockerfile`, `deploy/values-local.yaml`, README, `<finalName>app</finalName>` (deleted along with the .NET version in J5a).
   3. **Fix the issuer mismatch on kind:**
      - Tokens are obtained from the dev machine via `http://localhost:18080`, so the issuer is `localhost`. Pods cannot reach the machine's `localhost`.
      - Add `shp.auth.jwk-set-uri` (`OIDC_JWK_SET_URI`) to `ShpAuthProperties`. When set, the decoder fetches keys from this URL (in-cluster DNS), while the issuer is still validated against `OIDC_AUTHORITY`. Applied to both servlet (`ShpWebAutoConfiguration`) and reactive (`GatewaySecurity`).
   4. Dev toolkit:
      - `deploy.sh`: `JAVA` list, context is the repo root. The `shp-local-conn` Secret now contains `postgres-password`, `redis-password`, `rabbitmq-password`, `mongodb`, `rabbitmq`, `blob`.
      - `app-values-local.yaml`: `SPRING_*` per Spring Boot convention; `MONGODB_URI`/`RABBITMQ_URL` for Python; OIDC as in step 3; MFA disabled for test users.
      - `env.sh`: `SPRING_*` variables for running outside the cluster.
      - Local Postgres now only has the `scheduling` database.
      - `up.sh` runs `contracts/db/mongo-notification-ml.js` after the replica set is ready.
      - Update `dev/README.md` (JDK 21 + Maven; environment variable table).
   5. Add a root `.dockerignore`: the Java build context is the whole repo, so `target/` and **`dev/.local` (contains randomly generated passwords)** must be excluded.
   6. `.editorconfig`: remove the C# section, add Java/pom with 2-space indent (Spotless is the source of truth). Delete all of `libs/dotnet`, the 5 .NET services, `Directory.*.props`, `global.json`, `libs-dotnet.yml`.
   7. `tools/plan/status.py`: LOC-03, LOC-05, GW-01, SCF-01..05 = partial (state explicitly what is missing).
3. **Changes (commit c909d5b, 159 files):**

| File | | Content |
|---|---|---|
| `tools/scaffold/dotnet_service.py` -> `java_service.py` | D / A | Java service generator |
| `services/{scheduling,notification,clinical,document,audit}/**` | D (.NET) / A (Java) | Java skeleton as in step 2.1 |
| `services/gateway/{Dockerfile,README.md,deploy/values-local.yaml}`, `pom.xml` | A / M | package the gateway |
| `libs/java/shp-security/.../ShpAuthProperties.java` | M | 4 -> 5 parameters (`jwkSetUri`, default empty) |
| `ShpWebAutoConfiguration.java`, `GatewaySecurity.java` | M | `fromIssuerLocation` -> choose `withJwkSetUri` when configured |
| `10-web-defaults.properties`, `services/gateway/.../application.yml` | M | + `shp.auth.jwk-set-uri=${OIDC_JWK_SET_URI:}` |
| `dev/scripts/{deploy,env,up}.sh`, `dev/chart/app-values-local.yaml`, `dev/chart/shp-local-deps/values.yaml`, `dev/kind/cluster.yaml`, `dev/README.md` | M | as in step 2.4 |
| `.dockerignore` | A | exclude `target/`, `dev/.local`, Python cache |
| `.editorconfig` | M | - C#; + Java |
| `libs/dotnet/**`, `Directory.*.props`, `global.json`, `.github/workflows/libs-dotnet.yml` | D | all .NET gone |
| `.github/workflows/svc-*.yml` | A/M | regenerated for Java; new `libs-java.yml` |

4. **Redo commands:**
```bash
for s in scheduling notification clinical document audit; do python3 -I tools/scaffold/java_service.py $s; done
mvn -B spotless:apply && mvn -B verify                    # Docker needed for Testcontainers
docker build -f services/gateway/Dockerfile -t localhost/shp-gateway:dev .
```
5. **Verification:**
   - `mvn -B verify` for the whole reactor: 14 modules, **395 tests**, BUILD SUCCESS. `tools/contracts/check_all.sh`: ALL CONTRACT CHECKS PASSED. `tools/plan/build.py`: 317 tasks, 1,070 hours.
   - Gateway image, actually run: `/health/readiness` -> UP; `/info` -> `{"name":"gateway",...}`; `/fhir/Patient` -> 401; user `nonroot`.
   - Scheduling image, actually run with Postgres 16: readiness UP; Flyway creates `outbox`, `inbox`, `idempotency_key`; `/fhir/Appointment` -> 401.
   - The Mongo command in `up.sh` runs twice in a row on Mongo 8 replica set: no errors, creates all notification/ml collections.
   - Fields written by `MongoOutbox` match the script's `outbox` validator (`eventType`, `subject`, `payload`, `occurredAt`); index names match.
   - `helm template` of the `microservice` chart with local values for gateway, scheduling, clinical, ai: renders the correct `OIDC_JWK_SET_URI` and `postgres-password` secret.
6. **Errors and fixes:**
   - `docker build` in the sandbox: Maven in the container does not trust the proxy CA (`PKIX path building failed`). This is an environment error, not a Dockerfile error; dev machines and GitHub Actions do not hit it.
     Alternative verification: run the exact `mvn ... package` command of the build stage on the machine, then build the runtime stage with that jar.
   - Gateway was missing `deploy/values-local.yaml` (deleted along with the .NET version) -> `helm template` failed -> added.
   - Admin endpoints are at `/health`, `/info`, with no `/actuator` (base-path `/` per `shp-service-defaults`).
7. **Remaining limitations:**
   - No real `EventPublisher` yet (Spring Cloud Stream -> RabbitMQ/Service Bus), so the relay does not push events to the bus yet.
   - `make quickstart` has not been run on a real kind cluster.
   - The real GitHub Actions pipeline has not been run.
   - Commit: `git show --stat c909d5b`.

### J7 - Overall architecture and module diagrams; clean up .NET leftovers DONE
1. **Goal:** the PM needs an overall software architecture diagram and a list of modules; remove every trace of .NET left in the code.
2. **Work done (in order):**
   1. `docs/03-architecture/overview.md` adds 4 new sections (renumbering the old sections to 5-8). There are 5 mermaid diagrams:
      - Section 1 overall: client -> WAF/Ingress -> gateway -> 6 Java services + 2 Python, bus, data stores, LLM, news providers.
      - Section 2.1 events E1-E11 (producer -> event group -> consumer).
      - Section 2.2 internal REST `/internal/v1/**`.
      - Section 3 Maven modules (dependencies taken from the real `pom.xml`), module role table and owner.
      - Section 4 package layout inside a Java service: `api` -> `app` -> `domain`; `infra`; `messaging`; `config`.
   2. Section 7 (infrastructure): replace old .NET/MinIO names:
      - `IFhirStore` -> Spring JDBC/Data MongoDB
      - `StackExchange.Redis` -> Spring Data Redis
      - MinIO -> Azurite
      - `IConfiguration` -> environment variables/Spring
   3. The scaffold now also generates `package-info` for `app`, `domain`, `infra`, `messaging` in the 5 services; service READMEs have a "Package layout" line.
   4. .NET cleanup:
      - Comments in `pom.xml`, `ShpJwt`, `ServiceDefaultsAutoConfiguration`, `.gitignore`.
      - `docs/05-plan/project-plan.md`: "Dev .NET" -> "Dev Java", `libs/dotnet` -> `libs/java`.
      - Templates repo: the `microservice` chart sets `SERVER_PORT` instead of `ASPNETCORE_URLS` (commit fe5e2f9).
3. **Changes:**

| File | | Content |
|---|---|---|
| `docs/03-architecture/overview.md` | M | + sections 1-4 (5 diagrams); section 7 Java technology names; catalog + Dev column |
| `tools/scaffold/java_service.py`, `services/*/README.md` | M | + `LAYERS`, package layout line |
| `services/{5}/src/main/java/org/shp/*/{app,domain,infra,messaging}/package-info.java` | A | 20 files |
| `pom.xml`, `.gitignore`, 2 Java classes, `project-plan.md`, `status.py` | M | remove .NET wording |
| templates: `charts/microservice/templates/_helpers.tpl` | M | `ASPNETCORE_URLS` -> `SERVER_PORT` |

4. **Redo command:** `python3 -I tools/scaffold/java_service.py` (only creates missing files). Render diagrams:
   `npx -p @mermaid-js/mermaid-cli@11.12.0 mmdc -i d.mmd -o d.png` (puppeteer points `executablePath` to an available Chromium).
5. **Verification:**
   - All 5 diagrams render with mermaid-cli 11.12.0; inspected the images, and redrew section 2 because the first version overlapped labels.
   - `mvn -B verify` for the whole reactor: BUILD SUCCESS.
   - `helm lint` of the chart with the 3 CI values sets: 0 errors.
   - Code and configuration (except ADRs and the historical log) no longer contain `dotnet`, `.NET` or `ASPNETCORE`. The remaining `grep` results are only `java.net` (Java standard library), `com.networknt` and the `*.windows.net` domain.
6. **Errors and fixes:**
   - The module diagram and event diagram were cluttered at first because edge labels were repeated for every target. Fix: make events intermediate nodes; group relationships with `contracts/` into a single edge.
   - Two claims not present in the code were rewritten as rules for the uat/prod values: egress only opened for ai/notification; Python package layout.
7. **Deliberately kept:** ADR-0002/0012 (already Superseded) and the old log still mention .NET, because that is decision history.

### J6 - Dev onboarding: README, CONTRIBUTING, per-dev guide, PR template, CODEOWNERS, issues DONE
1. **Goal:** devs can clone the repo, read, receive tasks and code immediately, without asking the SA how to start.
2. **Work done (in order):**
   1. Merge PR #26 (Java) into `main`; reset the working branch from the new `main` (`1bd0456`).
   2. `docs/onboarding.md`: 5 steps (set up machine -> `make -C dev quickstart` -> what to read -> D1/D2/D3's part -> first PR).
      Each dev has: services owned, design documents to read (every link checked to exist), first task, libraries already available.
   3. `CONTRIBUTING.md`: 4 principles (synthetic data, no secrets, contracts first, devs only write logic); task process -> branch `<ID>-...` -> squash PR;
      check commands like CI; CI blocking conditions (cross-referenced with `java-ci`/`python-ci`: coverage 70%); Java code conventions.
   4. `.github/pull_request_template.md`: task/story, contract impact, Definition of Done checklist.
   5. `.github/CODEOWNERS`: default repo owner; lines for D1/D2/D3 left as comments (no GitHub accounts yet; nonexistent names make GitHub drop the line);
      `libs/`, `contracts/`, `.github/`, `dev/`, Dockerfile, `deploy/`, `config/` always require SA.
   6. `.github/dependabot.yml`: maven, pip (ai, ml, tools/contracts), docker, github-actions; `github-setup.md` nuget -> maven.
   7. Root README: "Quick start" section + directory table; update phase 4-5 status.
   8. GitHub issues:
      - Closed (completed) 8 finished issues, now owned by SA: #2 SHR-21, #3 SHR-22, #4 SHR-23, #5 SHR-24, #6 SHR-25, #8 SHR-28, #10 SHR-02, #16 SHR-12. Each issue body records the commit.
      - #1 SHR-20 changed to `owner:SA`, kept open (partial).
      - Fixed titles #6 (ASP.NET -> Spring), #9 (Firely -> HAPI FHIR), #23 (Azure OpenAI/Anthropic -> DeepSeek/Gemini/Ollama).
   9. `status.py`:
      - NTF-01 = done: the `contracts/db/mongo-notification-ml.js` script meets all criteria.
      - ML-11 = partial: missing the `score` constraint, because time-series collections do not accept validators.
3. **Changes:**

| File | | Content |
|---|---|---|
| `docs/onboarding.md`, `CONTRIBUTING.md` | A | dev guide |
| `.github/{CODEOWNERS,pull_request_template.md,dependabot.yml}` | A | PR governance |
| `README.md` | M | + Quick start, directory table, status |
| `docs/04-devops/github-setup.md` | M | Dependabot nuget -> maven |
| `tools/plan/status.py`, `docs/05-plan/by-person/*` | M | NTF-01 [x], ML-11 [~] |
| GitHub issues #1-#10, #16, #23 | - | as in step 2.8 |

4. **Verification:**
   - Every relative link in `onboarding.md` points to an existing file.
   - `tools/plan/build.py`: OK.
   - Commands in CONTRIBUTING cross-checked with the template workflows (`pytest --cov-fail-under=70`, `mvn verify` with Spotless/JaCoCo).
5. **Your work (PM):** once D1/D2/D3 GitHub accounts exist, uncomment the lines in `.github/CODEOWNERS` and invite them as collaborators; enable the rulesets per `github-setup.md`.

### G1 - GitOps repo (`shp-gitops-scratch`) DONE / partial
1. **Goal:** Argo CD knows what to run on the `uat`/`prod` clusters. Matches the `gitops-promote` pipeline (writes `envs/<env>/<svc>/values.yaml`).
2. **Work done (in order):**
   1. `bootstrap/`: Argo CD install values (chart 10.11.5; local admin disabled, UI not exposed externally) and a root Application per environment.
   2. `clusters/<env>/`:
      - AppProjects `platform` and `shp-apps` (services only go into the `shp-apps` namespace, do not create cluster-scoped resources).
      - Add-on ApplicationSet, pinned versions: cert-manager v1.21.2, kyverno 3.9.1, argo-rollouts 2.43.7, keda 2.20.2, envoy-gateway 1.9.3, otel-collector 0.177.0.
        Versions taken from `helm search repo`, choosing the release immediately before the latest.
      - Service ApplicationSet, with a post-selector that skips services without a digest (the chart refuses to render when the digest is missing).
   3. `platform/charts/shp-baseline`:
      - PSA restricted namespace + default-deny NetworkPolicy.
      - 4 Kyverno ClusterPolicies: ACR only, keyless cosign signature from `container.yml`, ban `:latest`, require resources/probes.
      - GatewayClass/Gateway + ClusterIssuer.
   4. `envs/`: common values + per-environment values + 8 services x 2 environments. The gateway only accepts traffic from `envoy-gateway-system`; other services accept traffic within the namespace.
   5. `tools/validate.sh` + `validate` workflow:
      - Valid YAML; `clusters/uat` == `prod` (ignoring comments); render; kubeconform with CRD schemas.
      - List required fields still empty (`--strict` makes it an error).
   6. Create the GitOps repo's `main` branch from the working branch (Argo CD and the promote bot use `main`).
3. **Verification:** `tools/validate.sh` -> OK. kubeconform: 106 resources, 0 errors, 0 skipped. The README diagram renders.
4. **Errors and fixes:**
   - The uat/prod comparison step reported a mismatch because a comment contained the word "uat" -> strip comments before comparing.
   - One network push error -> retried successfully.
5. **Not done:**
   - ENV-01 (install Argo CD via workflow, create Secrets from Key Vault).
   - Service DB/bus connectivity in the cloud (waiting for data IaC).
   - Pin the chart by tag (CIT-09).
   - Config fields left empty (ACR, domain, OIDC) must be filled before provisioning environments.
   - Not yet run on a real AKS.

## Part D - Next steps (in order)
| # | Step | Who | Status |
|---|---|---|---|
| D1 | Java libraries `libs/java` DONE (J2-J4) + 6 Java services DONE (J5); remaining: `EventPublisher` Spring Cloud Stream + consumer/DLQ, FHIR resource store Mongo (SHR-10) | SA | in progress |
| D1b | Dev onboarding (J6): root README, CONTRIBUTING, per-dev guide, PR template, CODEOWNERS, issues | SA | DONE |
| D1c | Overall architecture + module diagrams (J7) | SA | DONE |
| D2 | Python libraries (`libs/python`): LLM router (DeepSeek -> Gemini -> Ollama, PII redaction), shared feature package for ML | SA | TODO |
| D3 | GitOps repo: Argo CD bootstrap, AppProject, ApplicationSet, add-ons, Kyverno, `envs/{uat,prod}` | SA | DONE (G1) |
| D4 | ENV-01: install Argo CD in the `env-provision` workflow | SA | TODO |
| D5 | Data IaC modules (IAC-09..16), `ai` node pool for Ollama + LLM key secret | SA | TODO |
| D6 | Local `full` profile (OpenSearch, Ollama, ClamAV, MLflow, observability), MFA realm | SA | TODO |
| D7 | GitHub setup (rulesets, CODEOWNERS, Environments, GitHub App, Variables) per `github-setup.md` | YOU | TODO |
| D8 | Bootstrap Azure once, fill in GitHub Variables | YOU | TODO |
| D9 | Rotate keys exposed in the old PoC repo + purge history | YOU | TODO |
| D10 | Move the templates/infra repos to private | YOU | in progress |
