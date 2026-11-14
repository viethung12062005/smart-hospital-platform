# ADR-0011: Two cloud environments (UAT, prod) and local Kubernetes development

## Status
Accepted (2026-10-27) — replaces the "3 environments" part of [ADR-0009](0009-cicd-ephemeral-environments.md) §1 and the related approval tables.

## Context
The team has only 4 people and a limited cloud budget. Three cloud clusters (dev, staging, prod) is too many for this scale: dev can run entirely
on a personal machine if enough tooling is provided, and staging differs from UAT only in name.

## Decision

| Environment | Where it runs | Purpose | Lifecycle |
|---|---|---|---|
| **local** | Dev's personal machine, small Kubernetes cluster (kind) | Write code, run and test each service with all dependencies, using the **same Helm chart** as cloud | Dev starts/stops anytime |
| **uat** | Ephemeral AKS on Azure | Real cloud integration testing, acceptance, **load test and failure test**, deploy/rollback/backup drills | Provisioned when needed, **destroyed immediately afterwards** |
| **prod** | Ephemeral AKS on Azure | **Started only for demo/defense** (and the rehearsal before it) | Provisioned ~1 hour before the demo, destroyed immediately after the demo |

Design implications:

1. **Only 2 Terraform stacks remain** (`envs/uat`, `envs/prod`). Both are ephemeral (provisioned by `env-provision`, destroyed by `env-destroy`). Permanent resources
   include only state, ACR, and identity (`bootstrap` stack).
2. **Devs do not need a cloud cluster.** The `dev/` toolkit in the application repo (kind + Helm + Makefile) provisions a small cluster with PostgreSQL, MongoDB (replica set),
   Redis, RabbitMQ, Azurite (Azure Blob emulator, same SDK as cloud), Keycloak, OpenSearch (optional), ClamAV, Ollama (optional), MLflow, and runs services using the shared `microservice` chart.
   Devs test on this cluster before creating a PR; CI uses Testcontainers as designed.
3. **UAT is where NFRs are measured.** Load tests (NFR-P/S) and failure tests (NFR-A) run on UAT. When measuring performance, UAT uses **the same size as prod** (`sizing = "prod"`);
   for integration-only testing use a small size.
4. **Prod is ephemerally planned:** because it is only provisioned for the demo, all needed state must be in GitOps and seed scripts: once provisioned it runs, with no dependency on old data.
   Must **rehearse prod provisioning at least once** (W10 or W11) to know the actual timing, and have a backup (screenshots, video, or UAT) if prod provisioning is late.
5. **Release flow:** merge into `main` → CI build, scan, sign image → (after `deploy-uat` approval) update `envs/uat` in GitOps → UAT passes → (after `deploy-prod` approval) update `envs/prod`.
   Argo CD only syncs clusters that exist.
6. **GitHub Environments** simplified: `deploy-uat`, `deploy-prod`, `infra-uat`, `infra-prod`, `infra-uat-destroy`, `infra-prod-destroy`.
7. **Isolation (NFR-D4):** prod and UAT use separate resource groups/subscriptions and separate identities.
8. **MLOps:** canary/automatic retraining is validated in UAT; in prod only the version that passed UAT is needed.

## Alternatives considered
- **Keep dev in cloud:** convenient for devs with weak machines, but costs fixed money and still needs local for fast iteration.
- **Docker Compose instead of local K8s:** lighter but differs from the real environment (cannot test charts, probes, NetworkPolicy, KEDA); small K8s preserves the deployment shape.
- **minikube / k3d:** equivalent; chose kind because it is popular, starts fast, and is easy to use in CI.

## Consequences
- Dev machine needs at least 4 CPU, 16 GB RAM, Docker; two profiles: `core` (~6 GB, enough for most tasks) and `full` (adds OpenSearch, Ollama, ClamAV, MLflow, observability).
- Each dev runs locally, so cloud-dependent bugs (Azure Blob, real AI Search, Service Bus; login already uses Keycloak like local, ADR-0017) only surface in UAT: SA schedules UAT provisioning regularly (proposed every Thursday).
- Cloud cost reduced: pay only for the hours UAT/prod exist.
