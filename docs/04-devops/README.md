# Phase 4 - Platform & DevOps (plan)

> Status: **Not started**, only kicks off after G3 (architecture approved). Detailed tasks: epics GOV, IAC, CIT, GOP, ENV, OBS in
> [backlog](../05-plan/backlog.md). Foundational decisions: [ADR-0009](../03-architecture/adr/0009-cicd-ephemeral-environments.md), [ADR-0010](../03-architecture/adr/0010-repository-strategy.md).
> Every cloud environment is **ephemeral**: create -> test -> destroy.
> Step-by-step GitHub setup (repo, ruleset, environment, OIDC, bot): [github-setup.md](github-setup.md).
> Goal: complete **the entire pipeline and environment before** the dev team starts writing services.

## Outputs of this phase

| # | Item | Content | NFR |
|---|---|---|---|
| 1 | Repository & branch strategy | Monorepo; trunk-based (`main` + short-lived branches), PRs require review + green CI; CODEOWNERS by directory | NFR-D1 |
| 2 | Azure landing zone (Terraform) | Subscription/resource group per environment, hub-spoke VNet, Private Endpoint for every data store, Key Vault, ACR, Log Analytics; remote state in a locked Storage Account | NFR-D3, D4, SEC7 |
| 3 | Cluster (Terraform) | One ephemeral AKS per environment: 3 zones, system/apps/ai node pools, Workload Identity, Azure CNI overlay + Cilium | ADR-0004 |
| 4 | Platform add-ons (GitOps) | Argo CD, Argo Rollouts, KEDA, cert-manager, ingress, Kyverno, Key Vault CSI, OpenTelemetry Collector, managed Prometheus/Grafana | NFR-A7, SEC6, O1 |
| 5 | CI (GitHub Actions) | Reusable workflow: build -> unit test -> SAST -> build image -> vulnerability scan -> SBOM -> cosign signing -> push to ACR; only build services that changed | NFR-SEC6, D1 |
| 6 | CD (GitOps) | `deploy/` repo/directory holds Helm values per environment; CI updates the image tag; Argo CD syncs; `uat` and `prod` via PR with approval; canary + automatic rollback based on metrics | NFR-A7, D2 |
| 7 | Pipeline security | Azure login via OIDC federated credential (no secrets stored in GitHub); least privilege per environment; GitHub Environments with reviewers | NFR-SEC4 |
| 8 | Baseline observability | Standard dashboards for every service (RED metrics), SLO burn-rate alerts, logs without PII | NFR-O1-O4 |
| 9 | Service template ("walking skeleton") | One sample Java service and one sample Python service going through the whole pipeline: commit -> local K8s -> uat -> prod (temporary) -> rollback -> destroy | Gate G4 |
| 10 | **Local dev environment** | `dev/` toolkit (kind + Helm + Makefile): all dependencies and services run on a small K8s cluster on the dev machine + automatically seeded synthetic data. See [ADR-0011](../03-architecture/adr/0011-two-environments-local-kubernetes.md) | NFR-SEC9 |
| 11 | FinOps | Cost tags + TTL, budget alerts, cost-guard reporting environments alive past their deadline | NFR-C1, C3 |

## Gate G4
The sample services can go from commit to `prod` only through the pipeline; rollback within <= 5 minutes; unsigned images are
rejected by the cluster; the dashboard shows traces across services. Once G4 is met, the dev team starts Phase 5.

> Operational note: Terraform only runs `fmt`/`validate`/`plan` in CI; `apply` to the real Azure is run by
> DevOps in a controlled manner (environment with reviewers).
