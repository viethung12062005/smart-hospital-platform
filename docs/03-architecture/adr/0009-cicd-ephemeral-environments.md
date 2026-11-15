# ADR-0009: CI/CD, ephemeral environments, and approval gates

## Status
> Update: the number of cloud environments and the approval table are replaced by [ADR-0011](0011-two-environments-local-kubernetes.md) (UAT and prod only). The CI/CD, GitOps, Helm, and OIDC parts remain unchanged.

Accepted (2026-10-26) — approved by the Solution Architect/PM together with gate G3.

## Context
- The team does not maintain a continuously running cluster (cost); infrastructure is provisioned only for testing/demo and then destroyed.
- Requirements: a separate pipeline per service using shared templates; deployment with Helm; full checks
  before build/deploy; **every change to an environment must have explicit approval**.
- Related NFRs: D1–D4, A7, SEC4, SEC6, C1.

## Decision

### 1. Ephemeral environments
- `uat` and `prod` are **2 independent Terraform stacks, each with its own AKS cluster** (see ADR-0011), provisioned
  by the `env-provision` workflow and destroyed by `env-destroy`. No environment runs permanently.
- **Permanent** resources (cheap, no patient data): Terraform state, Azure Container Registry,
  managed identity for GitHub OIDC. Located in the `bootstrap` stack.
- Each stack is tagged `shp-env`, `created-at`, `ttl-hours`. The `cost-guard` workflow reports environments
  alive past TTL (warning only; destruction still requires approval).
- Daily development runs **locally** (docker compose / kind) with the same Helm chart.

### 2. CI with GitHub Actions
- **Reusable workflows** (shared templates) for: Java CI (Maven), Python CI (ai, ml), container (build → scan → SBOM → sign → push),
  Helm validate, Terraform checks, GitOps update/promote.
- **One caller workflow per service** (`svc-<name>.yml`) filtered by service directory path, runs only when
  that service changes.
- Mandatory checks before building the image (failure stops the pipeline):
  format/lint → build (warning = error) → unit + integration tests with coverage threshold → vulnerable dependency scan →
  SAST (CodeQL) → Dockerfile lint → secret scan (gitleaks).
- Mandatory image checks: vulnerability scan (block High/Critical with an available patch), SBOM, cosign keyless signing (OIDC).
- Infrastructure: `terraform fmt` → `validate` → `tflint` → security configuration scan (Trivy) → `plan` + cost estimate.
- Manifests: `helm lint` → render per environment → `kubeconform` (schema) → security configuration scan.

### 3. CD with Argo CD (GitOps) and Helm
- All workloads (platform add-ons and services) are described by Helm charts; one **shared `microservice` chart**
  for all services, with each service having only per-environment values files.
- Argo CD is the only way to change cluster state; no one runs `kubectl apply` or `helm install`
  manually (except the Argo CD bootstrap step in the `env-provision` workflow).
- Deploy = change the image tag/digest in the GitOps repo. Rollback = revert the commit.
- `prod` uses Argo Rollouts canary and **pauses for manual promotion**.

### 4. Approval gates
| Action | Mechanism | Approver |
|---|---|---|
| Merge code | PR + green CI + CODEOWNERS review | 1 reviewer (service owner or PM) |
| Deploy `uat` | GitHub Environment `deploy-uat` **and** PR to the GitOps repo | DevOps |
| Promote `prod` | GitHub Environment `deploy-prod` (wait timer) **and** GitOps PR **and** promote canary | DevOps + Architect |
| Provision environment | `env-provision`: plan → Environment `infra-<env>` → apply | DevOps |
| Destroy environment | `env-destroy`: type the exact environment name + Environment `infra-<env>-destroy` | DevOps |

GitHub logs in to Azure using **OIDC federated credentials**, with a separate identity per environment.
No client secret is stored in GitHub.

## Alternatives considered
- **Permanent cluster for non-prod**: convenient for dev but costs fixed money; the team chose local + ephemeral.
- **Push-based CD (GitHub Actions `helm upgrade`)**: simpler, but CI holds write access to the cluster,
  has no drift detection, and rollback is less transparent than GitOps.
- **Azure DevOps Pipelines**: good Azure integration, but the repo is already on GitHub.

## Consequences
- Each environment provisioning takes about 20–30 minutes (AKS + managed data); tests/demos must be scheduled in advance.
- Data in the environment is lost when destroyed, so a synthetic data seed script must run automatically after bootstrap.
- Key Vault, Cosmos DB, and resources with soft-delete must have names with a random suffix, and purge protection must be disabled
  in non-prod so they can be recreated.
