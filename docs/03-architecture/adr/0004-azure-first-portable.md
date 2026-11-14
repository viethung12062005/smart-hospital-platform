# ADR-0004: Kubernetes (AKS) as the runtime platform, Azure-first but portable

## Status
Accepted (2026-10-26) — approved by the Solution Architect/PM together with gate G3.

## Context
- The team already has Azure infrastructure; the system must be production-like and runnable on other platforms (NFR-I3).
- Load has a spike at 07:00 (NFR-S1); AI and document need independent scaling (NFR-S4).
- Requires zero-downtime deployment, rollback ≤ 5 minutes (NFR-A7, D2), network isolation between services (NFR-SEC7),
  and running only signed images (NFR-SEC6).

## Decision
**Azure Kubernetes Service (AKS)** for all workloads, operated with GitOps:

| Item | Choice | Satisfies |
|---|---|---|
| Cluster | **One separate, ephemeral cluster per environment** (created for testing/demo then destroyed, see ADR-0009); node pools `system` / `apps` / `ai` (separate taints); spread across 3 availability zones | NFR-D4, A3, S4 |
| Deployment | Shared Helm chart for services + **Argo CD** (GitOps); **Argo Rollouts** for canary with metric analysis | NFR-A7, D2 |
| Autoscale | HPA (CPU/RPS) + **KEDA** (queue length, cron schedule to scale before 07:00) + Cluster Autoscaler | NFR-S1–S3 |
| Ingress | Ingress controller (NGINX or Application Gateway for Containers) + cert-manager; WAF at the edge (Front Door) | NFR-SEC3 |
| Security | Workload Identity + Key Vault CSI driver; NetworkPolicy deny by default; Pod Security "restricted"; Kyverno only allows signed images (cosign) | NFR-SEC4, SEC6, SEC7 |
| Internal mTLS | Lightweight service mesh (Istio ambient or Linkerd); final decision in Phase 4 based on resource cost | NFR-SEC3 |
| Resilience | PodDisruptionBudget, topology spread by zone, readiness/liveness probes, resource requests/limits | NFR-A1, A3 |
| Observability | OpenTelemetry Collector → Azure Monitor (managed Prometheus + Grafana, App Insights) | NFR-O1–O4 |
| Stateful data | **Outside the cluster**, using managed services (PostgreSQL Flexible, Cosmos DB for MongoDB vCore, Redis, Blob); local/portable uses the corresponding charts | NFR-A4, reduced operational effort |

All infrastructure dependencies go through standard protocols or abstractions (OCI, PostgreSQL, MongoDB API, OIDC, OTLP,
Spring Cloud Stream Service Bus ↔ RabbitMQ, `IBlobStore`), so the same Helm chart runs on AKS, other
K8s clusters, and `kind` on a dev machine.

## Alternatives considered
- **Azure Container Apps**: cheap, scale-to-zero, low ops; but limited control over networking, policy,
  service mesh and GitOps, hard to call production-like, and not as portable as standard K8s manifests.
- **One shared cluster, namespaces separated by environment**: cheaper when running continuously, but since it is already ephemeral, separating clusters costs no extra, provides full isolation (NFR-D4), and verifies rebuilding from scratch (NFR-D3).
- **Run the DB in the cluster (operator)**: more portable, but you take on backup/HA yourself, not worth it for a 12-week team.

## Consequences
- AKS is more expensive than Container Apps when running continuously; therefore the cluster exists only during testing/demo (ADR-0009),
  and UAT uses spot nodes when not measuring performance. Cost is reported per environment-hour in the FinOps report (NFR-C1).
- Requires K8s operational capability: the platform (Argo CD, KEDA, Kyverno, OTel, cert-manager) is set up in Phase 4 **before** developers start.
- The number of services still follows ADR-0001: production-like is about how the system is operated, not the number of microservices.
