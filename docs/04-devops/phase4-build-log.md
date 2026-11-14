# Phase 4 build log (Platform & DevOps) - for review and redoing

> Step-by-step details in chronological order: [progress-log.md](progress-log.md).
> Purpose: record **each step that has been completed**, the commands to redo it, how to verify it, and what remains. Updated each time a step is finished.
> All data is synthetic data. There are no secrets in the 4 repos (Azure login via OIDC, local passwords randomly generated on the machine).
> No `terraform apply` has been run and no cloud resources have been created.

## 0. Repo map (ADR-0010)
| Repo (scratch copy) | Branch | Content |
|---|---|---|
| `smart-hospital-platform-scratch` | `work/initial-baseline` | documents, plan, service code, local `dev/` toolkit |
| `shp-platform-templates-scratch` | same | `microservice` Helm chart, reusable CI/CD workflows |
| `shp-infra-scratch` | same | Terraform bootstrap + uat/prod environments, provision/destroy workflows |
| `shp-gitops-scratch` | same | (no content yet - step 9) |

When moving to the real repos: copy the content, then change the reference `viethung12062005/shp-platform-templates-scratch@work/initial-baseline`
in `.github/workflows/svc-*.yml`, `tools/scaffold/*.py`, `dev/scripts/lib.sh` to the real repo and a version tag (for example `@v0.1.0`).

## 1. Phases 1-3: analysis, requirements, design (G1-G3 approved on 2026-10-26)
| Step | Result | Commit |
|---|---|---|
| BA, FR/NFR, authorization matrix, workload, traceability | `docs/01-*`, `docs/02-requirements/` | before 3cace72 |
| 11 ADRs + architecture | `docs/03-architecture/` | - |
| Detailed design 01-16 (DB, API, event, authz, MLOps) | `docs/03-architecture/detailed-design/` | 3cace72, 4826847 |
| 74 user stories, 317 tasks, split by person | `docs/02-requirements/user-stories.md`, `docs/05-plan/` | c378bfc |

Redo the plan: `python3 -I tools/plan/build.py` (self-checks: every AC has a story, every API/event has a task, dev tasks <= 4h, dependency order, load <= 30h/week).
Verification performed: DDL runs on PostgreSQL 16 (50 concurrent bookings -> exactly 1 success), validator/index/transaction on MongoDB 8, the mermaid diagram parses.

## 2. ADR-0011: only 2 cloud environments (uat, prod) + dev on kind - commit 3a72b78

## 3. GitHub runbook - commit 63d873b
`docs/04-devops/github-setup.md`. **Your work (requires GitHub admin rights)**: `main` ruleset, CODEOWNERS, Environments
(`deploy-uat`, `deploy-prod`, `infra-*`), GitOps bot GitHub App, Variables. Not done yet.

## 4. Templates repo - commits c352248, ab2a514 (+ step 7 below)
- `charts/microservice`: Deployment or Argo Rollout, HPA/KEDA, PDB, NetworkPolicy, HTTPRoute, Workload Identity, Key Vault CSI, `values.schema.json`.
- Workflows: `dotnet-ci`, `python-ci`, `container` (hadolint, Trivy, SBOM, cosign keyless, push to ACR via OIDC), `helm-validate`, `terraform-checks`, `pr-checks`, `gitops-promote`, `release-chart`.
- Actions pinned by commit SHA; Trivy runs from an image pinned by digest.

Redo verification:
```bash
helm lint charts/microservice && for f in charts/microservice/ci/*.yaml; do helm template t charts/microservice -f $f | kubeconform -strict -summary; done
actionlint
```

## 5. Infra repo - commit 3918ddc
`bootstrap/` (state, ACR, managed identity + federated credential, budget), `modules/{network,monitoring,keyvault,aks,environment}`, `envs/{uat,prod}`,
workflows `env-provision` / `env-destroy` (pending approval), `cost-guard`.
Verification: `terraform init -backend=false && terraform validate`, `tflint`, `trivy config` (0 High/Critical). **No apply.**
**Your work**: run bootstrap once with your Azure account, fill in GitHub Variables per `bootstrap/README.md`.

## 6. Local `dev/` toolkit - commit 365405e (+ step 8)
kind cluster + `shp-local-deps` chart (PostgreSQL, MongoDB rs, Redis, RabbitMQ, Azurite, Keycloak realm `shp`). Developer guide: [`dev/README.md`](../../dev/README.md).

## 7. Code skeleton for 8 services (walking skeleton) - commit of this phase
| Component | Path |
|---|---|
| Shared .NET configuration | `global.json` (SDK 10.0.100+), `Directory.Build.props` (nullable, warnings = errors, analyzers), `Directory.Packages.props` (centralized versions), `.editorconfig` |
| Base library | `libs/dotnet/Shp.ServiceDefaults`: ProblemDetails, `/health/live`, `/health/ready`, `/info`, OpenTelemetry (OTLP when `OTEL_EXPORTER_OTLP_ENDPOINT` is set) |
| 6 .NET services | `services/{gateway,scheduling,notification,clinical,document,audit}`: `.slnx`, `src/...Api`, `tests/...Tests`, `Dockerfile` (chiseled, non-root), `deploy/values-local.yaml`, README |
| Gateway | YARP, routing table matching `07-api-conventions.md section 1`, `/internal/*` has no route (tested) |
| 2 Python services | `services/{ai,ml}`: FastAPI, `pyproject.toml` (ruff, mypy strict, pytest-cov, pip-audit), non-root Dockerfile |
| Per-service pipeline | `.github/workflows/svc-<svc>.yml`: CI -> image (scan/SBOM/sign) -> promote to uat (pending approval) |
| Generators | `tools/scaffold/dotnet_service.py`, `tools/scaffold/python_service.py` (do not overwrite existing files) |

Fix in the templates repo: `gitops-promote` attaches the `deploy-<env>` Environment itself (a job calling a reusable workflow cannot declare `environment`, so the approval gate must live in the called workflow).

Redo / verify:
```bash
python3 -I tools/scaffold/dotnet_service.py && python3 -I tools/scaffold/python_service.py
for s in gateway scheduling notification clinical document audit; do (cd services/$s && dotnet format --verify-no-changes && dotnet test -c Release); done
for s in ai ml; do (cd services/$s && pip install -e ".[dev]" && ruff check . && ruff format --check . && mypy . && pytest --cov --cov-fail-under=70); done
docker build -f services/scheduling/Dockerfile .   # context = repo root for .NET
docker build services/ml                            # context = service directory for Python
```
Results: 6 x 3 .NET tests + 3 gateway routing tests green; 2 x 3 Python tests green, ruff/mypy clean; the scheduling and ml images build and run (`/info` 200, user is not root).

## 8. Run the whole system on a dev machine with one command - commit of this phase
| Component | Work |
|---|---|
| `dev/scripts/install-tools.sh` (`make tools`) | download kind v0.29.0, kubectl v1.33.4, helm v3.18.6 into `dev/.local/bin`, verify sha256, re-running does not download again |
| `dev/scripts/deploy.sh` (`make deploy SVC=<svc\|all>`) | build image -> `kind load` -> `helm upgrade --install` the `microservice` chart with `dev/chart/app-values-local.yaml` + the service's values; create connection-string Secrets in the cluster |
| `make quickstart` | tools -> up -> seed-users -> deploy all |
| `make apps / app-logs / undeploy / port-forward` | operate services in the `shp-apps` namespace; gateway at `localhost:18000` |
| Azurite | account `shp` with a randomly generated key (`AZURITE_ACCOUNTS`), does not use the public emulator key |
| Python images | base `mirror.gcr.io/library/python:3.12-slim` (avoids Docker Hub 429) |

Verification performed: shellcheck clean; `install-tools.sh` actually run twice; chart renders for 8 services + `kubeconform --strict`.
**Could not run** `make quickstart` fully on kind (the build environment cannot run kubelet) -> the first person to run it on a real machine should report errors.

## 8b. Decision on 2026-10-27
- ADR-0012 **Accepted**: keep .NET for the 6 business services, Python for ai/ml.
- Local uses **Azurite** instead of MinIO: the same Azure Blob SDK as the cloud. Updated LOC-01, DOC-02, AUD-08 (WORM checked on UAT), ADR-0008/0011.

## 9. Remaining work
| Work | Who | Note |
|---|---|---|
| GitHub setup (step 3), Azure bootstrap (step 5) | You | requires GitHub / Azure admin rights |
| Move the templates/infra repos to private | You | currently public |
| GitOps repo: Argo CD bootstrap, AppProject, ApplicationSet, add-ons, Kyverno, `envs/{uat,prod}` | SA | next |
| ENV-01: install Argo CD in `env-provision` | SA | after the GitOps repo |
| Contracts: `contracts/authz/matrix.yaml`, event JSON schema, OpenAPI, terminology | SA | ARC-06/07/19/20 |
| Shared libraries for devs: auth (JWT + role + identity link), outbox/MassTransit, FHIR helper, Testcontainers test fixture | SA | so devs only write logic |
| Local `full` profile (LOC-08), MFA realm (LOC-02) | SA | |
| Rotate keys exposed in the old PoC repo + purge history | You | outside the scope of the project repos |
