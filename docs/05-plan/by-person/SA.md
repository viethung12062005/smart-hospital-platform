# Work for SA — Solution Architect / PM / DevOps; Keycloak, shared libraries, AI, ML, synthetic data

> Generated automatically by `python3 -I tools/plan/build.py`. Each task is one PR (branch name `<ID>-short-description`). Follow the week order; do not start a task whose dependencies are not done.

**Definition of Done for every task:** unit tests green, lint clean, no secrets, PR reviewed and merged through the pipeline.

Total: **221 tasks, 767 hours**. Done: **79**, partially done: **22** (☑ done, ◐ partial, ☐ not started; recorded in `tools/plan/status.py`).

## Week 1 — 42 hours

| ✓ | ID | Work | Done when | Hours | Depends on | Story |
|---|---|---|---|---|---|---|
| ☐ | ARC-01 | G1 sign-off: business analysis | Scope, KPIs and business rules signed off by the team; doc status = Approved | 2 |  | ENABLER |
| ☐ | ARC-02 | G2 sign-off: finalize NFRs and MoSCoW | Every NFR has a number + measurement method; Must/Should/Could list finalized | 3 | ARC-01 | ENABLER |
| ☐ | ARC-03 | G3 sign-off: move ADR 0001-0010 to Accepted | Each ADR traces back to FR/NFR; status Accepted | 3 | ARC-02 | ENABLER |
| ☐ | ARC-04 | C4 Context and Container diagrams | 2 diagrams (Mermaid/PlantUML) in docs/03, matching the service catalog | 4 | ARC-03 | ENABLER |
| ☐ | ARC-05 | Data model per service | FHIR resource + SearchParameter tables each service must support; data ownership clear | 6 | ARC-03 | ENABLER |
| ☑ | ARC-09 | Coding standards & conventions for services **[done, commit 3e827ec: docs/00-conventions.md: directory structure, errors, PII-free logging, tracing, configuration, versioning]** | Document: directory structure, errors, PII-free logging, tracing, configuration, API versioning | 4 | ARC-03 | ENABLER |
| ☑ | ARC-10 | Definition of Ready/Done, PR and branch workflow **[done, commit 3e827ec: docs/00-workflow.md: DoR/DoD, PR workflow, main protected; PR template reflects the DoD]** | Document in docs/00; PR template reflects the DoD | 2 | ARC-03 | ENABLER |
| ☑ | ARC-11 | Synthetic data specification **[done, commit 3e827ec: docs/00-synthetic-data.md: rules for generating Vietnamese synthetic data, scale, distribution, automated checks]** | Rules to generate Vietnamese patients, doctors, schedules and encounters; no real data used | 3 | ARC-05 | ENABLER |
| ☑ | GOV-01 | Create 4 repos per ADR-0010 **[done, commit viethung12062005: 4 private repos, main branch, squash-only, delete branch after merge, Dependabot alerts; GitHub secret scanning not available on the current plan (replaced by gitleaks in CI)]** | 4 repos exist, README describes responsibilities, visibility correct | 1 | ARC-03 | US-070 |
| ☑ | GOV-02 | Main branch protection ruleset for 4 repos **[done, commit ruleset protect-main: All 4 repos: PR required, 1 review + Code Owner, dismiss approval on new push, resolve threads, linear history, force-push/delete blocked; only the repo owner can bypass via PR]** | PR required, 1 review, green CI, force-push blocked; GitOps bot bypasses in shp-gitops | 2 | GOV-01 | US-070 |
| ◐ | GOV-03 | CODEOWNERS + PR template + issue template **[partially done, commit gitops 3ce293d, infra 124859b, templates 97aea24: CODEOWNERS + PR/issue templates for 3 repos (awaiting merge); app repo already had them; dev CODEOWNERS (D1..D3) waiting for real GitHub accounts]** | PRs to infra/gitops-prod need SA approval; templates include a DoD checklist | 2 | GOV-01 | US-070 |
| ☐ | GOV-07 | GitHub Project + backlog import | Project board has Todo/In progress/Review/Done columns and Sprint/Owner/Estimate fields; backlog.csv imported | 2 | GOV-01 | US-070 |
| ☐ | LOC-01 | Local dev toolkit on kind (core profile): small K8s cluster + Helm for PostgreSQL, MongoDB replica set, Redis, RabbitMQ, Azurite, Keycloak; Makefile up/down/status | `make up` provisions the cluster and all core dependencies in < 10 minutes on a 16 GB machine; `make down` cleans up; README included | 8 | ARC-09 | ENABLER |

## Week 2 — 83 hours

| ✓ | ID | Work | Done when | Hours | Depends on | Story |
|---|---|---|---|---|---|---|
| ☑ | ARC-19 | Create the full contracts/authz/matrix.yaml from the authorization matrix **[done, commit cd8c789: 64 rules, safety-invariant validator, validator tests]** | Every matrix cell has a rule row; YAML schema validated; dev uses it as input for SHR-22 | 4 | ARC-03 | ENABLER |
| ☐ | CIT-01 | Reusable workflow Java CI (Maven) | Spotless check, build -Werror, tests + JaCoCo coverage threshold, vulnerable dependency scan; usable from other repos | 4 | GOV-01 | US-070 |
| ☐ | CIT-03 | Reusable workflow container | hadolint, buildx build, Trivy blocks High/Critical, SBOM, cosign keyless signing, ACR push only on main, digest output | 5 | IAC-02, IAC-03 | US-070 |
| ☐ | DAT-01 | Vietnamese synthetic data generator: patients, doctors, nurses (no real data) | Names/phones/DOB valid but fake; fixed seed for reproducibility | 4 | ARC-11 | ENABLER |
| ☐ | GOP-01 | Shared microservice Helm chart | Deployment/Rollout, Service, HPA/KEDA, PDB, NetworkPolicy, HTTPRoute, ServiceAccount WI, SecretProviderClass; values.schema.json | 8 | ARC-09 | US-070 |
| ☐ | IAC-01 | Bootstrap stack: shared RG, state storage | Storage account with versioning, soft delete, shared key disabled; state accessed via Entra ID | 3 | GOV-01 | ENABLER |
| ☐ | IAC-02 | Bootstrap: Azure Container Registry | ACR admin user disabled; retention policy; login server output | 2 | IAC-01 | ENABLER |
| ☐ | IAC-03 | Bootstrap: OIDC identity for GitHub | Identity plan (read), apply-<env>, acr-push; federated credentials per repo + environment; least-privilege RBAC | 4 | IAC-01 | ENABLER |
| ☐ | LOC-02 | Local Keycloak realm | Realm export with patient/doctor/nurse/admin/dpo roles, fhir_user claim, fake users | 3 | LOC-01 | US-008, US-066 |
| ☑ | LOC-03 | Walking skeleton Java (Spring Boot) **[done, commit c909d5b: 6 Java services: health/info/prometheus, OTel, distroless non-root Dockerfile; run through the real template pipeline on main (e.g. svc-scheduling run 41487094998: verify, build + image scan); image push to ACR pending IAC-02]** | Sample service: actuator health, info, OTel, distroless non-root Dockerfile; runs through the template pipeline | 4 | ARC-09 | ENABLER |
| ☐ | LOC-04 | Walking skeleton Python | FastAPI: health, info, OTel, non-root Dockerfile; runs through the template pipeline | 3 | ARC-09 | ENABLER |
| ◐ | LOC-08 | Full local dev profile: ClamAV, OpenSearch, Ollama, MLflow, Loki/Grafana/OTel; seed synthetic data into the cluster **[partially done, commit e8e2fe2: shp-local-full chart (ClamAV, OpenSearch, Ollama, MLflow, Loki, Grafana, OTel) + make up PROFILE=full + pull-models; helm lint/template done; NOT YET run on real kind]** | `make up PROFILE=full` provisions infrastructure for every service; healthchecks; resource requirements documented | 3 | LOC-01 | ENABLER |
| ☑ | ML-01 | Generate appointment data with no-show patterns (lead time, past absence, exam hour, channel) **[done, commit f108284: synth: 80k appointments, 18% no-show, with threshold and interaction effects]** | Documented patterns; no-show rate ~18% overall | 4 | ARC-11 | US-063 |
| ☑ | PY-01 | Python library: JWT authentication (Keycloak), role dependency for FastAPI **[done, commit 932b0e4: libs/python shp_platform.security: JWKS, iss/aud/expiry, roles, staff MFA, FastAPI dependency; tests for valid/invalid/expired tokens]** | Test valid/invalid/expired token | 3 | LOC-02 | US-066 |
| ☑ | PY-05 | Problem Details + error code catalog for FastAPI **[done, commit 932b0e4: errors: code catalog matches the Java ErrorCatalog, Problem Details, no input/exception leaks]** | Matches 07 §5; test | 2 | LOC-04 | ENABLER |
| ☑ | PY-06 | PII scrubber (name, phone, email, address, identifiers → fake tokens with reverse map) **[done, commit 932b0e4: pii.redact: email, phone, national ID, health insurance, id, date, known names/addresses; reverse map; tests]** | Synthetic data test suite: no PII left in prompts sent outside Azure | 4 |  | US-055 |
| ☐ | SHR-01 | FHIR R4 JSON serialize/parse (HAPI FHIR) + round-trip tests for in-scope resources | Invalid parse returns 400 validation-failed; test each resource type | 3 | LOC-03 | ENABLER |
| ☑ | SHR-02 | Error model: exception → OperationOutcome / Problem Details + error code catalog **[done, commit 5e423ab: libs/java/shp-web/errors: ErrorCatalog (27 codes, tests cross-check 07 §5), ShpException, ErrorWriter, ShpExceptionHandler]** | Every code in 07 §5 has a test; vi/en messages, no PII/stack trace leaks | 3 | SHR-01 | ENABLER |
| ☐ | SHR-03 | Search parameter registry + parser (token, reference, date with precision, _count) | ge/gt/le/lt/eq, '2026-11' = whole month; unknown parameter → 400 | 4 | SHR-01 | ENABLER |
| ◐ | SHR-20 | JWT bearer from Keycloak (standard OIDC): map roles claim, enforce amr=mfa for staff **[partially done, commit 5e423ab: shp-web ShpJwt + SecurityFilterChain (issuer, audience, expiry, staff MFA); tested with local HMAC tokens; NOT YET tested against real Keycloak]** | Tests for valid/bad-signature/expired/wrong-aud tokens and staff missing MFA; configured via environment variables | 3 | LOC-02 | US-008, US-066 |
| ☑ | SHR-21 | Identity resolver: sub → fhirUser + roles + disabled + linkedProfiles (record, scope), 60-second cache **[done, commit 5e423ab: shp-web IdentityResolver (60s/5s cache) + HttpIdentityLinkClient + IdentityInterceptor; clinical-side internal endpoint is a dev task]** | Locked accounts rejected within ≤ 60 seconds; cache expires correctly; on clinical failure use the cache, return 503 when there is no cache | 3 | SHR-20 | US-007 |
| ☑ | SHR-22 | Authz: schema + loader + validator for contracts/authz/matrix.yaml **[done, commit 95d2f74: libs/java/shp-authz: AuthzMatrix (Java)]** | A matrix with a bad schema (missing role, unknown predicate) fails the build; unit tests | 3 | ARC-19 | ENABLER |
| ☑ | SHR-23 | Authz: predicates own, self-practitioner, care, breakglass, released, before-2h, encounter-finished, not-care **[done, commit 95d2f74: Predicates in AuthorizationEngine (care/breakglass received via ResourceContext, no network calls)]** | Each predicate has allow/deny tests; care/breakglass predicates go through a mockable interface | 4 | SHR-22 | ENABLER |

## Week 3 — 135 hours

| ✓ | ID | Work | Done when | Hours | Depends on | Story |
|---|---|---|---|---|---|---|
| ◐ | ARC-06 | Skeleton API contracts (OpenAPI) for 6 services **[partially done, commit cd8c789: 83-operation skeleton for 7 services, OpenAPI validator; NO request/response body schemas yet (dev adds them when building endpoints)]** | openapi.yaml per service with endpoints and OperationOutcome error codes; lint passes | 6 | ARC-05 | ENABLER |
| ☑ | ARC-07 | Event contracts (JSON Schema) **[done, commit cd8c789: 11 event schemas + examples + envelope; validator matches catalog E1–E11]** | Schemas for AppointmentBooked/Cancelled, DocumentUploaded, AuditRecorded, NoShowScored; versioned | 4 | ARC-05 | ENABLER |
| ◐ | ARC-20 | Reference data: ICD-10 (~300 codes), LOINC for vital signs/lab tests, CodeSystem for specialties and cancellation reasons **[partially done, commit cd8c789: 12 specialties, 6 cancellation reasons, 10 vital-sign LOINCs, 6 document types, 12 ICD-10 codes; NOT YET the full ~300 ICD-10 codes and not yet cross-checked against official sources]** | JSON files in contracts/terminology, loader script, used by CLN-16/CLN-18 | 4 | ARC-03 | ENABLER |
| ☐ | CIT-05 | Reusable workflow Terraform checks + plan | fmt, validate, tflint, Trivy config, plan + summary in the job summary; plan via read-only OIDC | 4 | IAC-03 | US-070 |
| ☐ | CIT-06 | Reusable workflow GitOps update/promote | Update digest in shp-gitops; uat commits after approval; prod opens a PR after approval | 4 | GOV-05 | US-070 |
| ☐ | DAT-02 | Generator for work schedules, appointments, encounters, vital signs, diagnoses, sample documents | Consistent data across services (IDs match) | 4 | DAT-01 | ENABLER |
| ☑ | GOP-03 | Argo CD bootstrap chart **[done, commit gitops 4314a40: shp-gitops repo: Argo CD bootstrap, platform/shp-apps AppProject, platform + services ApplicationSet per environment (app-of-apps)]** | AppProject, ApplicationSet for platform + services per environment; app-of-apps | 6 | GOP-01 | US-070 |
| ☐ | GOV-05 | GitHub App bot for GitOps | App has only contents:write on shp-gitops; secret stored as org/repo secret | 2 | GOV-01 | US-070 |
| ☐ | IAC-05 | Module network | VNet, AKS/private endpoint/Postgres (delegated) subnets, private DNS zones; validate + tflint clean | 5 | IAC-01 | ENABLER |
| ☐ | IAC-06 | Module monitoring | Log Analytics, Azure Monitor workspace (Prometheus), Managed Grafana, App Insights | 4 | IAC-05 | ENABLER |
| ☐ | IAC-07 | Module aks | Azure CNI overlay + Cilium, Workload Identity, KV CSI, Azure RBAC, local accounts disabled, 3 node pools, 3 zones | 8 | IAC-05, IAC-06 | ENABLER |
| ☑ | ML-02 | Feature engineering + time-based train/validation split **[done, commit e391985: features.training_frame uses only pre-bookedAt information + time_split; leakage test]** | No future leakage; feature tests | 4 | ML-01 | US-063 |
| ☑ | ML-03 | Baseline Logistic Regression + metrics (AUC, precision/recall at threshold) **[done, commit 34b171e: LR baseline in the pipeline, run recorded (FileRegistry; MLflow adapter not yet tested with a server)]** | Recorded in MLflow | 3 | ML-02 | US-063 |
| ☑ | ML-04 | LightGBM + hyperparameter search + MLflow tracking **[done, commit 34b171e: LightGBM tunes on the last 3 months of train, AUC 0.784 > LR 0.769]** | Compare with baseline; save artifact | 4 | ML-03 | US-063 |
| ☑ | ML-13 | Versioned Parquet dataset: write dataset hash into MLflow run, rebuild model from hash **[done, commit 34b171e: Dataset hash recorded in the run and meta; same hash + seed give the same model (test)]** | Same hash + seed → same model (verified by test) | 3 | ML-01 | US-071 |
| ☑ | ML-14 | Data validation before training (schema, ranges, missing rate, temporal leakage) with pandera **[done, commit e391985: checks.check (pandas, no pandera): schema, ranges, missing < 2%, temporal leakage, label ratio]** | Bad data stops pipeline with clear reason; test failure cases | 3 | ML-13 | US-071 |
| ☑ | ML-15 | Shared feature package for training and serving **[done, commit e391985: features.features_for shared by train and serve]** | Single source for all features; has unit tests | 3 | ML-02 | US-071 |
| ☑ | ML-17 | Subgroup evaluation (specialty, age group, channel) + calibration + prediction latency **[done, commit 34b171e: Subgroup AUC, ECE, p95 latency in the gate]** | Gate blocks if subgroup AUC > 0,07 below overall or p95 > 100 ms | 4 | ML-04 | US-072 |
| ☑ | ML-21 | Second synthetic dataset with shifted distribution (longer lead time, higher no-show rate) **[done, commit e391985: synth --shifted: lead time +40%, no-show +6 points, more chatbot, more early-morning shifts; detector reports drift on this set]** | Documented shift magnitude; used for detector tests and demo | 3 | ML-01 | US-065 |
| ☑ | PY-02 | On-behalf-of user token exchange client (Keycloak, RFC 8693 token exchange) **[done, commit 67662f2: Token exchange actually used in AI4 (tested with a fake IdP); real Keycloak tried in IAM-07]** | Exchanged token carries user identity, correct aud; test with fake IdP | 3 | PY-01 | US-053 |
| ☑ | PY-03 | Python CloudEvents consumer: validate schema, inbox (Mongo/Postgres), retry/DLQ **[done, commit 968e3b6: MongoDB inbox/outbox/relay (transaction, lease), RabbitMQ (quorum queue, 5 attempts then DLQ) with real integration tests; Service Bus adapter not yet tested against Azure]** | Duplicate event processed once | 4 | ARC-07 | US-059, US-064 |
| ☑ | PY-07 | LLM provider interface + DeepSeek, Gemini, Ollama (OpenAI-compatible) adapters + mandatory PII redaction + fake provider for tests **[done, commit 932b0e4: Provider + OpenAI-compatible adapter (DeepSeek/Gemini/Ollama) + FakeProvider; timeout/429/5xx]** | Same interface; simulate timeout/429/5xx | 4 | LOC-04 | US-055 |
| ☑ | PY-08 | Router: model chain per task, timeout, failover to next model, final result when chain exhausted **[done, commit 932b0e4: Router: per-task chain, fallbackLevel, final fallback; providers outside Azure always receive redacted text]** | Record fallbackLevel; test all error branches | 4 | PY-07 | US-055 |
| ☐ | SHR-04 | Keyset page token (opaque, signed) + build searchset Bundle | Tampered token → 400; next/self links correct | 3 | SHR-03 | ENABLER |
| ☐ | SHR-05 | ETag/If-Match filter (412/428) and mandatory Idempotency-Key (428) | Missing header → 428; ETag W/"version" on every response | 2 | SHR-02 | ENABLER |
| ☑ | SHR-06 | CloudEvents envelope + JSON Schema validation + contract test helper **[done, commit 5e423ab: libs/java/shp-events: CloudEvent + CloudEvents.create (UUIDv7, traceparent, ≤64KB) + EventSchemas.validate (embedded schemas)]** | Events with a bad schema are rejected; helper used by every service | 3 | ARC-07 | ENABLER |
| ☑ | SHR-07 | Shared outbox relay (PostgreSQL and MongoDB stores), 1-second poll, SKIP LOCKED **[done, commit 3764d57: shp-data-postgres + shp-data-mongo: outbox written in the same transaction + relay (SKIP LOCKED / findAndModify with lease expiry); the real EventPublisher is StreamEventPublisher (shp-messaging, SHR-08); relay runs in 5 services, outbox → RabbitMQ tested in scheduling]** | Everything is still published after the bus dies and recovers; multiple replicas do not duplicate | 4 | SHR-06 | ENABLER |
| ☑ | SHR-08 | Platform consumer: idempotent inbox + Spring Cloud Stream (RabbitMQ local, Service Bus cloud), retry/DLQ **[done, commit 3764d57: PostgresInbox + MongoInbox; shp-messaging: Spring Cloud Stream (RabbitMQ local, Service Bus cloud), EventConsumers.idempotent; tested with real RabbitMQ: duplicate events processed once, 5 deliveries then DLQ, bad schema to DLQ. Service Bus checked when uat exists]** | Duplicate events are processed once; 5 delivery attempts then DLQ | 4 | SHR-06 | ENABLER |
| ☐ | SHR-10 | Mongo resource store: create/update with version CAS + history + outbox in one transaction | Stale version update matches 0; full rollback on error | 4 | SHR-03, LOC-08 | ENABLER |
| ☑ | SHR-24 | Authz: default-deny decision engine, field constraints (fields), purposeOfUse **[done, commit 95d2f74: AuthorizationEngine.decide (Java): default-deny, fields, purpose, 238 tests]** | No matching rule → Deny; with several matching rules, take the first Allow whose conditions hold; table tests | 3 | SHR-23 | ENABLER |
| ☑ | SHR-25 | Authz: Spring endpoint filter + 'every endpoint must have a rule' test **[done, commit 5e423ab: shp-web @RequireShpAuthz + AuthzInterceptor + UnprotectedEndpoints.find (test catches missing authorization)]** | Adding an endpoint without a rule fails the CI test; 403/404 follow the convention | 4 | SHR-24 | ENABLER |
| ☐ | SHR-26 | Authz: test generator from matrix.yaml (role × condition) | Generate Allow/Deny tests for every cell from the matrix; usable in each service's tests | 4 | SHR-24 | ENABLER |
| ☑ | SHR-28 | Idempotency-Key middleware for PostgreSQL (claim, request hash, replay) **[done, commit 3764d57: shp-data-postgres PostgresIdempotency + shp-web IdempotencyKeys; 28 tests against real PostgreSQL]** | Same key+body → return the previous result; different body → 422; missing key → 428; concurrency tests | 4 | SHR-05 | US-015 |
| ☐ | SHR-30 | Authz: linked, linked-records, guardian conditions; onBehalfOf in audit; generate tests from the matrix for linked records | Every *-LINKED rule has Allow and Deny tests; audit records the acting account and the accessed record | 3 | SHR-24, SHR-21 | US-076, US-077 |
| ◐ | WEB-01 | React/Vite web shell: OIDC (Keycloak), role-based router, layout frame, i18n vi/en **[partially done, commit branch WEB-01-frontend-base: web/: React 18 + TS + Vite + Mantine 7, OIDC PKCE (shp-web), role-based routes/menu, vi/en i18n, ErrorBoundary, design system + AppShell ported from the portal PoC (Apache-2.0); 6 sample screens run with MSW; NOT YET real Keycloak login tested in a browser]** | Login, role-based menu, ErrorBoundary; build via pipeline | 4 | LOC-02, SHR-20 | US-066 |

## Week 4 — 127 hours

| ✓ | ID | Work | Done when | Hours | Depends on | Story |
|---|---|---|---|---|---|---|
| ☑ | AI-01 | Bootstrap MongoDB ai + script to create AI Search index kb-chunks-v1 (and local OpenSearch) **[done, commit 2b6a50c: kb-chunks-v1 index (Azure AI Search, OpenSearch local) created idempotently; ai Mongo has indexes]** | Idempotent script; same schema for both backends | 3 | SCF-06, LOC-08 | US-052 |
| ☑ | AI-02 | Document parser: PDF, DOCX, Markdown → structured text (sections, pages) **[done, commit 2b6a50c: PDF/DOCX/Markdown parser keeps headings and pages]** | Keeps section titles and page numbers; test with sample documents | 3 | AI-01 | US-052 |
| ☑ | AI-03 | Chunker: 600 tokens, 80 overlap, split by section heading **[done, commit 2b6a50c: 600/80 chunker does not cut mid-heading]** | Chunks do not cut across sections; length tests | 4 | AI-02 | US-052 |
| ☑ | AI-04 | Local bge-m3 embedding (Ollama, 1024 dims) + index upsert/delete; version swap with no gap **[done, commit 2b6a50c: bge-m3 embeddings (Ollama) + upsert/delete; old chunks deleted after new chunks enter the index]** | New chunks indexed before old deleted; test | 4 | AI-03, PY-07 | US-052 |
| ☐ | ARC-13 | Sprint review + planning S1 | Demo, update backlog status and risks; short minutes | 2 |  | ENABLER |
| ☐ | CIT-02 | Reusable workflow Python CI | ruff lint+format, mypy, pytest + coverage, pip-audit | 3 | GOV-01 | US-070 |
| ☐ | CIT-04 | Reusable workflow Helm validate | helm lint, render per environment, kubeconform strict, Trivy config | 3 | GOP-01 | US-070 |
| ☐ | CIT-07 | Shared check workflow for every PR | gitleaks, actionlint, dependency review, CodeQL (java-kotlin, python, actions) | 3 | GOV-01 | US-070 |
| ☐ | CIT-08 | Sample caller workflow for services + generator script | Script creates svc-<name>.yml from the template with correct path filters | 2 | CIT-01, CIT-02, CIT-03, CIT-06 | US-070 |
| ◐ | GOP-04 | Platform add-on: Argo Rollouts, KEDA, cert-manager **[partially done, commit gitops 4314a40: ApplicationSet pins versions: argo-rollouts 2.43.7, keda 2.20.2, cert-manager v1.21.2; NOT YET running on real AKS]** | Application pins chart versions; values per environment | 3 | GOP-03 | US-070 |
| ◐ | GOP-05 | Platform add-on: Envoy Gateway (Gateway API) **[partially done, commit gitops 4314a40: envoy-gateway 1.9.3 + GatewayClass/Gateway + Let's Encrypt ClusterIssuer (shp-baseline); NOT YET a domain, not yet run for real]** | GatewayClass/Gateway, TLS via cert-manager | 3 | GOP-04 | US-070 |
| ◐ | GOV-04 | GitHub Environments and reviewers **[partially done, commit Environments: Created deploy-uat/prod, infra-uat/prod(+destroy); NOT YET reviewers/wait timers because the GitHub plan does not support them for private repos]** | deploy-uat, deploy-prod, infra-<env>, infra-<env>-destroy (env = uat, prod) have reviewers and wait timers per ADR-0009 | 2 | GOV-01 | US-070 |
| ☐ | GOV-08 | Translate documentation, contracts and the shared Python library to English | docs/, contracts/ and the tools/ sources contain no Vietnamese prose; the backlog, SRS, trace and contract checks are green and the service copies match libs/python | 8 | GOV-07 | US-070 |
| ☐ | GOV-09 | Translate the Java services, web app and dev scripts to English | Comments, docstrings and developer-facing text in libs/java, services/, web/, dev/ and tests/ are English; product data stays Vietnamese (vi locale, Vietnamese test corpora); linters, tests and the web build pass | 8 | GOV-08 | US-070 |
| ☐ | GOV-10 | Convert GitHub issues, pull requests and comments to English | Every issue, PR title/body and comment in the 4 repos is English; task and user-story bodies match the English backlog; no Vietnamese left in GitHub records | 8 | GOV-08 | US-070 |
| ☐ | GOV-11 | Translate the infra, GitOps and templates repositories to English | Comments, descriptions, READMEs and templates in shp-infra, shp-gitops and shp-platform-templates contain no Vietnamese prose; terraform fmt/validate, the GitOps validate script and the chart checks are green | 4 | GOV-10 | US-070 |
| ☐ | GOV-12 | Definition of Done audit of every completed task | docs/05-plan/dod-audit.md gives a verdict with evidence for every task marked Done, lists each gap with a proposed fix, and traces the acceptance criteria to the test catalog | 8 | GOV-11 | US-070 |
| ☐ | GOV-13 | Instructions for AI agents (AGENTS.md, CLAUDE.md) in the four repositories | Each repo has an AGENTS.md that states the non-negotiable rules and routes to the convention documents, and a CLAUDE.md that imports it; every path it names exists; conventions stay defined in docs/ | 4 | GOV-12 | US-070 |
| ☐ | IAC-04 | Bootstrap: budget alert + tag policy | Budget alerts at 50/80/100%; shp-env, created-at, ttl-hours tags required | 2 | IAC-01 | ENABLER |
| ☐ | IAC-08 | Module key vault | RBAC mode, private endpoint, purge protection only in prod, name with random suffix | 2 | IAC-05 | ENABLER |
| ◐ | LOC-05 | Run services on the local cluster with the shared microservice chart (make deploy SVC=...) **[partially done, commit c909d5b: deploy.sh + app-values-local for Java (SPRING_*, in-cluster JWKS); chart renders per service; NOT YET make quickstart run on real kind]** | Sample service builds, loads into kind and runs with the same chart used for UAT/prod; debug guide included | 3 | GOP-01, LOC-03, LOC-01 | ENABLER |
| ☐ | LOC-07 | Developer onboarding guide | From cloning the repo to running locally and opening a first PR in < 1 hour | 2 | LOC-01, LOC-03 | ENABLER |
| ◐ | ML-05 | Quality gate and model registry: AUC ≥ 0,75 and above baseline before production alias registration **[partially done, commit 34b171e: Eligibility gate §3, FileRegistry with alias and tests; MLflow adapter written, NOT YET tested against an MLflow server (LOC-08)]** | Weak model blocked; gate has tests | 3 | ML-04, LOC-08 | US-063 |
| ☑ | ML-18 | Auto-generate model card on model registration **[done, commit 34b171e: card.md generated on registration, includes a synthetic-data warning]** | Model card stored with artifact in MLflow; all sections per 16-mlops §3 | 3 | ML-17, ML-05 | US-072 |
| ☐ | OBS-04 | Standard observability library (OTel, logs with trace-id, PII redaction) | OTel traces/metrics/logs, logs with trace-id, PII-redacting enricher; test asserts logs contain no PII | 4 | LOC-03 | ENABLER |
| ☑ | PY-04 | Audit emitter Python (E11) for AI/ML **[done, commit 932b0e4: audit.build: redacts IPs, blocks search values in resource, matches the E11 schema]** | Does not log question content or PII; test | 2 | PY-03 | US-047 |
| ☑ | PY-09 | Circuit breaker + token/day quota + per-model metrics **[done, commit cf3ed23: Circuit breaker, shared daily token quota (MongoDB), per-model metrics]** | Circuit opens after 5 failures/60s; over budget → ai-budget-exceeded | 3 | PY-08 | US-055 |
| ☑ | SCF-01 | Scaffold scheduling service (code skeleton, Dockerfile, svc-scheduling pipeline, Helm values, DB/bus/OTel wiring) **[done, commit c909d5b: scheduling: Spring Boot skeleton + Flyway + PostgreSQL outbox/inbox/idempotency, Dockerfile, svc-scheduling.yml, local/uat/prod values; uat/prod log in to PostgreSQL with Workload Identity (azure-identity-extensions, shp-gitops + shp-infra SCF-01); bus wiring done in SHR-08]** | Service from template; svc-scheduling.yml; local/uat/prod values | 2 | LOC-03, CIT-08 | ENABLER |
| ◐ | SCF-02 | Scaffold notification worker (code skeleton, pipeline, values, DB/bus wiring) **[partially done, commit c909d5b: notification: skeleton + Mongo outbox/inbox, collections created by make up; NOT YET bus wiring, uat/prod values]** | Service from template; svc-notification.yml | 2 | LOC-03, CIT-08 | ENABLER |
| ◐ | SCF-03 | Scaffold clinical service (code skeleton, pipeline, values, MongoDB/bus wiring) **[partially done, commit c909d5b: clinical: skeleton + Mongo + MongoIdentityLinkClient; NOT YET bus wiring, uat/prod values]** | Service from template; svc-clinical.yml | 2 | LOC-03, CIT-08 | ENABLER |
| ◐ | SCF-04 | Scaffold document service (code skeleton, pipeline, values, Blob/MongoDB) **[partially done, commit c909d5b: document: skeleton + Mongo; NOT YET Blob client, bus wiring, uat/prod values]** | Service from template; svc-document.yml | 2 | LOC-03, CIT-08 | ENABLER |
| ◐ | SCF-05 | Scaffold audit worker (code skeleton, pipeline, values, Blob WORM/Log Analytics) **[partially done, commit c909d5b: audit: skeleton + Mongo; NOT YET Blob WORM/Log Analytics, bus wiring, uat/prod values]** | Service from template; svc-audit.yml | 2 | LOC-03, CIT-08 | ENABLER |
| ☐ | SCF-06 | Scaffold ai-service FastAPI (code skeleton, pipeline, values, ai node pool) | FastAPI from template; svc-ai.yml; ai node pool | 2 | LOC-04, CIT-08 | ENABLER |
| ☐ | SCF-07 | Scaffold ml-service (Python code skeleton, Dockerfile, pipeline, values, MongoDB/bus/MLflow wiring) | Sample service runs through the template pipeline | 2 | LOC-04, CIT-08 | ENABLER |
| ◐ | SHR-09 | Audit emitter library: builds audit.recorded (schema 05), redacts IPs, blocks PII, FHIR middleware **[partially done, commit 5e423ab: shp-events AuditRecordBuilder (redacts IPs, blocks values in resource) + AuditSink; NOT YET a sink that writes the outbox]** | Audit contains no clinical content; search records only parameter names | 4 | SHR-06 | US-047 |
| ☐ | SHR-11 | Mongo search executor: compile search criteria to query + use index | Query plan uses IXSCAN for main queries | 3 | SHR-10 | ENABLER |
| ☑ | SHR-12 | Idempotency-Key for MongoDB (document service) **[done, commit 3764d57: shp-data-mongo MongoIdempotency + MongoMessaging.inTransaction (retry on conflict); 21 tests against real MongoDB]** | Same key → same result; different body 422 | 2 | SHR-05, SHR-10 | US-035 |
| ☑ | SHR-27 | Authz: emit audit for every decision (allow and deny) **[done, commit 5e423ab: AuthzAudit.fromDecision + interceptor records audit for allow, deny, 5xx errors]** | Every Deny emits audit outcome=denied with a reason code; tests | 2 | SHR-24, SHR-09 | US-047 |
| ☑ | WEB-02 | API client: attach token, Idempotency-Key, If-Match/ETag, map OperationOutcome/Problem Details to messages **[done, commit branch WEB-01-frontend-base: web/src/api/client.ts: token, Accept-Language, Idempotency-Key, If-Match/ETag, OperationOutcome/Problem Details + 409 slot suggestions; tests]** | Show errors per Accept-Language; auto retry login when session expires | 3 | WEB-01 | US-066 |

## Week 5 — 76 hours

| ✓ | ID | Work | Done when | Hours | Depends on | Story |
|---|---|---|---|---|---|---|
| ☑ | AI-05 | Knowledge base API (AI9): upload, approve, replace file, remove; Admin/Department Doctor permission **[done, commit 2b6a50c: AI9: upload, review by department, replace file, remove; index retry 3 times; audit]** | draft → approved → indexed → retired; index error retries 3 times | 4 | AI-04, PY-01 | US-052 |
| ☑ | AI-06 | Hybrid retrieval (RRF, optional cross-encoder rerank per eval) + security trimming by role/department; degrade when embedding/reranker fails **[done, commit cf3ed23: Hybrid BM25 + vector + RRF, query-time trimming; reranker not enabled (waiting for eval AI-16)]** | Unauthorized users never see chunks; filtering tests | 4 | AI-04 | US-053 |
| ☑ | AI-07 | Versioned prompt template + context assembly within token limit **[done, commit cf3ed23: prompts/<task>/v<N>.md, ID recorded in messages; context ≤ 3,500 tokens]** | Prompt version ID recorded in message | 3 | AI-06 | US-053 |
| ☑ | AI-08 | Answer generation + citation-chunk match check + answered=false rule **[done, commit cf3ed23: Verifies quotes match chunks, retries the next model once, no sources means no LLM call]** | No source → do not call LLM to generate content | 4 | AI-07, PY-08 | US-053 |
| ☐ | ARC-14 | Sprint review + planning S2 | Demo, update backlog status and risks; short minutes | 2 |  | ENABLER |
| ☐ | ENV-01 | Workflow env-provision | plan -> infra-<env> approval -> apply -> install Argo CD + bootstrap -> wait for healthy -> seed synthetic data -> smoke test | 6 | IAC-17, GOP-03 | US-070 |
| ☐ | ENV-02 | Workflow env-destroy | Type the environment name correctly + infra-<env>-destroy approval; clean destroy, verify no resources remain | 2 | IAC-17 | US-070 |
| ◐ | GOP-06 | Platform add-on: OpenTelemetry Collector **[partially done, commit gitops 4314a40: opentelemetry-collector 0.177.0: OTLP → Azure Monitor; NOT YET the appinsights Secret (ENV-01)]** | Receives OTLP, exports to Azure Monitor; locally exports to the Grafana stack | 3 | GOP-03 | US-070 |
| ◐ | GOP-07 | Kyverno + policy **[partially done, commit gitops 4314a40: kyverno 3.9.1 + 4 Enforce policies (ACR only, cosign keyless signature, latest banned, resources/probes); NOT YET tested with a real signed image]** | Only signed images from ACR, latest tag banned, resources/probes required, PSS restricted; Enforce | 4 | GOP-03 | US-070 |
| ☑ | GOP-08 | NetworkPolicy default-deny + namespace **[done, commit gitops 4314a40: shp-apps namespace PSA restricted + default-deny NetworkPolicy; services open ingress per values]** | Namespace labelled PSA restricted; only designed flows opened | 2 | GOP-03 | US-070 |
| ☐ | IAC-09 | Module postgresql | Flexible Server 16, Entra auth only (no password), 7-day PITR, zone-redundant HA in prod | 4 | IAC-05 | US-067 |
| ☐ | IAC-10 | Module mongodb (Cosmos DB for MongoDB vCore) | Cluster private endpoint; password generated with random_password and stored in Key Vault, never in code | 4 | IAC-05, IAC-08 | US-067 |
| ☐ | IAC-11 | Module redis | Private endpoint, Entra auth, TLS only | 2 | IAC-05 | ENABLER |
| ☐ | IAC-12 | Module storage | documents + audit containers (WORM immutability), Hot->Cool lifecycle after 30 days, shared key disabled | 4 | IAC-05 | US-048 |
| ☐ | IAC-17 | envs/uat, prod stacks (core: network, AKS, Key Vault, ACR) + sizing | 2 stacks call the modules; tfvars differ in SKU/HA/spot; UAT uses sizing=prod for performance testing; plan runs | 4 | IAC-05, IAC-07, IAC-08 | ENABLER |
| ☑ | ML-06 | Consumer E1/E2/E3/E5/E7: write appointment_fact, update outcome and attendance_stats **[done, commit d5d63c1: E1/E2/E3/E5/E7 consumers in one transaction with the inbox; skip older versions; late cancellation < 24h; merge records]** | Duplicate event ignored; late cancel <24h counts as late_cancel | 4 | SCF-07, PY-03, ML-11 | US-064 |
| ☑ | ML-07 | Score on E1: build features, predict, save prediction, emit no-show-scored via outbox **[done, commit d5d63c1: Scores on E1/E3, stores the prediction with features and version, E10 outbox in the same transaction; no model means no scoring]** | AC-15.2 stores model version and features; same transaction | 4 | ML-06, ML-05 | US-064, US-046 |
| ☑ | ML-08 | API M1 (view prediction), M2 (current model), M3 (internal predict) **[done, commit d5d63c1: M1 (nurse, admin, audited), M2 (admin), M3 (service token, p95 < 100 ms in tests)]** | p95 ≤ 100 ms for M3; correct permissions | 3 | ML-07 | US-063, US-064 |
| ☑ | ML-11 | Bootstrap MongoDB ml (ADR-0014): patient_stats, appointment_facts, predictions (time-series if vCore supports), inbox, outbox **[done, commit d5d63c1: store.ensure_schema idempotent: validator, index, TTL, unique; respects time-series created by the infrastructure script]** | Idempotent script; validator enforces ageBand, score; index (modelVersion, predictedAt) | 2 | SCF-07 | US-064 |
| ☑ | ML-16 | Train/serve skew test: same sample produces matching output on both paths **[done, commit e391985: test_training_features_equal_serving_features: replaying events matches training_frame]** | Test green in CI; skew fails | 3 | ML-15, ML-07 | US-071 |
| ☑ | ML-19 | Drift detector: per-feature PSI and prediction score vs training data **[done, commit 490481e: drift.feature_drift: PSI for main features and prediction score, threshold 0.2 on ≥ 2 features; tests: normal set quiet, shifted set alerts]** | PSI > 0,2 threshold on ≥ 2 main features; test with shifted dataset | 4 | ML-07, ML-21 | US-065 |
| ☑ | ML-20 | Actual performance monitoring: join predictions with real outcomes, 14-day rolling AUC **[done, commit 3a39c95: Joins production predictions with actual outcomes for 14 days, needs ≥ 500 labels]** | Alert when AUC drops > 0,05 and ≥ 500 labelled samples | 4 | ML-06, ML-07 | US-065 |

## Week 6 — 66 hours

| ✓ | ID | Work | Done when | Hours | Depends on | Story |
|---|---|---|---|---|---|---|
| ◐ | AI-09 | Assistant question API (AI1) + SSE stream, reset event on model switch **[partially done, commit cf3ed23: AI1 JSON and SSE; SSE emits after the answer is fully checked (no real token streaming from the provider yet, so first-token p95 ≤ 3 seconds not yet measurable)]** | Retrieval-only mode when all models fail; first-token p95 ≤ 3s | 4 | AI-08, PY-09 | US-053, US-055 |
| ☑ | AI-10 | Input guardrail: length, prompt injection patterns, block with audit **[done, commit cf3ed23: Input guardrail: length, vi/en injection patterns; blocks with audit denied]** | Attack patterns blocked; does not wrongly block normal medical questions | 3 | AI-09 | US-054 |
| ☑ | AI-11 | Output guardrail: schema, no self-issued diagnosis/orders, quotes match chunks **[done, commit cf3ed23: Output guardrail: schema, citations, no direct orders, dosage must exist in the source; prevents data block escape]** | Violation → try next model then answered=false | 3 | AI-09 | US-054 |
| ☑ | AI-12 | Store conversations/messages (TTL 30 days) + feedback and history API (AI2, AI3) **[done, commit cf3ed23: conversations/messages TTL 30 days, feedback (409, PUT), AI3 only for the conversation owner]** | Conversation owner only; duplicate rating 409 | 3 | AI-09 | US-056 |
| ☑ | AI-13 | Track tokens/cost/fallback + usage API (AI10) + alert at 80% budget **[done, commit cf3ed23: usage per call, cost, AI10 per task/model/day, 80% budget alert]** | Report by task/model/day | 3 | PY-09 | US-055 |
| ☑ | AI-14 | RAG eval set part 1: 50 questions + reference answers from sample knowledge base **[done, commit 0235093: 50 questions part 1 from the synthetic knowledge base, schema checked]** | JSON file with schema check | 3 | AI-05 | US-057 |
| ☐ | ARC-15 | Sprint review + planning S3 | Demo, update backlog status and risks; short minutes | 2 |  | ENABLER |
| ☐ | DAT-03 | Seed CLI calls public APIs: local and cloud environment | One command loads sample data; rerun no duplicates | 3 | DAT-02, SCH-14, CLN-05 | ENABLER |
| ☐ | ENV-05 | G4 rehearsal | Sample service goes local -> uat -> prod (ephemeral) through the pipeline only; GitOps revert rollback < 5 minutes; unsigned image blocked; destroy; evidence captured | 4 | ENV-01, LOC-03, LOC-04, GOP-07 | US-070 |
| ☐ | GOP-10 | MLflow chart (tracking + registry) for cluster environments | MLflow runs with durable storage backend, accessed via Workload Identity | 3 | GOP-03 | US-063 |
| ☐ | IAC-13 | Module service bus | Namespace with local auth disabled; RBAC for services | 2 | IAC-05 | ENABLER |
| ☐ | IAC-14 | Module ai-search | API key disabled (RBAC only), SKU from environment variable | 2 | IAC-05 | ENABLER |
| ☐ | IAC-15 | Module workload identity for services | One UAMI per service + federated credential (namespace/serviceaccount) + role to the correct data store | 4 | IAC-07 | ENABLER |
| ☐ | IAC-16 | Infrastructure for Keycloak (ADR-0017) | Dedicated PostgreSQL for IAM, secret in Key Vault, /idp path through the Gateway, NetworkPolicy allows only the gateway and admin IPs | 4 | IAC-01 | US-008, US-066 |
| ☐ | IAC-20 | Wire the data and identity modules (IAC-09..IAC-16) into both stacks | plan runs for both environments; each service has access only to its own data store | 4 | IAC-17, IAC-12, IAC-15 | US-070 |
| ☑ | ML-09 | Reload model on production alias change within ≤ 60 seconds without deploy **[done, commit d5d63c1: ModelHolder polls the alias every 30 seconds, swaps the model atomically; registry failure keeps the old model]** | Alias change test | 2 | ML-08 | US-063 |
| ☑ | ML-12 | Contract test: consume E1/E5 and emit E10; ML failure does not affect reminders **[done, commit d5d63c1: Test: E10 matches the schema; with no model, no E10 is emitted and no error]** | Green in CI; AC-15.1 has test in notification | 2 | ML-08, SHR-06 | US-064, US-046 |
| ☑ | ML-22 | Retraining trigger logic: conditions, 7-day cooldown, ≥ 500 new labelled samples, no overlapping runs **[done, commit 490481e: drift.decide_retrain: drift or AUC drop, 7-day cooldown, ≥ 500 new labels, overlap prevention, at most 2 runs/14 days then wait for a human; 24-month sliding window]** | Test cases: drift, AUC drop, in cooldown, insufficient samples | 3 | ML-19, ML-20 | US-074 |
| ☑ | ML-23 | Retraining job: train → data check → quality gate → register candidate alias, never auto-switch production **[done, commit 3a39c95: retrain.run_pending: lock, Parquet snapshot with hash, gated pipeline, candidate only, audit; end-to-end test with shifted data]** | Stops and keeps production when gate fails; every run has links and audit | 4 | ML-22, ML-05, ML-14, ML-17 | US-074 |
| ☑ | ML-24 | Shadow mode in serving: candidate model scores in parallel, record only, do not use results **[done, commit d5d63c1: Shadow: candidate scores in parallel, shadow predictions stored, agreement metric; E10 always from production]** | No user-side latency increase; agreement metric with running model | 3 | ML-09 | US-073 |
| ◐ | ML-25 | Export metrics for canary analysis: error rate, p95, score distribution, agreement, version label **[partially done, commit d5d63c1: Per-model_version metrics: latency, errors, score distribution, highRisk, agreement; AnalysisTemplate (MLO-03) NOT YET]** | Prometheus has metrics per model_version for AnalysisTemplate | 3 | ML-08 | US-073 |
| ◐ | WEB-10 | Pipeline + chart for the web app **[partially done, commit branch WEB-01-frontend-base; shp-gitops branch WEB-10-web-app; templates v0.1.5: Non-root nginx Dockerfile (Trivy 0 HIGH/CRITICAL), svc-web.yml + node-ci, chart pathPrefixes/configFiles, uat/prod values; NOT YET real pipeline run and not yet deployed to the cluster]** | Non-root nginx container using the shared chart | 2 | WEB-01 | US-066 |

## Week 7 — 81 hours

| ✓ | ID | Work | Done when | Hours | Depends on | Story |
|---|---|---|---|---|---|---|
| ☑ | AI-15 | RAG eval set part 2: add 50 questions (some without answers in documents) **[done, commit 0235093: 100 questions total, 18 without an answer]** | Total ≥ 100 questions, ≥ 15 unanswerable | 3 | AI-14 | US-057 |
| ☑ | AI-16 | Eval harness: Recall@5, MRR, groundedness, citation correctness, correct-refusal rate **[done, commit 0235093: Harness Recall@5, MRR, groundedness, citations, refusals; CI gate; used to tune the relevance threshold]** | Run one command, export Markdown + JSON report | 4 | AI-15 | US-057 |
| ☑ | AI-17 | Prompt injection attack set 50 samples (in questions and in documents) + runner **[done, commit 0235093: 50 attack samples (questions + embedded in documents), 0 escapes is the CI condition]** | 0 bypasses is the CI pass condition | 4 | AI-11 | US-054 |
| ☑ | AI-19 | Gather data for summary via OBO: timeline, comorbidities, allergies, abnormal results; number sources **[done, commit 67662f2: Collected with an OBO token (timeline, Condition, Allergy, abnormal Observation); real clinical does not exist yet, tested with fake HTTP per contract 09]** | No permission → 403 before data enters prompt | 4 | PY-02, CLN-23 | US-058 |
| ☑ | AI-20 | Prompt + output schema + source validator (reject claims without valid source) **[done, commit 67662f2: Prompt + schema + source checking, numbers must exist in the source]** | Every claim has sources pointing to real records | 4 | AI-19, PY-08 | US-058 |
| ☐ | ARC-08 | STRIDE threat model | Threat table per data flow; mitigations trace back to NFR-SEC | 6 | ARC-04 | ENABLER |
| ☐ | ARC-12 | Test strategy | Test levels, coverage thresholds, load/failure test scenarios mapped to NFRs | 4 | ARC-02 | ENABLER |
| ☐ | ARC-16 | Sprint review + planning S4 | Demo, update backlog status and risks; short minutes | 2 |  | ENABLER |
| ☐ | CIT-09 | Versioned template releases | vX.Y.Z tags on shp-platform-templates; charts pushed as OCI to ACR; CHANGELOG | 2 | CIT-01, GOP-01 | US-070 |
| ☐ | CIT-10 | Internal Maven packaging for shared libraries | Libraries published to GitHub Packages (Maven) with versions; services reference by version | 3 | SHR-01 | US-070 |
| ☐ | GOP-02 | Unit tests for the chart | helm-unittest for the main values combinations; runs in CI | 3 | GOP-01 | US-070 |
| ☐ | GOP-09 | Prod canary with Argo Rollouts | Steps 10%-50%-100%, pauses for manual promotion, automatic rollback on failure | 3 | GOP-04 | US-070 |
| ☐ | GOV-06 | Dependabot for actions, maven, pip, docker, terraform | dependabot.yml in every repo; actions pinned by commit SHA | 2 | GOV-01 | US-070 |
| ☐ | IAC-18 | Module Defender for Storage + Event Grid → queue for scan results | New blobs are scanned; results go to a queue for the document service to process | 3 | IAC-12 | US-036 |
| ☐ | IAC-19 | ai node pool running Ollama (bge-m3, qwen2.5) + DeepSeek/Gemini API key secret in Key Vault | Ollama has a model cache PVC; keys only in Key Vault, loaded via CSI, never in the repo/values | 4 | IAC-05 | US-055 |
| ☐ | IAM-01 | Keycloak on the cluster (ADR-0017): chart, GitOps app, shp realm as code | Chart + GitOps app, 2 replicas in prod; realm has shp-web PKCE client, shp-api audience, roles; rebuilt from code | 4 | IAC-16, GOP-03 | US-001, US-008, US-066 |
| ☐ | IAM-02 | Service accounts: client credentials for /internal per service | Client auth method finalized (ServiceAccount token assertion or 90-day rotating secret); tested against real Keycloak | 3 | IAM-01, SHR-20 | US-053, US-066 |
| ☐ | IAM-03 | Keycloak theme vi/en: login, forgot password | Matches the SHP design system; meets WCAG AA; does not reveal existing accounts | 3 | IAM-01 | US-001, US-008 |
| ☐ | IAM-06 | Hardened Keycloak: Argon2id, password policy, brute-force lockout, internal admin console, backups | Admin console not exposed to the Internet; DB restore from backup tested successfully | 4 | IAM-01 | US-001, US-008, US-066 |
| ☐ | IAM-07 | RFC 8693 token exchange for ai (on behalf of the user) | Exchanged token keeps the user sub and correct target service aud; other clients cannot exchange | 3 | IAM-02 | US-053, US-066 |
| ☐ | IAM-08 | Sign-up page with phone number or email, terms of use | Sign-up does not reveal existing accounts; Vietnamese by default | 3 | IAM-03 | US-001, US-008 |
| ☐ | OBS-01 | Standard RED dashboard for every service | Grafana dashboard by service label: rate, errors, duration p50/p95/p99 | 3 | GOP-06 | ENABLER |
| ☐ | OBS-02 | SLOs + burn-rate alerts | SLOs for NFR-A1, P1, P2; multi-window burn-rate alerts | 3 | OBS-01 | ENABLER |
| ☐ | WEB-30 | Assistant UI: ask, stream, clickable citations, rating | Shows retrieval-only mode; citations open correct section | 4 | WEB-02, AI-09 | US-053, US-056 |

## Week 8 — 69 hours

| ✓ | ID | Work | Done when | Hours | Depends on | Story |
|---|---|---|---|---|---|---|
| ◐ | AI-18 | Run eval for each model in fallback chain and produce comparison report **[partially done, commit 0235093: --live runs each model in the chain and reports per model; NOT YET run because it needs DeepSeek/Gemini API keys (IAC-19)]** | Per-model report (NFR-AI7) | 3 | AI-16, IAC-19 | US-057, US-055 |
| ☑ | AI-21 | Summary API (AI4): Idempotency-Key, 10-minute cache, AI label, structured-timeline fallback **[done, commit 67662f2: AI4: Idempotency-Key, 10-minute cache, structured-timeline fallback, TREAT/ETREAT audit]** | All models failing still returns timeline; has 'AI-generated' label (AC-12.3) | 4 | AI-20 | US-058 |
| ☐ | AI-22 | Extraction job model + consumer document-uploaded + state machine + retry/DLQ | unique (documentId, version); retry 1/5/15 minutes → failed | 4 | PY-03, DOC-05 | US-059 |
| ☐ | AI-23 | OCR step with Tesseract (local); text stored in Blob | Fallback when service fails; test with sample documents | 4 | AI-22, DOC-10 | US-059 |
| ☐ | AI-24 | Classification + field extraction by LLM per schema + value/unit validator + confidence | overallConfidence = min of required fields; threshold 0,85 | 4 | AI-23, PY-08 | US-059 |
| ☐ | ARC-17 | Sprint review + planning S5 | Demo, update backlog status and risks; short minutes | 2 |  | ENABLER |
| ☐ | ENV-03 | Workflow cost-guard | Runs on a schedule; lists environments exceeding TTL; opens/updates a warning issue (no auto-destroy) | 2 | IAC-04 | US-070 |
| ☐ | ENV-04 | Environment lifecycle runbook | Guides provision/test/destroy, expected durations, common troubleshooting | 2 | ENV-01, ENV-02 | US-070 |
| ☐ | IAM-04 | OTP authenticator (Keycloak SPI) calling notification N8, N9 on sign-up | Verify phone (Zalo first, SMS later) or email ownership with a code; code expires in 5 minutes, single use | 4 | IAM-01 | US-001 |
| ☐ | IAM-05 | Identity events to audit | Successful/failed login, sign-up, account lock, password/phone change emit audit.recorded; no passwords or OTP codes recorded | 4 | IAM-01, SHR-09 | US-008, US-031 |
| ☐ | IAM-09 | OTP for phone/email changes, rate limits per phone and IP, fake provider for dev and tests | Over-limit requests rejected; no codes or phone numbers in logs; tested with fake notification | 4 | IAM-04, NTF-16 | US-001 |
| ☐ | MLO-01 | ML training pipeline in CI + model canary deploy | Runs when data/code changes; model artifact; canary deploy | 4 | ML-05 | US-063 |
| ☐ | MLO-02 | Reproducible training image: pinned library versions, seeds, fixed image, vulnerability scan | Same image + dataset hash + seed produce the same model; image signed | 3 | MLO-01 | US-071 |
| ☐ | MLO-03 | Argo Rollouts canary for ml-service + AnalysisTemplate (Prometheus), auto-promote or rollback | 10%, 50%, 100% steps with analysis; bad model rolls back automatically; autoPromote configured per environment | 6 | GOP-04, SCF-07, ML-25 | US-073 |
| ☐ | MLO-04 | Retraining orchestration on the cluster: CronJob/KEDA receives requests from ML-22, runs ML-23, monitors and audits | A simulated drift alert starts exactly one retraining run; no overlapping runs | 5 | ML-23, GOP-10 | US-074 |
| ☐ | OBS-03 | DLQ and pipeline failure alerts | Alerts when a dead-letter queue has messages, a cert is expiring, or an Argo CD app is degraded | 2 | OBS-01 | ENABLER |
| ☐ | PRD-10 | k6 script: find slot + book (1x/5x/10x scenarios) | 1x/5x/10x scenarios per workload analysis; runs on UAT (prod-sized) | 4 | SCH-14 | US-068 |
| ☐ | PRD-11 | KEDA cron scale before 07:00 + HPA for scheduling | prod/uat values scale before the slot-opening window | 1 | SCF-01, GOP-04 | US-068 |
| ☐ | WEB-31 | Knowledge base management and AI cost UI (Admin) | Upload, approve, remove; usage table | 4 | WEB-30, AI-13 | US-053, US-052 |
| ☐ | WEB-32 | Summary panel for record: AI label, clickable sources to records | Shows structured-timeline mode when LLM fails | 3 | WEB-21, AI-21 | US-058 |

## Week 9 — 42 hours

| ✓ | ID | Work | Done when | Hours | Depends on | Story |
|---|---|---|---|---|---|---|
| ☐ | AI-25 | Extraction list and detail API (AI5) with field positions to highlight on document | Filter by status/patient; Care/Nurse permission | 3 | AI-24 | US-059, US-060 |
| ☐ | AI-26 | Approve API (AI6): create Observation via OBO after user confirms; record editedFields | No data enters record before confirmation; clinical error returned verbatim | 4 | AI-25, PY-02, CLN-17 | US-060 |
| ☐ | AI-27 | Labelled synthetic document set for extraction (lab results, prescriptions, referrals) | ≥ 60 documents with field labels | 4 | AI-24 | US-061 |
| ☑ | AI-29 | Booking bot: 4 tools, parameter schema, max 6 turns and 10-second timeout **[done, commit 4b3dbcd: 4 tools, schema checks parameters, loop capped at 6 turns/10 seconds]** | Unknown tool rejected; bad parameters blocked | 4 | PY-08, PY-02 | US-062 |
| ☑ | AI-30 | pending_action + confirmation API (AI8): call $book/$cancel with patient token, 5-minute expiry **[done, commit 4b3dbcd: pending_actions TTL 5 minutes; AI8 confirm calls $book/$cancel with the patient token, 409 suggests retry]** | No appointment before confirmation (AC-14.1); 409 → suggest again | 4 | AI-29, SCH-14 | US-062 |
| ☑ | AI-31 | Booking chat API (AI7) + fallback to form when all models fail **[done, commit 4b3dbcd: AI7 JSON; any model failure returns 503 ai-unavailable with a booking-form fallback]** | 503 ai-unavailable with fallback (AC-14.3) | 3 | AI-30 | US-062 |
| ☐ | AI-32 | Authz matrix and event contract tests for ai-service | Green in CI; non-Care doctor cannot get data via AI | 4 | AI-31, AI-26 | US-052, US-053, US-058, US-060, US-062 |
| ☐ | ARC-18 | Sprint review + planning S6 | Demo, update backlog status and risks; short minutes | 2 |  | ENABLER |
| ☑ | ML-10 | Daily drift job runs detector (ML-19, ML-20), exports metric and alerts **[done, commit 3a39c95: monitor.run_daily: 7-day PSI, 14-day AUC, Prometheus metrics, creates retraining requests; 02:00 VN thread or CronJob]** | Actual PSI/AUC metrics in Prometheus; alert when above threshold | 3 | ML-19, ML-20 | US-065 |
| ☐ | MLO-05 | Drift and actual-AUC alerts (Alertmanager) + MLOps dashboard panels | Alerts reach the right channel; dashboard shows PSI, actual AUC, model version, retraining history | 3 | ML-10, OBS-01 | US-065 |
| ☐ | WEB-33 | Extraction review screen: highlight fields on source document, edit, accept/reject | Low-confidence fields highlighted | 4 | WEB-25, AI-26 | US-059, US-060 |
| ☐ | WEB-34 | Booking chatbot widget with confirmation step (Could) | Shows form fallback on 503 | 4 | WEB-04, AI-31 | US-062 |

## Week 10 — 26 hours

| ✓ | ID | Work | Done when | Hours | Depends on | Story |
|---|---|---|---|---|---|---|
| ☐ | AI-28 | Field-level and classification F1 eval + report | F1 ≥ 0,85 or explanation | 3 | AI-27 | US-061 |
| ☐ | ENV-06 | First ephemeral prod provisioning rehearsal: measure provision/destroy times, seed data, fallback plan if provisioning is late | Actual timing measurements; fallback assets (video/screenshots/UAT); prod cleanly destroyed after the rehearsal | 4 | ENV-01, ENV-02, ENV-05 | US-070 |
| ☐ | PRD-01 | 1x/5x/10x load test + report | Runs on ephemeral UAT (prod-sized); compared against NFR-P/S | 6 | PRD-10, ENV-01 | US-068 |
| ☐ | PRD-02 | Failure test | Kill pod, DB failover, SMS provider failure, LLM failure, consumer crash; record results | 6 | ENV-01 | US-069 |
| ☐ | PRD-03 | Backup/restore rehearsal | Postgres PITR + Mongo restore; record actual times vs RTO | 4 | ENV-01 | US-067 |
| ☐ | PRD-04 | Final SLO dashboard | Error budget per NFR-A1 | 3 | OBS-02 | ENABLER |

## Week 11 — 11 hours

| ✓ | ID | Work | Done when | Hours | Depends on | Story |
|---|---|---|---|---|---|---|
| ☑ | AI-33 | Token and cost report by task/model **[done, commit 0235093: python -m shp_ai.report_usage: by task/model/day, days hitting 80%]** | Numbers from AI10; compared with NFR-C2 | 3 | AI-13, PRD-01 | US-055 |
| ☐ | PRD-05 | FinOps report | Cost per environment-hour, monthly forecast, recommendations | 4 | ENV-03 | ENABLER |
| ☐ | PRD-06 | Security review | Checklist against the threat model; scan results; authorization matrix green | 4 | ARC-08 | ENABLER |

## Week 12 — 9 hours

| ✓ | ID | Work | Done when | Hours | Depends on | Story |
|---|---|---|---|---|---|---|
| ☐ | PRD-08 | Demo script + defense slides | Answer the 12 review questions from the assignment; end-to-end demo | 6 | PRD-01..PRD-07 | ENABLER |
| ☐ | PRD-09 | Demo rehearsal on ephemeral prod | Provision prod, run the script, destroy; timings captured | 3 | PRD-08 | ENABLER |
