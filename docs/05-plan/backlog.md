# Detailed backlog

> Generated automatically by `python3 -I tools/plan/build.py` from `tools/plan/tasks_sa.py`, `tasks_dev.py`, `stories.py`. Do not edit by hand. Import into GitHub Projects with [backlog.csv](backlog.csv). Work per person: [by-person](by-person/).

Total: **347 tasks**, **1195 hours** · dev: each task ≤ 4 hours, SA: ≤ 8 hours.

## Remaining load per person and per week (hours; completed tasks excluded)

| Week | SA | D1 | D2 | D3 |
|---|---|---|---|---|
| W1 | 30 |  |  |  |
| W2 | **49** |  |  | 7 |
| W3 | **61** |  | 27 |  |
| W4 | **92** | 30 | 29 | 27 |
| W5 | 35 | 30 | 30 | 29 |
| W6 | 37 | 30 | 30 | 30 |
| W7 | **62** | 30 | 30 | 30 |
| W8 | **65** | 4 | 25 | 8 |
| W9 | 28 |  |  |  |
| W10 | 26 | 4 |  |  |
| W11 | 8 |  |  |  |
| W12 | 9 |  |  |  |
| **Total** | **502** | **128** | **171** | **131** |

Bold = above the warning threshold (dev > 30h/week, SA > 45h/week).

## Sprint schedule

| Code | Period |
|---|---|
| S0 | W1 26/10-01/11: finalize gates G1-G3, create repos, onboarding |
| P1 | W2 02/11-08/11: platform 1; dev builds shared libraries + synthetic data |
| P2 | W3 09/11-15/11: platform 2; dev builds shared libraries |
| P3 | W4 16/11-22/11: platform 3 (parallel with S1) |
| P4 | W5 23/11-29/11: identity, ephemeral environments, G4 (parallel with S2) |
| S1 | W4 16/11-22/11: dev starts services |
| S2 | W5 23/11-29/11 |
| S3 | W6 30/11-06/12 |
| S4 | W7 07/12-13/12 |
| S5 | W8 14/12-20/12 |
| S6 | W9 21/12-27/12 |
| H1 | W10 28/12-03/01: hardening, load/failure test |
| H2 | W11 04/01-10/01: eval, FinOps, security |
| DEF | W12 11/01-17/01: defense preparation |

## ARC — Architecture & project governance

| ID | Story | Task | Done when | Owner | Hours | Depends on | Sprint | Traceability |
|---|---|---|---|---|---|---|---|---|
| ARC-01 | ENABLER | G1 sign-off: business analysis | Scope, KPIs and business rules signed off by the team; doc status = Approved | SA | 2 |  | S0 | docs/01 |
| ARC-02 | ENABLER | G2 sign-off: finalize NFRs and MoSCoW | Every NFR has a number + measurement method; Must/Should/Could list finalized | SA | 3 | ARC-01 | S0 | docs/02 |
| ARC-03 | ENABLER | G3 sign-off: move ADR 0001-0010 to Accepted | Each ADR traces back to FR/NFR; status Accepted | SA | 3 | ARC-02 | S0 | ADR |
| ARC-04 | ENABLER | C4 Context and Container diagrams | 2 diagrams (Mermaid/PlantUML) in docs/03, matching the service catalog | SA | 4 | ARC-03 | S0 | P1 |
| ARC-05 | ENABLER | Data model per service | FHIR resource + SearchParameter tables each service must support; data ownership clear | SA | 6 | ARC-03 | S0 | ADR-0003, ADR-0008 |
| ARC-09 | ENABLER | Coding standards & conventions for services | Document: directory structure, errors, PII-free logging, tracing, configuration, API versioning | SA | 4 | ARC-03 | S0 | NFR-O4 |
| ARC-10 | ENABLER | Definition of Ready/Done, PR and branch workflow | Document in docs/00; PR template reflects the DoD | SA | 2 | ARC-03 | S0 | NFR-D1 |
| ARC-11 | ENABLER | Synthetic data specification | Rules to generate Vietnamese patients, doctors, schedules and encounters; no real data used | SA | 3 | ARC-05 | S0 | NFR-SEC9 |
| ARC-19 | ENABLER | Create the full contracts/authz/matrix.yaml from the authorization matrix | Every matrix cell has a rule row; YAML schema validated; dev uses it as input for SHR-22 | SA | 4 | ARC-03 | P1 | 15-authorization §3 |
| ARC-06 | ENABLER | Skeleton API contracts (OpenAPI) for 6 services | openapi.yaml per service with endpoints and OperationOutcome error codes; lint passes | SA | 6 | ARC-05 | P2 | P2 |
| ARC-07 | ENABLER | Event contracts (JSON Schema) | Schemas for AppointmentBooked/Cancelled, DocumentUploaded, AuditRecorded, NoShowScored; versioned | SA | 4 | ARC-05 | P2 | P2 |
| ARC-20 | ENABLER | Reference data: ICD-10 (~300 codes), LOINC for vital signs/lab tests, CodeSystem for specialties and cancellation reasons | JSON files in contracts/terminology, loader script, used by CLN-16/CLN-18 | SA | 4 | ARC-03 | P2 | 01-domain-model §3 |
| ARC-13 | ENABLER | Sprint review + planning S1 | Demo, update backlog status and risks; short minutes | SA | 2 |  | S1 | PM |
| ARC-14 | ENABLER | Sprint review + planning S2 | Demo, update backlog status and risks; short minutes | SA | 2 |  | S2 | PM |
| ARC-15 | ENABLER | Sprint review + planning S3 | Demo, update backlog status and risks; short minutes | SA | 2 |  | S3 | PM |
| ARC-08 | ENABLER | STRIDE threat model | Threat table per data flow; mitigations trace back to NFR-SEC | SA | 6 | ARC-04 | S4 | P3 |
| ARC-12 | ENABLER | Test strategy | Test levels, coverage thresholds, load/failure test scenarios mapped to NFRs | SA | 4 | ARC-02 | S4 | P4 |
| ARC-16 | ENABLER | Sprint review + planning S4 | Demo, update backlog status and risks; short minutes | SA | 2 |  | S4 | PM |
| ARC-17 | ENABLER | Sprint review + planning S5 | Demo, update backlog status and risks; short minutes | SA | 2 |  | S5 | PM |
| ARC-18 | ENABLER | Sprint review + planning S6 | Demo, update backlog status and risks; short minutes | SA | 2 |  | S6 | PM |

## GOV — Repos, workflow & GitHub governance

| ID | Story | Task | Done when | Owner | Hours | Depends on | Sprint | Traceability |
|---|---|---|---|---|---|---|---|---|
| GOV-01 | US-070 | Create 4 repos per ADR-0010 | 4 repos exist, README describes responsibilities, visibility correct | SA | 1 | ARC-03 | S0 | ADR-0010 |
| GOV-02 | US-070 | Main branch protection ruleset for 4 repos | PR required, 1 review, green CI, force-push blocked; GitOps bot bypasses in shp-gitops | SA | 2 | GOV-01 | S0 | ADR-0009 |
| GOV-03 | US-070 | CODEOWNERS + PR template + issue template | PRs to infra/gitops-prod need SA approval; templates include a DoD checklist | SA | 2 | GOV-01 | S0 | NFR-D1 |
| GOV-07 | US-070 | GitHub Project + backlog import | Project board has Todo/In progress/Review/Done columns and Sprint/Owner/Estimate fields; backlog.csv imported | SA | 2 | GOV-01 | S0 | PM |
| GOV-05 | US-070 | GitHub App bot for GitOps | App has only contents:write on shp-gitops; secret stored as org/repo secret | SA | 2 | GOV-01 | P2 | ADR-0010 |
| GOV-04 | US-070 | GitHub Environments and reviewers | deploy-uat, deploy-prod, infra-<env>, infra-<env>-destroy (env = uat, prod) have reviewers and wait timers per ADR-0009 | SA | 2 | GOV-01 | P3 | ADR-0009 |
| GOV-08 | US-070 | Translate documentation, contracts and the shared Python library to English | docs/, contracts/ and the tools/ sources contain no Vietnamese prose; the backlog, SRS, trace and contract checks are green and the service copies match libs/python | SA | 8 | GOV-07 | S1 | ARC-09 |
| GOV-09 | US-070 | Translate the Java services, web app and dev scripts to English | Comments, docstrings and developer-facing text in libs/java, services/, web/, dev/ and tests/ are English; product data stays Vietnamese (vi locale, Vietnamese test corpora); linters, tests and the web build pass | SA | 8 | GOV-08 | S1 | ARC-09 |
| GOV-10 | US-070 | Convert GitHub issues, pull requests and comments to English | Every issue, PR title/body and comment in the 4 repos is English; task and user-story bodies match the English backlog; no Vietnamese left in GitHub records | SA | 8 | GOV-08 | S1 | ADR-0009 |
| GOV-11 | US-070 | Translate the infra, GitOps and templates repositories to English | Comments, descriptions, READMEs and templates in shp-infra, shp-gitops and shp-platform-templates contain no Vietnamese prose; terraform fmt/validate, the GitOps validate script and the chart checks are green | SA | 4 | GOV-10 | S1 | ARC-09 |
| GOV-12 | US-070 | Definition of Done audit of every completed task | docs/05-plan/dod-audit.md gives a verdict with evidence for every task marked Done, lists each gap with a proposed fix, and traces the acceptance criteria to the test catalog | SA | 8 | GOV-11 | S1 | ARC-10 |
| GOV-06 | US-070 | Dependabot for actions, maven, pip, docker, terraform | dependabot.yml in every repo; actions pinned by commit SHA | SA | 2 | GOV-01 | S4 | NFR-SEC6 |

## IAC — Azure infrastructure with Terraform

| ID | Story | Task | Done when | Owner | Hours | Depends on | Sprint | Traceability |
|---|---|---|---|---|---|---|---|---|
| IAC-01 | ENABLER | Bootstrap stack: shared RG, state storage | Storage account with versioning, soft delete, shared key disabled; state accessed via Entra ID | SA | 3 | GOV-01 | P1 | NFR-D3 |
| IAC-02 | ENABLER | Bootstrap: Azure Container Registry | ACR admin user disabled; retention policy; login server output | SA | 2 | IAC-01 | P1 | ADR-0009 |
| IAC-03 | ENABLER | Bootstrap: OIDC identity for GitHub | Identity plan (read), apply-<env>, acr-push; federated credentials per repo + environment; least-privilege RBAC | SA | 4 | IAC-01 | P1 | NFR-SEC4 |
| IAC-05 | ENABLER | Module network | VNet, AKS/private endpoint/Postgres (delegated) subnets, private DNS zones; validate + tflint clean | SA | 5 | IAC-01 | P2 | NFR-SEC7 |
| IAC-06 | ENABLER | Module monitoring | Log Analytics, Azure Monitor workspace (Prometheus), Managed Grafana, App Insights | SA | 4 | IAC-05 | P2 | NFR-O1, O2 |
| IAC-07 | ENABLER | Module aks | Azure CNI overlay + Cilium, Workload Identity, KV CSI, Azure RBAC, local accounts disabled, 3 node pools, 3 zones | SA | 8 | IAC-05, IAC-06 | P2 | ADR-0004 |
| IAC-04 | ENABLER | Bootstrap: budget alert + tag policy | Budget alerts at 50/80/100%; shp-env, created-at, ttl-hours tags required | SA | 2 | IAC-01 | P3 | NFR-C1, C3 |
| IAC-08 | ENABLER | Module key vault | RBAC mode, private endpoint, purge protection only in prod, name with random suffix | SA | 2 | IAC-05 | P3 | NFR-SEC4 |
| IAC-09 | US-067 | Module postgresql | Flexible Server 16, Entra auth only (no password), 7-day PITR, zone-redundant HA in prod | SA | 4 | IAC-05 | P4 | ADR-0008, NFR-A4 |
| IAC-10 | US-067 | Module mongodb (Cosmos DB for MongoDB vCore) | Cluster private endpoint; password generated with random_password and stored in Key Vault, never in code | SA | 4 | IAC-05, IAC-08 | P4 | ADR-0008 |
| IAC-11 | ENABLER | Module redis | Private endpoint, Entra auth, TLS only | SA | 2 | IAC-05 | P4 | ADR-0008 |
| IAC-12 | US-048 | Module storage | documents + audit containers (WORM immutability), Hot->Cool lifecycle after 30 days, shared key disabled | SA | 4 | IAC-05 | P4 | NFR-A5, SEC5 |
| IAC-17 | ENABLER | envs/uat, prod stacks (core: network, AKS, Key Vault, ACR) + sizing | 2 stacks call the modules; tfvars differ in SKU/HA/spot; UAT uses sizing=prod for performance testing; plan runs | SA | 4 | IAC-05, IAC-07, IAC-08 | P4 | NFR-D4 |
| IAC-13 | ENABLER | Module service bus | Namespace with local auth disabled; RBAC for services | SA | 2 | IAC-05 | S3 | ADR-0001 |
| IAC-14 | ENABLER | Module ai-search | API key disabled (RBAC only), SKU from environment variable | SA | 2 | IAC-05 | S3 | ADR-0008 |
| IAC-15 | ENABLER | Module workload identity for services | One UAMI per service + federated credential (namespace/serviceaccount) + role to the correct data store | SA | 4 | IAC-07 | S3 | NFR-SEC4 |
| IAC-16 | US-008, US-066 | Infrastructure for Keycloak (ADR-0017) | Dedicated PostgreSQL for IAM, secret in Key Vault, /idp path through the Gateway, NetworkPolicy allows only the gateway and admin IPs | SA | 4 | IAC-01 | S3 | UC-08, NFR-SEC1, ADR-0017 |
| IAC-20 | US-070 | Wire the data and identity modules (IAC-09..IAC-16) into both stacks | plan runs for both environments; each service has access only to its own data store | SA | 4 | IAC-17, IAC-12, IAC-15 | S3 | NFR-D4 |
| IAC-18 | US-036 | Module Defender for Storage + Event Grid → queue for scan results | New blobs are scanned; results go to a queue for the document service to process | SA | 3 | IAC-12 | S4 | 05-storage §2 |
| IAC-19 | US-055 | ai node pool running Ollama (bge-m3, qwen2.5) + DeepSeek/Gemini API key secret in Key Vault | Ollama has a model cache PVC; keys only in Key Vault, loaded via CSI, never in the repo/values | SA | 4 | IAC-05 | S4 | ADR-0006 |

## CIT — CI templates (GitHub Actions)

| ID | Story | Task | Done when | Owner | Hours | Depends on | Sprint | Traceability |
|---|---|---|---|---|---|---|---|---|
| CIT-01 | US-070 | Reusable workflow Java CI (Maven) | Spotless check, build -Werror, tests + JaCoCo coverage threshold, vulnerable dependency scan; usable from other repos | SA | 4 | GOV-01 | P1 | ADR-0009 |
| CIT-03 | US-070 | Reusable workflow container | hadolint, buildx build, Trivy blocks High/Critical, SBOM, cosign keyless signing, ACR push only on main, digest output | SA | 5 | IAC-02, IAC-03 | P1 | NFR-SEC6 |
| CIT-05 | US-070 | Reusable workflow Terraform checks + plan | fmt, validate, tflint, Trivy config, plan + summary in the job summary; plan via read-only OIDC | SA | 4 | IAC-03 | P2 | NFR-D3 |
| CIT-06 | US-070 | Reusable workflow GitOps update/promote | Update digest in shp-gitops; uat commits after approval; prod opens a PR after approval | SA | 4 | GOV-05 | P2 | ADR-0009 |
| CIT-02 | US-070 | Reusable workflow Python CI | ruff lint+format, mypy, pytest + coverage, pip-audit | SA | 3 | GOV-01 | P3 | ADR-0009 |
| CIT-04 | US-070 | Reusable workflow Helm validate | helm lint, render per environment, kubeconform strict, Trivy config | SA | 3 | GOP-01 | P3 | ADR-0009 |
| CIT-07 | US-070 | Shared check workflow for every PR | gitleaks, actionlint, dependency review, CodeQL (java-kotlin, python, actions) | SA | 3 | GOV-01 | P3 | NFR-SEC6 |
| CIT-08 | US-070 | Sample caller workflow for services + generator script | Script creates svc-<name>.yml from the template with correct path filters | SA | 2 | CIT-01, CIT-02, CIT-03, CIT-06 | P3 | ADR-0009 |
| CIT-09 | US-070 | Versioned template releases | vX.Y.Z tags on shp-platform-templates; charts pushed as OCI to ACR; CHANGELOG | SA | 2 | CIT-01, GOP-01 | S4 | ADR-0010 |
| CIT-10 | US-070 | Internal Maven packaging for shared libraries | Libraries published to GitHub Packages (Maven) with versions; services reference by version | SA | 3 | SHR-01 | S4 | ADR-0010 |

## GOP — Helm & GitOps (Argo CD)

| ID | Story | Task | Done when | Owner | Hours | Depends on | Sprint | Traceability |
|---|---|---|---|---|---|---|---|---|
| GOP-01 | US-070 | Shared microservice Helm chart | Deployment/Rollout, Service, HPA/KEDA, PDB, NetworkPolicy, HTTPRoute, ServiceAccount WI, SecretProviderClass; values.schema.json | SA | 8 | ARC-09 | P1 | ADR-0004 |
| GOP-03 | US-070 | Argo CD bootstrap chart | AppProject, ApplicationSet for platform + services per environment; app-of-apps | SA | 6 | GOP-01 | P2 | ADR-0009 |
| GOP-04 | US-070 | Platform add-on: Argo Rollouts, KEDA, cert-manager | Application pins chart versions; values per environment | SA | 3 | GOP-03 | P3 | NFR-A7, S1 |
| GOP-05 | US-070 | Platform add-on: Envoy Gateway (Gateway API) | GatewayClass/Gateway, TLS via cert-manager | SA | 3 | GOP-04 | P3 | NFR-SEC3 |
| GOP-06 | US-070 | Platform add-on: OpenTelemetry Collector | Receives OTLP, exports to Azure Monitor; locally exports to the Grafana stack | SA | 3 | GOP-03 | P4 | NFR-O1 |
| GOP-07 | US-070 | Kyverno + policy | Only signed images from ACR, latest tag banned, resources/probes required, PSS restricted; Enforce | SA | 4 | GOP-03 | P4 | NFR-SEC6 |
| GOP-08 | US-070 | NetworkPolicy default-deny + namespace | Namespace labelled PSA restricted; only designed flows opened | SA | 2 | GOP-03 | P4 | NFR-SEC7 |
| GOP-10 | US-063 | MLflow chart (tracking + registry) for cluster environments | MLflow runs with durable storage backend, accessed via Workload Identity | SA | 3 | GOP-03 | S3 | ADR-0007 |
| GOP-02 | US-070 | Unit tests for the chart | helm-unittest for the main values combinations; runs in CI | SA | 3 | GOP-01 | S4 | ADR-0009 |
| GOP-09 | US-070 | Prod canary with Argo Rollouts | Steps 10%-50%-100%, pauses for manual promotion, automatic rollback on failure | SA | 3 | GOP-04 | S4 | NFR-A7, D2 |

## ENV — Ephemeral environment lifecycle

| ID | Story | Task | Done when | Owner | Hours | Depends on | Sprint | Traceability |
|---|---|---|---|---|---|---|---|---|
| ENV-01 | US-070 | Workflow env-provision | plan -> infra-<env> approval -> apply -> install Argo CD + bootstrap -> wait for healthy -> seed synthetic data -> smoke test | SA | 6 | IAC-17, GOP-03 | P4 | ADR-0009 |
| ENV-02 | US-070 | Workflow env-destroy | Type the environment name correctly + infra-<env>-destroy approval; clean destroy, verify no resources remain | SA | 2 | IAC-17 | P4 | ADR-0009 |
| ENV-05 | US-070 | G4 rehearsal | Sample service goes local -> uat -> prod (ephemeral) through the pipeline only; GitOps revert rollback < 5 minutes; unsigned image blocked; destroy; evidence captured | SA | 4 | ENV-01, LOC-03, LOC-04, GOP-07 | S3 | Gate G4 |
| ENV-03 | US-070 | Workflow cost-guard | Runs on a schedule; lists environments exceeding TTL; opens/updates a warning issue (no auto-destroy) | SA | 2 | IAC-04 | S5 | NFR-C1 |
| ENV-04 | US-070 | Environment lifecycle runbook | Guides provision/test/destroy, expected durations, common troubleshooting | SA | 2 | ENV-01, ENV-02 | S5 | NFR-D3 |
| ENV-06 | US-070 | First ephemeral prod provisioning rehearsal: measure provision/destroy times, seed data, fallback plan if provisioning is late | Actual timing measurements; fallback assets (video/screenshots/UAT); prod cleanly destroyed after the rehearsal | SA | 4 | ENV-01, ENV-02, ENV-05 | H1 | ADR-0011 |

## OBS — Platform observability

| ID | Story | Task | Done when | Owner | Hours | Depends on | Sprint | Traceability |
|---|---|---|---|---|---|---|---|---|
| OBS-04 | ENABLER | Standard observability library (OTel, logs with trace-id, PII redaction) | OTel traces/metrics/logs, logs with trace-id, PII-redacting enricher; test asserts logs contain no PII | SA | 4 | LOC-03 | S1 | NFR-O1, O4 |
| OBS-01 | ENABLER | Standard RED dashboard for every service | Grafana dashboard by service label: rate, errors, duration p50/p95/p99 | SA | 3 | GOP-06 | S4 | NFR-O2 |
| OBS-02 | ENABLER | SLOs + burn-rate alerts | SLOs for NFR-A1, P1, P2; multi-window burn-rate alerts | SA | 3 | OBS-01 | S4 | NFR-O2, O3 |
| OBS-03 | ENABLER | DLQ and pipeline failure alerts | Alerts when a dead-letter queue has messages, a cert is expiring, or an Argo CD app is degraded | SA | 2 | OBS-01 | S5 | NFR-O3 |

## LOC — Local environment & walking skeleton

| ID | Story | Task | Done when | Owner | Hours | Depends on | Sprint | Traceability |
|---|---|---|---|---|---|---|---|---|
| LOC-01 | ENABLER | Local dev toolkit on kind (core profile): small K8s cluster + Helm for PostgreSQL, MongoDB replica set, Redis, RabbitMQ, Azurite, Keycloak; Makefile up/down/status | `make up` provisions the cluster and all core dependencies in < 10 minutes on a 16 GB machine; `make down` cleans up; README included | SA | 8 | ARC-09 | S0 | ADR-0011, NFR-I3 |
| LOC-02 | US-008, US-066 | Local Keycloak realm | Realm export with patient/doctor/nurse/admin/dpo roles, fhir_user claim, fake users | SA | 3 | LOC-01 | P1 | NFR-SEC1 |
| LOC-03 | ENABLER | Walking skeleton Java (Spring Boot) | Sample service: actuator health, info, OTel, distroless non-root Dockerfile; runs through the template pipeline | SA | 4 | ARC-09 | P1 | Gate G4 |
| LOC-04 | ENABLER | Walking skeleton Python | FastAPI: health, info, OTel, non-root Dockerfile; runs through the template pipeline | SA | 3 | ARC-09 | P1 | Gate G4 |
| LOC-08 | ENABLER | Full local dev profile: ClamAV, OpenSearch, Ollama, MLflow, Loki/Grafana/OTel; seed synthetic data into the cluster | `make up PROFILE=full` provisions infrastructure for every service; healthchecks; resource requirements documented | SA | 3 | LOC-01 | P1 | ADR-0011, NFR-I3 |
| LOC-05 | ENABLER | Run services on the local cluster with the shared microservice chart (make deploy SVC=...) | Sample service builds, loads into kind and runs with the same chart used for UAT/prod; debug guide included | SA | 3 | GOP-01, LOC-03, LOC-01 | P3 | ADR-0011, NFR-I3 |
| LOC-07 | ENABLER | Developer onboarding guide | From cloning the repo to running locally and opening a first PR in < 1 hour | SA | 2 | LOC-01, LOC-03 | P3 | PM |

## SCF — Scaffold service (SA)

| ID | Story | Task | Done when | Owner | Hours | Depends on | Sprint | Traceability |
|---|---|---|---|---|---|---|---|---|
| SCF-01 | ENABLER | Scaffold scheduling service (code skeleton, Dockerfile, svc-scheduling pipeline, Helm values, DB/bus/OTel wiring) | Service from template; svc-scheduling.yml; local/uat/prod values | SA | 2 | LOC-03, CIT-08 | S1 | ADR-0001 |
| SCF-02 | ENABLER | Scaffold notification worker (code skeleton, pipeline, values, DB/bus wiring) | Service from template; svc-notification.yml | SA | 2 | LOC-03, CIT-08 | P3 | ADR-0001 |
| SCF-03 | ENABLER | Scaffold clinical service (code skeleton, pipeline, values, MongoDB/bus wiring) | Service from template; svc-clinical.yml | SA | 2 | LOC-03, CIT-08 | S1 | ADR-0001 |
| SCF-04 | ENABLER | Scaffold document service (code skeleton, pipeline, values, Blob/MongoDB) | Service from template; svc-document.yml | SA | 2 | LOC-03, CIT-08 | P3 | ADR-0001 |
| SCF-05 | ENABLER | Scaffold audit worker (code skeleton, pipeline, values, Blob WORM/Log Analytics) | Service from template; svc-audit.yml | SA | 2 | LOC-03, CIT-08 | P3 | ADR-0001 |
| SCF-06 | ENABLER | Scaffold ai-service FastAPI (code skeleton, pipeline, values, ai node pool) | FastAPI from template; svc-ai.yml; ai node pool | SA | 2 | LOC-04, CIT-08 | S1 | ADR-0001 |
| SCF-07 | ENABLER | Scaffold ml-service (Python code skeleton, Dockerfile, pipeline, values, MongoDB/bus/MLflow wiring) | Sample service runs through the template pipeline | SA | 2 | LOC-04, CIT-08 | P3 | ADR-0001 |

## GW — API Gateway

| ID | Story | Task | Done when | Owner | Hours | Depends on | Sprint | Traceability |
|---|---|---|---|---|---|---|---|---|
| GW-01 | ENABLER | API Gateway Spring Cloud Gateway | Routes to the services; propagates trace context | D3 | 3 | LOC-03 | S2 | ADR-0001 |
| GW-02 | US-068 | Authentication + per-user rate limiting | Bad token -> 401; over limit -> 429 | D3 | 4 | GW-01, SHR-20 | S4 | NFR-S3 |

## MLO — MLOps pipeline (SA)

| ID | Story | Task | Done when | Owner | Hours | Depends on | Sprint | Traceability |
|---|---|---|---|---|---|---|---|---|
| MLO-01 | US-063 | ML training pipeline in CI + model canary deploy | Runs when data/code changes; model artifact; canary deploy | SA | 4 | ML-05 | S5 | ADR-0007 |
| MLO-02 | US-071 | Reproducible training image: pinned library versions, seeds, fixed image, vulnerability scan | Same image + dataset hash + seed produce the same model; image signed | SA | 3 | MLO-01 | S5 | 16-mlops §2 |
| MLO-03 | US-073 | Argo Rollouts canary for ml-service + AnalysisTemplate (Prometheus), auto-promote or rollback | 10%, 50%, 100% steps with analysis; bad model rolls back automatically; autoPromote configured per environment | SA | 6 | GOP-04, SCF-07, ML-25 | S5 | 16-mlops §6 |
| MLO-04 | US-074 | Retraining orchestration on the cluster: CronJob/KEDA receives requests from ML-22, runs ML-23, monitors and audits | A simulated drift alert starts exactly one retraining run; no overlapping runs | SA | 5 | ML-23, GOP-10 | S5 | 16-mlops §5 |
| MLO-05 | US-065 | Drift and actual-AUC alerts (Alertmanager) + MLOps dashboard panels | Alerts reach the right channel; dashboard shows PSI, actual AUC, model version, retraining history | SA | 3 | ML-10, OBS-01 | S5 | NFR-O3 |

## PRD — Production engineering & defense

| ID | Story | Task | Done when | Owner | Hours | Depends on | Sprint | Traceability |
|---|---|---|---|---|---|---|---|---|
| PRD-10 | US-068 | k6 script: find slot + book (1x/5x/10x scenarios) | 1x/5x/10x scenarios per workload analysis; runs on UAT (prod-sized) | SA | 4 | SCH-14 | S5 | NFR-P1, P2 |
| PRD-11 | US-068 | KEDA cron scale before 07:00 + HPA for scheduling | prod/uat values scale before the slot-opening window | SA | 1 | SCF-01, GOP-04 | S5 | NFR-S1 |
| PRD-01 | US-068 | 1x/5x/10x load test + report | Runs on ephemeral UAT (prod-sized); compared against NFR-P/S | SA | 6 | PRD-10, ENV-01 | H1 | NFR-P1, P2, S2, S3 |
| PRD-02 | US-069 | Failure test | Kill pod, DB failover, SMS provider failure, LLM failure, consumer crash; record results | SA | 6 | ENV-01 | H1 | NFR-A3, A6 |
| PRD-03 | US-067 | Backup/restore rehearsal | Postgres PITR + Mongo restore; record actual times vs RTO | SA | 4 | ENV-01 | H1 | UC-16, NFR-A4 |
| PRD-04 | ENABLER | Final SLO dashboard | Error budget per NFR-A1 | SA | 3 | OBS-02 | H1 | NFR-O2 |
| PRD-05 | ENABLER | FinOps report | Cost per environment-hour, monthly forecast, recommendations | SA | 4 | ENV-03 | H2 | NFR-C1..C3 |
| PRD-06 | ENABLER | Security review | Checklist against the threat model; scan results; authorization matrix green | SA | 4 | ARC-08 | H2 | P3 |
| PRD-08 | ENABLER | Demo script + defense slides | Answer the 12 review questions from the assignment; end-to-end demo | SA | 6 | PRD-01..PRD-07 | DEF | Assignment |
| PRD-09 | ENABLER | Demo rehearsal on ephemeral prod | Provision prod, run the script, destroy; timings captured | SA | 3 | PRD-08 | DEF | Assignment |

## SHR — Shared libraries (Java)

| ID | Story | Task | Done when | Owner | Hours | Depends on | Sprint | Traceability |
|---|---|---|---|---|---|---|---|---|
| SHR-01 | ENABLER | FHIR R4 JSON serialize/parse (HAPI FHIR) + round-trip tests for in-scope resources | Invalid parse returns 400 validation-failed; test each resource type | SA | 3 | LOC-03 | P1 | ADR-0003 |
| SHR-02 | ENABLER | Error model: exception → OperationOutcome / Problem Details + error code catalog | Every code in 07 §5 has a test; vi/en messages, no PII/stack trace leaks | SA | 3 | SHR-01 | P1 | 07 §5 |
| SHR-03 | ENABLER | Search parameter registry + parser (token, reference, date with precision, _count) | ge/gt/le/lt/eq, '2026-11' = whole month; unknown parameter → 400 | SA | 4 | SHR-01 | P1 | ADR-0003, 07 §4 |
| SHR-20 | US-008, US-066 | JWT bearer from Keycloak (standard OIDC): map roles claim, enforce amr=mfa for staff | Tests for valid/bad-signature/expired/wrong-aud tokens and staff missing MFA; configured via environment variables | SA | 3 | LOC-02 | P1 | 07 §2, 15 §1 |
| SHR-21 | US-007 | Identity resolver: sub → fhirUser + roles + disabled + linkedProfiles (record, scope), 60-second cache | Locked accounts rejected within ≤ 60 seconds; cache expires correctly; on clinical failure use the cache, return 503 when there is no cache | SA | 3 | SHR-20 | P1 | 15 §8, C2 |
| SHR-22 | ENABLER | Authz: schema + loader + validator for contracts/authz/matrix.yaml | A matrix with a bad schema (missing role, unknown predicate) fails the build; unit tests | SA | 3 | ARC-19 | P1 | 15 §3 |
| SHR-23 | ENABLER | Authz: predicates own, self-practitioner, care, breakglass, released, before-2h, encounter-finished, not-care | Each predicate has allow/deny tests; care/breakglass predicates go through a mockable interface | SA | 4 | SHR-22 | P1 | 15 §2 |
| SHR-04 | ENABLER | Keyset page token (opaque, signed) + build searchset Bundle | Tampered token → 400; next/self links correct | SA | 3 | SHR-03 | P2 | 07 §4 |
| SHR-05 | ENABLER | ETag/If-Match filter (412/428) and mandatory Idempotency-Key (428) | Missing header → 428; ETag W/"version" on every response | SA | 2 | SHR-02 | P2 | 07 §3 |
| SHR-06 | ENABLER | CloudEvents envelope + JSON Schema validation + contract test helper | Events with a bad schema are rejected; helper used by every service | SA | 3 | ARC-07 | P2 | 13 §1 |
| SHR-07 | ENABLER | Shared outbox relay (PostgreSQL and MongoDB stores), 1-second poll, SKIP LOCKED | Everything is still published after the bus dies and recovers; multiple replicas do not duplicate | SA | 4 | SHR-06 | P2 | 13 §1 |
| SHR-08 | ENABLER | Platform consumer: idempotent inbox + Spring Cloud Stream (RabbitMQ local, Service Bus cloud), retry/DLQ | Duplicate events are processed once; 5 delivery attempts then DLQ | SA | 4 | SHR-06 | P2 | 13 §1 |
| SHR-10 | ENABLER | Mongo resource store: create/update with version CAS + history + outbox in one transaction | Stale version update matches 0; full rollback on error | SA | 4 | SHR-03, LOC-08 | P2 | 03 §2.5 |
| SHR-24 | ENABLER | Authz: default-deny decision engine, field constraints (fields), purposeOfUse | No matching rule → Deny; with several matching rules, take the first Allow whose conditions hold; table tests | SA | 3 | SHR-23 | P2 | 15 §2 |
| SHR-25 | ENABLER | Authz: Spring endpoint filter + 'every endpoint must have a rule' test | Adding an endpoint without a rule fails the CI test; 403/404 follow the convention | SA | 4 | SHR-24 | P2 | 15 §3 |
| SHR-26 | ENABLER | Authz: test generator from matrix.yaml (role × condition) | Generate Allow/Deny tests for every cell from the matrix; usable in each service's tests | SA | 4 | SHR-24 | P2 | 15 §3, NFR-SEC2 |
| SHR-28 | US-015 | Idempotency-Key middleware for PostgreSQL (claim, request hash, replay) | Same key+body → return the previous result; different body → 422; missing key → 428; concurrency tests | SA | 4 | SHR-05 | P2 | 02 §3.1, 07 §3 |
| SHR-30 | US-076, US-077 | Authz: linked, linked-records, guardian conditions; onBehalfOf in audit; generate tests from the matrix for linked records | Every *-LINKED rule has Allow and Deny tests; audit records the acting account and the accessed record | SA | 3 | SHR-24, SHR-21 | P2 | 15 §3, ADR-0018 |
| SHR-09 | US-047 | Audit emitter library: builds audit.recorded (schema 05), redacts IPs, blocks PII, FHIR middleware | Audit contains no clinical content; search records only parameter names | SA | 4 | SHR-06 | S1 | E11(pub), 05 §1 |
| SHR-11 | ENABLER | Mongo search executor: compile search criteria to query + use index | Query plan uses IXSCAN for main queries | SA | 3 | SHR-10 | S1 | 03 §2.6 |
| SHR-12 | US-035 | Idempotency-Key for MongoDB (document service) | Same key → same result; different body 422 | SA | 2 | SHR-05, SHR-10 | S1 | 07 §3 |
| SHR-27 | US-047 | Authz: emit audit for every decision (allow and deny) | Every Deny emits audit outcome=denied with a reason code; tests | SA | 2 | SHR-24, SHR-09 | S1 | 05 §1, E11(pub) |

## PY — Shared libraries (Python, for ai/ml, SA)

| ID | Story | Task | Done when | Owner | Hours | Depends on | Sprint | Traceability |
|---|---|---|---|---|---|---|---|---|
| PY-01 | US-066 | Python library: JWT authentication (Keycloak), role dependency for FastAPI | Test valid/invalid/expired token | SA | 3 | LOC-02 | P1 | 07 §2 |
| PY-05 | ENABLER | Problem Details + error code catalog for FastAPI | Matches 07 §5; test | SA | 2 | LOC-04 | P1 | 07 §5 |
| PY-06 | US-055 | PII scrubber (name, phone, email, address, identifiers → fake tokens with reverse map) | Synthetic data test suite: no PII left in prompts sent outside Azure | SA | 4 |  | P1 | NFR-SEC8 |
| PY-02 | US-053 | On-behalf-of user token exchange client (Keycloak, RFC 8693 token exchange) | Exchanged token carries user identity, correct aud; test with fake IdP | SA | 3 | PY-01 | P2 | 15 §7 |
| PY-03 | US-059, US-064 | Python CloudEvents consumer: validate schema, inbox (Mongo/Postgres), retry/DLQ | Duplicate event processed once | SA | 4 | ARC-07 | P2 | 13 §1 |
| PY-07 | US-055 | LLM provider interface + DeepSeek, Gemini, Ollama (OpenAI-compatible) adapters + mandatory PII redaction + fake provider for tests | Same interface; simulate timeout/429/5xx | SA | 4 | LOC-04 | P2 | ADR-0006 |
| PY-08 | US-055 | Router: model chain per task, timeout, failover to next model, final result when chain exhausted | Record fallbackLevel; test all error branches | SA | 4 | PY-07 | P2 | 06 §4 |
| PY-04 | US-047 | Audit emitter Python (E11) for AI/ML | Does not log question content or PII; test | SA | 2 | PY-03 | S1 | E11(pub), 12 §0 |
| PY-09 | US-055 | Circuit breaker + token/day quota + per-model metrics | Circuit opens after 5 failures/60s; over budget → ai-budget-exceeded | SA | 3 | PY-08 | S1 | 06 §4, NFR-C2 |

## DAT — Synthetic data

| ID | Story | Task | Done when | Owner | Hours | Depends on | Sprint | Traceability |
|---|---|---|---|---|---|---|---|---|
| DAT-01 | ENABLER | Vietnamese synthetic data generator: patients, doctors, nurses (no real data) | Names/phones/DOB valid but fake; fixed seed for reproducibility | SA | 4 | ARC-11 | P1 | NFR-SEC9 |
| DAT-02 | ENABLER | Generator for work schedules, appointments, encounters, vital signs, diagnoses, sample documents | Consistent data across services (IDs match) | SA | 4 | DAT-01 | P2 | NFR-SEC9 |
| DAT-03 | ENABLER | Seed CLI calls public APIs: local and cloud environment | One command loads sample data; rerun no duplicates | SA | 3 | DAT-02, SCH-14, CLN-05 | S3 | NFR-SEC9 |

## SCH — Scheduling service

| ID | Story | Task | Done when | Owner | Hours | Depends on | Sprint | Traceability |
|---|---|---|---|---|---|---|---|---|
| SCH-01 | US-009, US-011 | Migration V001: schedule, schedule_template, slot (+ extension, exclusion constraint) | Runs cleanly on Postgres 16 with Testcontainers; constraint prevents overlapping slots | D1 | 2 | SCF-01 | S1 | 02 §2 |
| SCH-02 | US-015 | Migration V002: appointment, resource_history, outbox, idempotency_key, inbox | Runs clean; unique index enforces one active appointment per existing slot | D1 | 2 | SCH-01 | S1 | 02 §2 |
| SCH-03 | US-009 | Schedule repository: create/read/find/update with version CAS and history | Update with stale version fails; every write has history; integration test | D1 | 4 | SCH-02, SHR-02 | S1 | 02, ADR-0003 |
| SCH-04 | US-009, US-013 | Schedule API (S1-S4) + Practitioner/specialty check via interface (fake) + authz rules | 201/400/409/403 correct; doctor sees only own schedule; add rules to matrix | D1 | 4 | SCH-03, SHR-25 | S1 | S1, S2, S3, S4 |
| SCH-05 | US-010 | Template repository + API S5, S6 with validation (divisible, no overlap) | Replace all templates in one transaction; 400 cases have tests | D1 | 4 | SCH-04 | S2 | S5, S6 |
| SCH-06 | US-011 | Slot generator: Asia/Ho_Chi_Minh timezone, ON CONFLICT DO NOTHING | Template 08:00-11:30 / 15 min → exactly 14 free slots; rerun no duplicates | D1 | 4 | SCH-05 | S2 | AC-02.1 |
| SCH-07 | US-011 | API slots:generate (S7) + date limits | to ≤ today + 30, ≤ 31 days, from ≥ today; result created/skipped | D1 | 2 | SCH-06 | S2 | S7 |
| SCH-08 | US-011 | Daily slot generation job 01:00 (one replica runs via advisory lock) | Two replicas at once only one executes; test | D1 | 2 | SCH-06 | S2 | 02 §3.3 |
| SCH-09 | US-014 | Slot search repository with keyset pagination (specialty, practitioner, schedule, date range) | Use ix_slot_free_by_specialty (EXPLAIN); stable pagination | D1 | 4 | SCH-02, SHR-04 | S2 | 02 §4 |
| SCH-21 | US-019 | _history API for Appointment (S17) | Returns all versions; Nurse/Admin only | D1 | 2 | SCH-03 | S4 | S17 |
| SCH-10 | US-014 | Slot search API (S8): force status/start for patients, 400 when missing filter or > 31 days | Patient cannot see non-free slot; filter tests | D1 | 3 | SCH-09, SHR-25 | S2 | S8 |
| SCH-11 | US-012 | Block/unblock slot API (S9, S10) | 409 slot-has-appointment when slot busy; unblock wrong state 409; If-Match | D1 | 3 | SCH-03, SHR-25 | S3 | S9, S10, AC-02.2 |
| SCH-12 | US-015 | $book step 1: claim Idempotency-Key, advisory lock per patient, check BR-03 | Patient with 3 future appointments → 422; two requests same patient serialize | D1 | 4 | SCH-02, SHR-28 | S3 | 02 §3.1, AC-03.4 |
| SCH-13 | US-015 | $book step 2: compare-and-set slot, BR-04, create appointment, history, outbox appointment-booked | Slot busy/outside window → 409/422; commit includes history and outbox | D1 | 4 | SCH-12 | S3 | E1(pub), 02 §3.1 |
| SCH-14 | US-015 | $book API (S11): parse Parameters, check patient=own, suggest 3 slots on 409 | 201/200(replay)/400/403/404/409/422/428 correct; Nurse forces channel=staff | D1 | 4 | SCH-13, SHR-25 | S3 | S11, AC-03.6 |
| SCH-17 | US-013, US-018, US-019 | Read/search appointments (S12, S13): force filters by role, max 92 days | Patient sees only own; doctor sees only own; Nurse unrestricted; test all roles | D1 | 4 | SCH-03, SHR-25 | S3 | S12, S13 |
| SCH-22 | US-022 | Internal appointment read API (S18) with service token | Only clinical/ml can call; other roles 403; audit actor=system | D1 | 2 | SCH-17 | S4 | S18 |
| SCH-33 | US-009 | Wire real clinical client (Practitioner roles) instead of fake in SCH-04 | Contract test with clinical; Practitioner without specialty role → 400 | D1 | 2 | SCH-04, CLN-12 | S2 | 09 §4 |
| SCH-15 | US-015 | End-to-end idempotency test: same key+body, same key different body | AC-03.2 and AC-03.3 green in CI | D1 | 3 | SCH-14 | S3 | AC-03.2, AC-03.3 |
| SCH-16 | US-015 | Concurrency test: 50 requests booking one slot (Testcontainers) | Exactly 1 succeeds, 49 get 409; no duplicate appointment (AC-03.1) | D1 | 3 | SCH-14 | S3 | AC-03.1, NFR-P2 |
| SCH-18 | US-016, US-019 | $cancel API (S14): BR-02, return slot to free, outbox appointment-cancelled | <2h Patient 422, Nurse allowed; no longer booked → 409; slot returned correctly | D1 | 4 | SCH-13 | S4 | S14, E2(pub), AC-03.5 |
| SCH-20 | US-021 | $check-in API (S16): window -60/+30 min, Nurse only, outbox appointment-checked-in | Outside window 422; non-Nurse 403 | D1 | 3 | SCH-13, SHR-25 | S4 | S16, E4(pub) |
| SCH-23 | US-015 | Outbox relay: enrich ageBand from clinical (cache 24h, error → unknown) | Clinical error does not block event publishing; ageBand=unknown | D1 | 3 | SCH-13, SHR-07, CLN-12 | S4 | 13 §3 E1 |
| SCH-24 | US-020 | No-show job every 5 minutes (SKIP LOCKED) + status-changed event | booked past end+30' → noshow; two replicas do not double-process | D1 | 3 | SCH-13 | S4 | E5(pub), 02 §3.3 |
| SCH-25 | US-022 | Consumer encounter-finished → Appointment fulfilled (inbox, ignore stale events) | Duplicate/late events do not change state; emit status-changed | D1 | 3 | SCH-13, SHR-08 | S4 | E6(sub), E5(pub) |
| SCH-26 | US-004 | Consumer patient-merged: replace patientId in scheduling data (idempotent) | Rerun produces same result; test | D1 | 2 | SCH-13, SHR-08 | S5 | E7(sub) |
| SCH-27 | US-014 | Redis cache for slot search (TTL 5 seconds) + invalidate by event; Redis error → read DB directly | Hit ratio measurable; Redis down does not break API | D1 | 4 | SCH-10, SHR-08 | S5 | NFR-S2 |
| SCH-28 | US-015 | Cleanup job: published outbox >7 days, idempotency >24h, past free slots >90 days | Does not delete data still needed; test | D1 | 2 | SCH-13 | S5 | 02 §3.3 |
| SCH-19 | US-017, US-019 | $reschedule API (S15) atomic: book new + cancel old in one transaction | Midway failure keeps old appointment; emit appointment-rescheduled | D1 | 4 | SCH-18 | S4 | S15, E3(pub) |
| SCH-29 | US-009, US-012, US-016, US-017 | Run authz matrix test suite for all scheduling endpoints | All scheduling-related cells green; every Deny has audit | D1 | 4 | SCH-22, SHR-26 | S5 | NFR-SEC2 |
| SCH-30 | US-015 | Contract test for events E1-E5 (producer emits correct schema) | JSON schema validates in CI | D1 | 3 | SCH-25, SHR-06 | S5 | E1(pub), E2(pub), E3(pub), E4(pub), E5(pub) |
| SCH-31 | US-014 | EXPLAIN ANALYZE 4 hot queries on year-1 data + tune indexes if needed | Results recorded in docs; slot search query uses index | D1 | 3 | SCH-27, DAT-03 | S5 | 02 §4, NFR-P1 |
| SCH-32 | US-068 | Performance tuning from load test results (pool, index, queries) | Meets NFR-P1, P2 or has explanation | D1 | 4 | PRD-01 | H1 | NFR-P1, P2 |

## NTF — Notification service

| ID | Story | Task | Done when | Owner | Hours | Depends on | Sprint | Traceability |
|---|---|---|---|---|---|---|---|---|
| NTF-01 | US-041 | Bootstrap MongoDB notification (ADR-0014): preferences, device_tokens, reminders (embedded send count), inbox, outbox | Idempotent script; unique (appointmentId, kind); TTL cleans old reminders; channel validator | D3 | 2 | SCF-02 | S2 | 04 §1.2 |
| NTF-02 | US-041 | Consumer appointment-booked → create confirmation reminders, T-24h, T-2h (skip past milestones) | Duplicate event does not create duplicate reminder; test time milestones | D3 | 3 | NTF-01, SHR-08 | S3 | E1(sub) |
| NTF-03 | US-042 | Consumer appointment-cancelled/rescheduled: cancel unsent reminders, create cancellation reminder | AC-07.1; reschedule sends only one combined notification | D3 | 3 | NTF-02 | S3 | E2(sub), E3(sub), AC-07.1 |
| NTF-04 | US-005, US-041 | Preference logic: channel order, language, quiet hours (skip at t-2h) | Test shifting send time across quiet hours | D3 | 3 | NTF-01 | S3 | 04 §1.2 |
| NTF-05 | US-043 | Work loop: atomic findOneAndUpdate + lockedUntil, reclaim stuck sending reminders | Worker dies midway → reminder resent; two workers no duplicate (MongoDB Testcontainers test) | D3 | 4 | NTF-01 | S3 | 04 §1.3 |
| NTF-06 | US-041, US-043 | Channel abstraction + fake provider + vi/en template (no embedded PII/diagnosis) | Template has doctorName/startTime/place variables; test rendering both languages | D3 | 4 | NTF-01 | S3 | 11 §1 |
| NTF-07 | US-043 | Sender: fallback channel, backoff 1/5/15/60 minutes, dead-letter on 5th attempt, metric | Simulate per-channel failure; 5th attempt → dead-lettered; attempt unique | D3 | 4 | NTF-05, NTF-06 | S4 | AC-07.2 |
| NTF-08 | US-043 | Client fetches contact info from clinical (internal) + audit; no PII stored | No phone/email in DB/log; test | D3 | 2 | NTF-06, CLN-12 | S4 | 09 §4 |
| NTF-12 | US-043 | Real adapters: push (FCM), email, SMS via channel interface | Configured via identity/secret store; fake mode for local | D3 | 4 | NTF-07 | S5 | ADR-0004 |
| NTF-09 | US-005 | Device and preference API (N1-N4) | Owner from identity; wrong channel 400; upsert by token | D3 | 4 | NTF-04, SHR-25 | S4 | N1, N2, N3, N4 |
| NTF-10 | US-044 | Reminder view API by appointment and dead-letter, retry (N5-N7) | No contact info returned; retry after exam time 409 | D3 | 3 | NTF-07, SHR-25 | S4 | N5, N6, N7 |
| NTF-11 | US-045, US-046, US-004 | Consumer document-released, no-show-scored, patient-merged | t-6h-risk only when highRisk and time remains; result-available generic content | D3 | 3 | NTF-02 | S4 | E9(sub), E10(sub), E7(sub) |
| NTF-13 | US-041 | Send latency metric to measure NFR-P8 | Histogram from due_at to sent; included in dashboard | D3 | 2 | NTF-07 | S5 | NFR-P8 |
| NTF-15 | US-043, US-069 | Failure tests: provider outage, worker killed midway | No loss and no duplicate sends; record results | D3 | 3 | NTF-12 | S5 | NFR-A6 |
| NTF-16 | US-079 | OTP service (N8, N9): otp_challenges (hashed, TTL 5 minutes), single-use, limits per number and IP, 15-minute lock | AC-RG.4; never log code or phone number | D3 | 4 | NTF-06 | S4 | N8, N9, ADR-0018 |
| NTF-14 | US-044, US-005 | Authz matrix + event contract tests for notification | Green in CI | D3 | 3 | NTF-10, SHR-26 | S5 | NFR-SEC2 |
| NTF-17 | US-079 | Zalo adapter (verification message) and auto SMS fallback with same code on failure or over 30 seconds; SMS fallback rate and budget metric | AC-RG.3; fake adapter for dev | D3 | 4 | NTF-16, NTF-12 | S5 | AC-RG.3 |
| NTF-18 | US-079 | OTP tests: expiry, reuse, 5 wrong attempts, per-number limit, SMS fallback, no code logging | AC-RG.3, AC-RG.4 | D3 | 3 | NTF-17 | S5 | AC-RG.3, AC-RG.4 |

## CLN — Clinical service

| ID | Story | Task | Done when | Owner | Hours | Depends on | Sprint | Traceability |
|---|---|---|---|---|---|---|---|---|
| CLN-02 | US-001, US-006 | Validator profile for Patient, Practitioner, PractitionerRole (cardinality, value set) | Out-of-profile fields rejected; test each constraint | D2 | 4 | SHR-01, ARC-20 | S1 | 01 §4 |
| CLN-10 | US-006 | Practitioner/PractitionerRole API (C9, C10): directory for all users, writes Admin only | Directory does not expose personal contact; staff code NV-NNNN | D2 | 4 | CLN-02, SHR-25 | S2 | C9, C10 |
| CLN-11 | US-007 | /me API (C2), manage link/account lock (C21), internal identity API | Lock effective ≤ 60 seconds; role mismatch PractitionerRole → 422 | D2 | 4 | CLN-10, SHR-21 | S2 | C2, C21, 15 §8 |
| CLN-12 | US-033, US-043 | Internal API: Care check, Practitioner roles, contact info, age-band | Only allowed services can call; contact access audited | D2 | 4 | CLN-11 | S2 | 09 §4 |
| CLN-13 | US-033 | Consumer appointment-booked/cancelled/rescheduled/status-changed → care_relationships | Care until start+365 days; cancel all sources loses Care; duplicate event ignored | D2 | 4 | CLN-12, SHR-08 | S3 | E1(sub), E2(sub), E3(sub), E5(sub), 15 §4 |
| CLN-14 | US-023 | Consumer appointment-checked-in → create Encounter (unique appointmentId) | Receiving twice creates only one Encounter | D2 | 3 | CLN-13 | S3 | E4(sub) |
| CLN-16 | US-025 | Observation validator: LOINC, UCUM units, plausible ranges, compute interpretation | Temperature 50 °C → value-out-of-range; blood pressure has 2 components | D2 | 4 | ARC-20, CLN-02 | S3 | 01 §4.7 |
| CLN-01 | US-002 | Bootstrap MongoDB clinical: collections, $jsonSchema validator, all indexes per design | Idempotent script; validator rejects invalid document in test | D2 | 4 | SCF-03, SHR-10 | S1 | 03 §2.2, §2.4 |
| CLN-15 | US-024 | Encounter API: $start, read, search, edit note (C11-C13), block edits when finished | Non-doctor of appointment 403; edit finished 409 | D2 | 4 | CLN-14, SHR-25 | S3 | C11, C12, C13 |
| CLN-17 | US-025 | Observation API (C16): write/read/search, Encounter and permission constraints | Vital signs without Encounter → 400; Patient sees only finished Encounter data | D2 | 3 | CLN-16, CLN-15 | S3 | C16 |
| CLN-18 | US-026 | Condition API (C17) + ICD-10 table + rule recorder must have Care | No Care (including break-glass) → 403; bad code 422; entered-in-error keeps record | D2 | 4 | CLN-15, ARC-20 | S3 | C17, AC-04.1 |
| CLN-20 | US-028 | $finish API (C14) + emit encounter-finished | No diagnosis yet → 422 diagnosis-required | D2 | 3 | CLN-18 | S4 | C14, E6(pub) |
| CLN-21 | US-029 | $addendum API (C15) | Only when finished; original unchanged | D2 | 2 | CLN-20 | S4 | C15, AC-04.2 |
| CLN-25 | US-029 | Load ICD-10 and LOINC into lookup tables on startup | Idempotent load; code lookup < 1 ms | D2 | 2 | CLN-18 | S4 | ARC-20 |
| CLN-27 | US-023, US-033 | Event contract test E1-E7 for clinical (consumer reads example, producer emits correct schema) | Green in CI | D2 | 3 | CLN-21, SHR-06 | S5 | E6(pub), E7(pub) |
| CLN-33 | US-076, US-077, US-078 | profile_links: link model (guardian, proxy, scope, expiry), linkedProfiles in internal resolver and /me | Cache 60 seconds; expired or revoked links ignored | D2 | 4 | CLN-12, SHR-21 | S4 | ADR-0018, 03 §profile_links |
| CLN-03 | US-001 | Patient repository: generate MRN BN-YYYY-NNNNNN, nameTokens without diacritics, normalize phone to E.164 | MRN unique under concurrent creation; 'Nguyen' with diacritics → 'nguyen' | D2 | 4 | CLN-01, CLN-02 | S1 | 03 §2.3 |
| CLN-04 | US-001, US-003 | Duplicate record detection by phone + date of birth | Duplicate → duplicate-patient-suspected, no data leak | D2 | 2 | CLN-03 | S1 | AC-01.2 |
| CLN-05 | US-001 | Patient registration API (C1) + create identity_links | 201/400/409/422 correct; MRN returned; audited | D2 | 4 | CLN-04, SHR-25, SHR-21 | S1 | C1 |
| CLN-06 | US-002 | Patient read/update/history API (C4, C5, C7) with patient field rules | Patient edits name → 403; every edit has version; another's record 404 | D2 | 4 | CLN-05 | S2 | C4, C5, C7, AC-01.1, AC-01.3 |
| CLN-07 | US-003 | Nurse create record API (C3) + forceCreate with reason and audit | No forceCreate when duplicate suspected → 422 | D2 | 3 | CLN-05 | S2 | C3 |
| CLN-08 | US-031 | Patient search API POST _search (C6): name without diacritics, phone, MRN, date of birth, abbreviated results | 'nguyen va' finds right person; non-Care doctor sees only birth year; no params → 400 | D2 | 4 | CLN-05, SHR-11 | S2 | C6, AC-06.1, AC-06.2 |
| CLN-19 | US-027 | AllergyIntolerance API (C18) | Care doctor and Nurse write; patient reads own | D2 | 2 | CLN-15 | S3 | C18 |
| CLN-22 | US-030 | Rules for patient reading own clinical data (finished Encounter only) | Unfinished Encounter 404; another's data 404 | D2 | 3 | CLN-17, CLN-18 | S4 | 15 §6 |
| CLN-24 | US-034 | Break-glass API (C20): 60-minute session TTL, reason 20-500 characters, ETREAT audit, read only | Writing diagnosis in session 403; expired session disappears automatically | D2 | 4 | CLN-13, SHR-09 | S4 | C20, AC-10.1 |
| CLN-09 | US-004 | Merge records API (C8) + emit patient-merged | Source record active=false, link replaced-by; merging again 409 | D2 | 4 | CLN-06 | S2 | C8, E7(pub) |
| CLN-26 | US-002, US-024, US-026, US-031, US-034 | Run authz matrix test suite for all clinical endpoints | All clinical cells green; Admin 403 for all clinical data | D2 | 4 | CLN-24, SHR-26 | S5 | NFR-SEC2 |
| CLN-30 | US-075 | CCCD field via C5 (Nurse only): 12 digits, matches gender and birth year, consent record, masked response, ?reveal audited | AC-ID.1, AC-ID.3: valid store masks last 4 digits; invalid format or missing consent rejected | D2 | 4 | CLN-03 | S4 | C5, AC-ID.1, AC-ID.3 |
| CLN-34 | US-076 | API C22, C23: profile list and create profile under 16 (max 10, duplicate prevention, attestation) | AC-DP.1, AC-DP.2, AC-DP.3 | D2 | 4 | CLN-33, CLN-04 | S4 | C22, C23 |
| CLN-36 | US-078 | API C27, C28: claim existing profile (match name, date of birth, phone hash), neutral response, limits, notify phone on record | AC-CL.1 to AC-CL.4; no profile leak when no match | D2 | 4 | CLN-33, CLN-04, NTF-16 | S5 | C27, C28 |
| CLN-23 | US-032 | Timeline API (C19): 4 parallel queries + document client with 300 ms timeout, partial | Document error still returns the rest with partial; keyset | D2 | 4 | CLN-17, CLN-18, DOC-10 | S4 | C19 |
| CLN-28 | US-032 | Timeline optimization: measure p95, index, projection to reach ≤ 500 ms | p95 report on year-1 data (NFR-P3) | D2 | 4 | CLN-23, DAT-03 | S5 | NFR-P3 |
| CLN-31 | US-075 | AES-256-GCM encryption (Key Vault key) + HMAC-SHA-256 hash, unique partial index, merge suggestion on duplicate | AC-ID.2: duplicate not stored; DB contains no plaintext number | D2 | 4 | CLN-30 | S4 | AC-ID.2, 03 §2 |
| CLN-35 | US-077 | API C24, C25: link adult relative via OTP, scope, 12-month expiry and renewal reminder | AC-LK.1, AC-LK.4, AC-PX.1 | D2 | 4 | CLN-33, NTF-16 | S5 | C24, C25 |
| CLN-32 | US-075 | Test no CCCD leakage: logs, events, search, messages, text sent to AI | AC-ID.4: scanning outputs with fake CCCD (prefix 000) finds nothing | D2 | 3 | CLN-31 | S5 | AC-ID.4, NFR-SEC10 |
| CLN-37 | US-076, US-077, US-078 | Authorization and linked-profile probing tests: linked, guardian, scope, expiry, revoke ≤ 60 seconds | AC-PX.1, AC-LK.2; generate Allow/Deny tests from matrix; 100 probe attempts cannot distinguish match | D2 | 3 | CLN-35, CLN-36, CLN-38, SHR-26 | S6 | AC-PX.1, AC-CL.2 |
| CLN-38 | US-077 | API C26, C29: revoke link (effective ≤ 60 seconds, notify) and record authorization at front desk | AC-LK.3, AC-RV.1, AC-RV.2 | D2 | 3 | CLN-35 | S5 | C26, C29 |

## DOC — Document service

| ID | Story | Task | Done when | Owner | Hours | Depends on | Sprint | Traceability |
|---|---|---|---|---|---|---|---|---|
| DOC-01 | US-035 | Bootstrap MongoDB document: collections, validator, index, TTL upload_sessions | Idempotent script | D3 | 2 | SCF-04, SHR-10 | S3 | 03 §3 |
| DOC-02 | US-035, US-037 | IBlobStore on Azure Blob SDK (one code for local Azurite and cloud); choose SAS signing method via configuration (user delegation on cloud, account key on Azurite); write SAS 10 minutes/read 5 minutes | Test with Azurite (Testcontainers); SAS has correct permissions, blob, expiry | D3 | 4 | DOC-01 | S3 | 05 §2 |
| DOC-03 | US-035 | Upload request API (D1): check type, size, Care, Idempotency-Key | 415/413/403/400 correct; same key same documentId | D3 | 4 | DOC-02, SHR-12, SHR-25 | S3 | D1, AC-05.1 |
| DOC-04 | US-035 | Complete API (D2): check existence, size, SHA-256, magic bytes | Mismatch → 422 upload-incomplete; 202 scanning | D3 | 4 | DOC-03 | S4 | D2 |
| DOC-06 | US-037, US-039 | Metadata read/search API (D3, D4) with patient rules (released only) | Patient not released → 404; no URL returned | D3 | 3 | DOC-04, SHR-25 | S4 | D3, D4, AC-05.3 |
| DOC-07 | US-037 | Download link API 5 minutes (D5) + audit every time | After 5 minutes Blob 403; not available 409; Deny also audited | D3 | 3 | DOC-06, SHR-09 | S4 | D5, AC-05.2 |
| DOC-08 | US-038 | Release API (D6) + emit document-released | Release again 409; Care doctor only | D3 | 2 | DOC-06 | S4 | D6, E9(pub) |
| DOC-09 | US-040 | entered-in-error API (D7) | Hidden from timeline/patient, record kept | D3 | 2 | DOC-06 | S5 | D7 |
| DOC-10 | US-032, US-059, US-004 | Internal API (D8, D9) + consumer patient-merged | D9 read SAS 60 seconds only for ai service; merge idempotent | D3 | 3 | DOC-06, SHR-08 | S4 | D8, D9, E7(sub) |
| DOC-11 | US-035 | Job to clean orphan blobs and pending-upload > 24 hours | Does not delete available files | D3 | 2 | DOC-04 | S5 | 10 §5 |
| DOC-05 | US-036 | Scan result handling: clean → available + emit document-uploaded; infected → quarantine | Infected file emits no event; scan error retries 3 times | D3 | 4 | DOC-04, IAC-18 | S4 | E8(pub) |
| DOC-12 | US-035, US-037, US-038 | Authz matrix and event contract tests for document | Green in CI | D3 | 4 | DOC-09, SHR-26 | S5 | NFR-SEC2 |

## AUD — Audit service

| ID | Story | Task | Done when | Owner | Hours | Depends on | Sprint | Traceability |
|---|---|---|---|---|---|---|---|---|
| AUD-01 | US-047 | Consumer audit.recorded: validate schema, inbox by eventId, batch 60s/5.000 records | Duplicate event not written twice; bus error loses nothing | D3 | 4 | SCF-05, SHR-08 | S2 | E11(sub) |
| AUD-03 | US-049 | Ship to Log Analytics (Logs Ingestion) / local Loki | Appears within ≤ 1 minute; no loss on sink error (retry) | D3 | 4 | AUD-01 | S3 | 05 §1.3, NFR-SEC5 |
| AUD-02 | US-048 | Append NDJSON blob to WORM container with hash chain (prevHash, batchHash) | Next batch contains previous batch hash; batchHash metadata correct | D3 | 4 | AUD-01, IAC-12 | S3 | 05 §1.2 |
| AUD-04 | US-049 | Audit query API (A1): filters, range ≤ 90 days, hide reason from Admin, audit the query itself | Missing from/to → 400; DPO sees all fields | D3 | 4 | AUD-03, SHR-25 | S4 | A1 |
| AUD-07 | US-051 | Break-glass review (A5) + emit DPO alert ≤ 5 minutes via notification | Status new → acknowledged; alert sent by email + push | D3 | 4 | AUD-03, CLN-24 | S4 | A5, AC-10.1 |
| AUD-05 | US-050 | Audit export API (A2, A3): background CSV export job, SAS link 5 minutes | Range ≤ 366 days; max 3 concurrent exports | D3 | 4 | AUD-04 | S4 | A2, A3 |
| AUD-06 | US-050 | Hash chain integrity check tool + API (A4) | Detects altered/missing batch and reports location | D3 | 4 | AUD-02 | S4 | A4 |
| AUD-08 | US-048 | Immutability test: audit blob edit/delete rejected, broken hash chain detected | Broken hash chain detected: green in CI (Azurite); blob edit/delete rejected (WORM): smoke test on UAT | D3 | 3 | AUD-06 | S5 | AC-09.2 |

## AI — AI service

| ID | Story | Task | Done when | Owner | Hours | Depends on | Sprint | Traceability |
|---|---|---|---|---|---|---|---|---|
| AI-01 | US-052 | Bootstrap MongoDB ai + script to create AI Search index kb-chunks-v1 (and local OpenSearch) | Idempotent script; same schema for both backends | SA | 3 | SCF-06, LOC-08 | S1 | 06 §1.2 |
| AI-02 | US-052 | Document parser: PDF, DOCX, Markdown → structured text (sections, pages) | Keeps section titles and page numbers; test with sample documents | SA | 3 | AI-01 | S1 | 06 §1.2 |
| AI-03 | US-052 | Chunker: 600 tokens, 80 overlap, split by section heading | Chunks do not cut across sections; length tests | SA | 4 | AI-02 | S1 | 06 §1.2 |
| AI-04 | US-052 | Local bge-m3 embedding (Ollama, 1024 dims) + index upsert/delete; version swap with no gap | New chunks indexed before old deleted; test | SA | 4 | AI-03, PY-07 | S1 | 06 §1.2 |
| AI-05 | US-052 | Knowledge base API (AI9): upload, approve, replace file, remove; Admin/Department Doctor permission | draft → approved → indexed → retired; index error retries 3 times | SA | 4 | AI-04, PY-01 | S2 | AI9 |
| AI-06 | US-053 | Hybrid retrieval (RRF, optional cross-encoder rerank per eval) + security trimming by role/department; degrade when embedding/reranker fails | Unauthorized users never see chunks; filtering tests | SA | 4 | AI-04 | S2 | 06 §1.2 |
| AI-07 | US-053 | Versioned prompt template + context assembly within token limit | Prompt version ID recorded in message | SA | 3 | AI-06 | S2 | 06 §4 |
| AI-08 | US-053 | Answer generation + citation-chunk match check + answered=false rule | No source → do not call LLM to generate content | SA | 4 | AI-07, PY-08 | S2 | AC-11.1 |
| AI-09 | US-053, US-055 | Assistant question API (AI1) + SSE stream, reset event on model switch | Retrieval-only mode when all models fail; first-token p95 ≤ 3s | SA | 4 | AI-08, PY-09 | S3 | AI1, AC-11.3 |
| AI-10 | US-054 | Input guardrail: length, prompt injection patterns, block with audit | Attack patterns blocked; does not wrongly block normal medical questions | SA | 3 | AI-09 | S3 | AC-11.2 |
| AI-11 | US-054 | Output guardrail: schema, no self-issued diagnosis/orders, quotes match chunks | Violation → try next model then answered=false | SA | 3 | AI-09 | S3 | AC-11.2 |
| AI-12 | US-056 | Store conversations/messages (TTL 30 days) + feedback and history API (AI2, AI3) | Conversation owner only; duplicate rating 409 | SA | 3 | AI-09 | S3 | AI2, AI3 |
| AI-13 | US-055 | Track tokens/cost/fallback + usage API (AI10) + alert at 80% budget | Report by task/model/day | SA | 3 | PY-09 | S3 | AI10, NFR-C2 |
| AI-14 | US-057 | RAG eval set part 1: 50 questions + reference answers from sample knowledge base | JSON file with schema check | SA | 3 | AI-05 | S3 | NFR-AI1 |
| AI-15 | US-057 | RAG eval set part 2: add 50 questions (some without answers in documents) | Total ≥ 100 questions, ≥ 15 unanswerable | SA | 3 | AI-14 | S4 | NFR-AI2 |
| AI-16 | US-057 | Eval harness: Recall@5, MRR, groundedness, citation correctness, correct-refusal rate | Run one command, export Markdown + JSON report | SA | 4 | AI-15 | S4 | NFR-AI1, AI2 |
| AI-17 | US-054 | Prompt injection attack set 50 samples (in questions and in documents) + runner | 0 bypasses is the CI pass condition | SA | 4 | AI-11 | S4 | NFR-AI3 |
| AI-19 | US-058 | Gather data for summary via OBO: timeline, comorbidities, allergies, abnormal results; number sources | No permission → 403 before data enters prompt | SA | 4 | PY-02, CLN-23 | S4 | AC-12.1 |
| AI-20 | US-058 | Prompt + output schema + source validator (reject claims without valid source) | Every claim has sources pointing to real records | SA | 4 | AI-19, PY-08 | S4 | AC-12.2 |
| AI-18 | US-057, US-055 | Run eval for each model in fallback chain and produce comparison report | Per-model report (NFR-AI7) | SA | 3 | AI-16, IAC-19 | S5 | NFR-AI7 |
| AI-21 | US-058 | Summary API (AI4): Idempotency-Key, 10-minute cache, AI label, structured-timeline fallback | All models failing still returns timeline; has 'AI-generated' label (AC-12.3) | SA | 4 | AI-20 | S5 | AI4, AC-12.3 |
| AI-22 | US-059 | Extraction job model + consumer document-uploaded + state machine + retry/DLQ | unique (documentId, version); retry 1/5/15 minutes → failed | SA | 4 | PY-03, DOC-05 | S5 | E8(sub), AC-13.2 |
| AI-23 | US-059 | OCR step with Tesseract (local); text stored in Blob | Fallback when service fails; test with sample documents | SA | 4 | AI-22, DOC-10 | S5 | D9 |
| AI-24 | US-059 | Classification + field extraction by LLM per schema + value/unit validator + confidence | overallConfidence = min of required fields; threshold 0,85 | SA | 4 | AI-23, PY-08 | S5 | 06 §3 |
| AI-25 | US-059, US-060 | Extraction list and detail API (AI5) with field positions to highlight on document | Filter by status/patient; Care/Nurse permission | SA | 3 | AI-24 | S6 | AI5 |
| AI-26 | US-060 | Approve API (AI6): create Observation via OBO after user confirms; record editedFields | No data enters record before confirmation; clinical error returned verbatim | SA | 4 | AI-25, PY-02, CLN-17 | S6 | AI6, AC-13.1 |
| AI-27 | US-061 | Labelled synthetic document set for extraction (lab results, prescriptions, referrals) | ≥ 60 documents with field labels | SA | 4 | AI-24 | S6 | NFR-AI4 |
| AI-29 | US-062 | Booking bot: 4 tools, parameter schema, max 6 turns and 10-second timeout | Unknown tool rejected; bad parameters blocked | SA | 4 | PY-08, PY-02 | S6 | AC-14.2 |
| AI-30 | US-062 | pending_action + confirmation API (AI8): call $book/$cancel with patient token, 5-minute expiry | No appointment before confirmation (AC-14.1); 409 → suggest again | SA | 4 | AI-29, SCH-14 | S6 | AI8, AC-14.1 |
| AI-31 | US-062 | Booking chat API (AI7) + fallback to form when all models fail | 503 ai-unavailable with fallback (AC-14.3) | SA | 3 | AI-30 | S6 | AI7, AC-14.3 |
| AI-32 | US-052, US-053, US-058, US-060, US-062 | Authz matrix and event contract tests for ai-service | Green in CI; non-Care doctor cannot get data via AI | SA | 4 | AI-31, AI-26 | S6 | NFR-SEC2 |
| AI-28 | US-061 | Field-level and classification F1 eval + report | F1 ≥ 0,85 or explanation | SA | 3 | AI-27 | H1 | NFR-AI4 |
| AI-33 | US-055 | Token and cost report by task/model | Numbers from AI10; compared with NFR-C2 | SA | 3 | AI-13, PRD-01 | H2 | NFR-C2 |

## ML — ML no-show

| ID | Story | Task | Done when | Owner | Hours | Depends on | Sprint | Traceability |
|---|---|---|---|---|---|---|---|---|
| ML-01 | US-063 | Generate appointment data with no-show patterns (lead time, past absence, exam hour, channel) | Documented patterns; no-show rate ~18% overall | SA | 4 | ARC-11 | P1 | ADR-0007 |
| ML-02 | US-063 | Feature engineering + time-based train/validation split | No future leakage; feature tests | SA | 4 | ML-01 | P2 | 04 §2.3 |
| ML-03 | US-063 | Baseline Logistic Regression + metrics (AUC, precision/recall at threshold) | Recorded in MLflow | SA | 3 | ML-02 | P2 | ADR-0007 |
| ML-04 | US-063 | LightGBM + hyperparameter search + MLflow tracking | Compare with baseline; save artifact | SA | 4 | ML-03 | P2 | ADR-0007 |
| ML-13 | US-071 | Versioned Parquet dataset: write dataset hash into MLflow run, rebuild model from hash | Same hash + seed → same model (verified by test) | SA | 3 | ML-01 | P2 | 16-mlops §2 |
| ML-14 | US-071 | Data validation before training (schema, ranges, missing rate, temporal leakage) with pandera | Bad data stops pipeline with clear reason; test failure cases | SA | 3 | ML-13 | P2 | 16-mlops §2 |
| ML-15 | US-071 | Shared feature package for training and serving | Single source for all features; has unit tests | SA | 3 | ML-02 | P2 | 04 §2.3 |
| ML-17 | US-072 | Subgroup evaluation (specialty, age group, channel) + calibration + prediction latency | Gate blocks if subgroup AUC > 0,07 below overall or p95 > 100 ms | SA | 4 | ML-04 | P2 | 16-mlops §3 |
| ML-21 | US-065 | Second synthetic dataset with shifted distribution (longer lead time, higher no-show rate) | Documented shift magnitude; used for detector tests and demo | SA | 3 | ML-01 | P2 | 16-mlops §1 |
| ML-05 | US-063 | Quality gate and model registry: AUC ≥ 0,75 and above baseline before production alias registration | Weak model blocked; gate has tests | SA | 3 | ML-04, LOC-08 | S1 | NFR-AI6, M2 |
| ML-18 | US-072 | Auto-generate model card on model registration | Model card stored with artifact in MLflow; all sections per 16-mlops §3 | SA | 3 | ML-17, ML-05 | S1 | 16-mlops §3 |
| ML-06 | US-064 | Consumer E1/E2/E3/E5/E7: write appointment_fact, update outcome and attendance_stats | Duplicate event ignored; late cancel <24h counts as late_cancel | SA | 4 | SCF-07, PY-03, ML-11 | S2 | E1(sub), E2(sub), E3(sub), E5(sub), E7(sub) |
| ML-07 | US-064, US-046 | Score on E1: build features, predict, save prediction, emit no-show-scored via outbox | AC-15.2 stores model version and features; same transaction | SA | 4 | ML-06, ML-05 | S2 | E10(pub), AC-15.2 |
| ML-08 | US-063, US-064 | API M1 (view prediction), M2 (current model), M3 (internal predict) | p95 ≤ 100 ms for M3; correct permissions | SA | 3 | ML-07 | S2 | M1, M2, M3 |
| ML-11 | US-064 | Bootstrap MongoDB ml (ADR-0014): patient_stats, appointment_facts, predictions (time-series if vCore supports), inbox, outbox | Idempotent script; validator enforces ageBand, score; index (modelVersion, predictedAt) | SA | 2 | SCF-07 | S2 | 04 §2.2 |
| ML-16 | US-071 | Train/serve skew test: same sample produces matching output on both paths | Test green in CI; skew fails | SA | 3 | ML-15, ML-07 | S2 | 16-mlops §2 |
| ML-19 | US-065 | Drift detector: per-feature PSI and prediction score vs training data | PSI > 0,2 threshold on ≥ 2 main features; test with shifted dataset | SA | 4 | ML-07, ML-21 | S2 | 16-mlops §4 |
| ML-20 | US-065 | Actual performance monitoring: join predictions with real outcomes, 14-day rolling AUC | Alert when AUC drops > 0,05 and ≥ 500 labelled samples | SA | 4 | ML-06, ML-07 | S2 | 16-mlops §4 |
| ML-09 | US-063 | Reload model on production alias change within ≤ 60 seconds without deploy | Alias change test | SA | 2 | ML-08 | S3 | ADR-0007 |
| ML-12 | US-064, US-046 | Contract test: consume E1/E5 and emit E10; ML failure does not affect reminders | Green in CI; AC-15.1 has test in notification | SA | 2 | ML-08, SHR-06 | S3 | E10(pub), AC-15.1 |
| ML-22 | US-074 | Retraining trigger logic: conditions, 7-day cooldown, ≥ 500 new labelled samples, no overlapping runs | Test cases: drift, AUC drop, in cooldown, insufficient samples | SA | 3 | ML-19, ML-20 | S3 | 16-mlops §5 |
| ML-23 | US-074 | Retraining job: train → data check → quality gate → register candidate alias, never auto-switch production | Stops and keeps production when gate fails; every run has links and audit | SA | 4 | ML-22, ML-05, ML-14, ML-17 | S3 | 16-mlops §5 |
| ML-24 | US-073 | Shadow mode in serving: candidate model scores in parallel, record only, do not use results | No user-side latency increase; agreement metric with running model | SA | 3 | ML-09 | S3 | 16-mlops §6 |
| ML-25 | US-073 | Export metrics for canary analysis: error rate, p95, score distribution, agreement, version label | Prometheus has metrics per model_version for AnalysisTemplate | SA | 3 | ML-08 | S3 | 16-mlops §6 |
| ML-10 | US-065 | Daily drift job runs detector (ML-19, ML-20), exports metric and alerts | Actual PSI/AUC metrics in Prometheus; alert when above threshold | SA | 3 | ML-19, ML-20 | S6 | NFR-AI6 |

## WEB — Web app (PWA)

| ID | Story | Task | Done when | Owner | Hours | Depends on | Sprint | Traceability |
|---|---|---|---|---|---|---|---|---|
| WEB-01 | US-066 | React/Vite web shell: OIDC (Keycloak), role-based router, layout frame, i18n vi/en | Login, role-based menu, ErrorBoundary; build via pipeline | SA | 4 | LOC-02, SHR-20 | P2 | NFR-I2 |
| WEB-02 | US-066 | API client: attach token, Idempotency-Key, If-Match/ETag, map OperationOutcome/Problem Details to messages | Show errors per Accept-Language; auto retry login when session expires | SA | 3 | WEB-01 | S1 | 07 §3, §5 |
| WEB-06 | US-009, US-010 | Doctor schedule management screen: Schedule and weekly template | Nurse/Admin create/edit; validation errors shown per field | D1 | 4 | WEB-02, SCH-05 | S4 | S1, S5 |
| WEB-10 | US-066 | Pipeline + chart for the web app | Non-root nginx container using the shared chart | SA | 2 | WEB-01 | S3 | ADR-0009 |
| WEB-20 | US-031 | Patient search screen (POST _search): name without diacritics, phone, MRN | Abbreviated results; non-Care Doctor sees break-glass suggestion | D2 | 4 | WEB-02, CLN-08 | S5 | C6 |
| WEB-24 | US-034 | Break-glass flow: reason dialog, 60-minute countdown banner | Banner visible for whole session | D2 | 2 | WEB-20, CLN-24 | S6 | C20 |
| WEB-27 | US-001, US-002, US-003 | Profile screen: register, view/edit my profile, front-desk registration | Handle 422 duplicate record; patient-locked fields | D2 | 4 | WEB-02, CLN-06 | S2 | C1, C4, C5 |
| WEB-03 | US-014 | Free slot search screen: specialty/doctor/date filters, paginated list | Calls only S8 API; displays in VN timezone | D1 | 4 | WEB-02, SCH-10 | S3 | S8 |
| WEB-04 | US-015 | Booking screen: confirm, handle 409 (slot suggestions), 422 BR-03/BR-04, prevent double-click | Each click uses a new Idempotency-Key; suggestions shown on 409 | D1 | 4 | WEB-03, SCH-14 | S3 | S11 |
| WEB-05 | US-016, US-017, US-018 | My appointments screen: view, cancel (warn <2h), reschedule | Clear message on 422 cancellation-too-late | D1 | 4 | WEB-04, SCH-19 | S4 | S12, S13, S14, S15 |
| WEB-07 | US-011, US-012, US-013 | Weekly slot calendar: generate slots, block/unblock; doctor views own schedule | Cannot block booked slot (shows 409) | D1 | 4 | WEB-06, SCH-11 | S5 | S7, S9, S10 |
| WEB-26 | US-049, US-050, US-051 | DPO screen: audit query, CSV export, break-glass review | DPO/Admin only; Admin cannot see reason | D2 | 4 | WEB-02, AUD-07 | S6 | A1, A2, A5 |
| WEB-28 | US-075 | CCCD supplement box in queue and check-in: input field, consent checkbox, show number with confirmation | Can be skipped; shown masked; warn on gender or birth-year mismatch | D2 | 3 | WEB-27, CLN-30 | S5 | C5 |
| WEB-30 | US-053, US-056 | Assistant UI: ask, stream, clickable citations, rating | Shows retrieval-only mode; citations open correct section | SA | 4 | WEB-02, AI-09 | S4 | AI1, AI2 |
| WEB-35 | US-076, US-077 | Relative profiles screen: list, add child, add relative, revoke; choose patient when booking | Per wireframe SCR relatives; show scope and expiry date | D2 | 4 | WEB-27, CLN-34 | S5 | C22, C23, C24, C25, C26 |
| WEB-36 | US-078 | Claim existing profile screen: form, enter OTP, resend by SMS after 30 seconds, neutral messages | No notification distinguishes match from no match | D2 | 3 | WEB-27, CLN-36 | S6 | C27, C28 |
| WEB-08 | US-019, US-021 | Front desk screen: book on behalf, cancel/reschedule on behalf, check-in | Nurse only; check-in outside window shows clear error | D1 | 4 | WEB-05, SCH-20 | S5 | S16 |
| WEB-21 | US-032 | Patient timeline screen: type/range filter, pagination, show partial | Shows warning when document parts missing | D2 | 4 | WEB-20, CLN-23 | S5 | C19 |
| WEB-22 | US-024, US-025 | Encounter form: start, reason, history, vital signs (Nurse) | Per-field errors (value-out-of-range) | D2 | 4 | WEB-21, CLN-17 | S6 | C11, C13, C16 |
| WEB-23 | US-026, US-027, US-028, US-029 | Diagnosis/allergy form, finish encounter, add addendum | Block finish when diagnosis missing | D2 | 4 | WEB-22, CLN-21 | S6 | C14, C15, C17, C18 |
| WEB-25 | US-035, US-037, US-038, US-039, US-040 | Document screen: direct upload via SAS, view via 5-minute link, release | Upload progress, scanning status, handle expired link | D2 | 4 | WEB-21, DOC-07 | S6 | D1, D2, D5, D6 |
| WEB-31 | US-053, US-052 | Knowledge base management and AI cost UI (Admin) | Upload, approve, remove; usage table | SA | 4 | WEB-30, AI-13 | S5 | AI9, AI10 |
| WEB-32 | US-058 | Summary panel for record: AI label, clickable sources to records | Shows structured-timeline mode when LLM fails | SA | 3 | WEB-21, AI-21 | S5 | AI4 |
| WEB-33 | US-059, US-060 | Extraction review screen: highlight fields on source document, edit, accept/reject | Low-confidence fields highlighted | SA | 4 | WEB-25, AI-26 | S6 | AI5, AI6 |
| WEB-34 | US-062 | Booking chatbot widget with confirmation step (Could) | Shows form fallback on 503 | SA | 4 | WEB-04, AI-31 | S6 | AI7, AI8 |
