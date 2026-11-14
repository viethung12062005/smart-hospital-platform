# Smart Hospital Platform

Cloud Application Development course project: **Project 5 — Smart Hospital Platform**.
A platform supporting the outpatient flow (patient records, doctor schedules, booking, visits, clinical documents,
reminders), protecting sensitive data, with an AI assistant and ML models.

> Use **synthetic patient data** only in every environment.

## Quick start (dev)

1. Read **[docs/onboarding.md](docs/onboarding.md)**: set up your machine, run the system, who does what.
2. Run the whole system locally: `make -C dev quickstart` (details: [dev/README.md](dev/README.md)).
3. Task -> branch -> PR workflow and check commands: **[CONTRIBUTING.md](CONTRIBUTING.md)**.

| Directory | Contents |
|---|---|
| `services/` | 8 services: gateway, scheduling, notification, clinical, document, audit (Java 21 / Spring Boot 4), ai, ml (Python 3.12) |
| `libs/java/` | 7 shared libraries (standard errors, JWT, authorization, CloudEvents, outbox/inbox, Idempotency-Key) |
| `contracts/` | API contracts (OpenAPI), events (JSON Schema), authorization matrix, code systems, DB scripts |
| `dev/` | local Kubernetes cluster (kind) + dependencies, `make -C dev ...` commands |
| `docs/` | analysis, requirements, architecture ([diagrams](docs/03-architecture/overview.md)), DevOps, plan |
| `tools/` | service scaffold generation, contract checks, plan generation |

Build and test all Java code: `mvn -B verify` (needs JDK 21, Maven 3.9, Docker).

## Process and status

| Phase | Documents | Status |
|---|---|---|
| 0. Process | [docs/00-delivery-process.md](docs/00-delivery-process.md) | — |
| 1. Business Analysis | [docs/01-business-analysis](docs/01-business-analysis/business-analysis.md) | Approved (G1) |
| 2. Requirements | [FR](docs/02-requirements/functional-requirements.md) · [NFR](docs/02-requirements/non-functional-requirements.md) · [Authorization](docs/02-requirements/access-control-matrix.md) · [Load](docs/02-requirements/workload-analysis.md) · [Traceability](docs/02-requirements/traceability.md) | Approved (G2) |
| 2b. User stories | [79 user stories](docs/02-requirements/user-stories.md) | Approved (G2) |
| 3. Architecture | [Overview](docs/03-architecture/overview.md) · [ADR](docs/03-architecture/adr/README.md) · [Detailed design](docs/03-architecture/detailed-design/README.md) | Approved (G3) |
| 4. Platform & DevOps | [Plan](docs/04-devops/README.md) · [Step-by-step log](docs/04-devops/progress-log.md) · [Run locally](dev/README.md) | In progress: libraries, the 8 service scaffolds and the dev kit are done |
| Project plan | [Plan](docs/05-plan/project-plan.md) · [Backlog](docs/05-plan/backlog.md) · [CSV](docs/05-plan/backlog.csv) · per-person work: [SA](docs/05-plan/by-person/SA.md), [D1](docs/05-plan/by-person/D1.md), [D2](docs/05-plan/by-person/D2.md), [D3](docs/05-plan/by-person/D3.md) | Approved |
| 5. Development | [Onboarding](docs/onboarding.md) · [CONTRIBUTING](CONTRIBUTING.md) | Ready for tasks |
