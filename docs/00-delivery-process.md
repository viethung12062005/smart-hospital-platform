# Project delivery process

The project proceeds through phases in sequence. **Each phase has an approval gate**: until the gate is passed, the next
phase does not start. **Nobody writes business code before G3 is approved**: user stories, detailed design
(data model, DB, API, event, authorization) and a backlog broken down to the individual must all be in place. Every decision is traceable back to a requirement (see
[02-requirements/traceability.md](02-requirements/traceability.md)).

```
Phase 1: Business Analysis --G1--> Phase 2: Requirements Engineering --G2--> Phase 3: Architecture
   --G3--> Phase 4: Platform & DevOps (pipeline + environments) --G4--> Phase 5: Sprint-based development
   --> Phase 6: Production engineering (load test, failure test, FinOps) --> Defense
```

| Phase | Owner | Outputs | Gate (pass conditions) | Deliverable map |
|---|---|---|---|---|
| 1. Business Analysis | BA | [Business analysis](01-business-analysis/business-analysis.md): problems, stakeholders, as-is/to-be processes, business rules, KPIs, scope | **G1**: stakeholders agree on scope and KPIs | P1 |
| 2. Requirements Engineering | BA + Solution Architect | [FR/use cases](02-requirements/functional-requirements.md), [NFRs with numbers](02-requirements/non-functional-requirements.md), [authorization matrix](02-requirements/access-control-matrix.md), [workload analysis](02-requirements/workload-analysis.md), [traceability](02-requirements/traceability.md), **[74 user stories](02-requirements/user-stories.md)** | **G2**: every FR has acceptance criteria; every NFR has a metric and a way to measure it; every FR AC belongs to a user story | P1 |
| 3. Architecture & detailed design | Solution Architect | [Architecture](03-architecture/overview.md), [ADRs](03-architecture/adr/README.md), **[detailed design](03-architecture/detailed-design/README.md)** (domain model, DB, API, events, sequences, authorization), threat model, **[per-person backlog](05-plan/project-plan.md)** | **G3**: every ADR Accepted; every API/event in the design has a task; every story has tasks; the DDL has been run | P1, P2, P3 |
| 4. Platform & DevOps | DevOps | IaC for 2 cloud environments (uat, prod), CI/CD, secret management, baseline observability, service template ("walking skeleton") running through the whole pipeline | **G4**: a sample service goes from commit -> local -> uat -> prod and can be rolled back | P3 |
| 5. Development | Dev team | Services per the [backlog](05-plan/backlog.md) (per-person tasks in [by-person](05-plan/by-person/)), every story has tests and is deployed through the pipeline | Definition of Done (see below) | P2, P5 |
| 6. Production engineering | Whole team | Load test, failure test, SLO dashboards, cost report, AI eval | NFRs met or explained | P4, P5 |

## Environments (design details in Phase 4)

| Environment | Purpose | Who deploys | Data |
|---|---|---|---|
| `local` | Developer's personal machine, small Kubernetes cluster (kind) running the whole system | Dev | Synthetic, automatic seed |
| `uat` | Cloud (ephemeral): integration testing, acceptance, load/failure tests | Pipeline + DevOps approval | Large-scale synthetic |
| `prod` | Cloud (ephemeral): **provisioned only for demo/defense** | Pipeline + DevOps and Architect approval | Synthetic |

`uat` and `prod` are both **ephemeral**: provisioned with IaC when needed and destroyed when done ([ADR-0009](03-architecture/adr/0009-cicd-ephemeral-environments.md), [ADR-0011](03-architecture/adr/0011-two-environments-local-kubernetes.md)). Devs test on their own machines.

> All data in every environment is **synthetic data**. Never use real patient data.

## Definition of Done (for devs in Phase 5)
- The story traces to at least one FR/NFR; acceptance criteria are verified by automated tests.
- Tests pass in CI; no High/Critical vulnerabilities from image/dependency scans.
- Traces/metrics/logs follow the common standard; new endpoints are present in the API contract.
- Runs on the local K8s cluster (`make up`) and is green in CI (integration tests with Testcontainers). After Gate G4: deployed to UAT through the pipeline (no manual deploy) when the SA provisions UAT.
