# ADR-0012: Re-evaluate the language for each service

## Status
**Superseded (2026-10-28)** by [ADR-0015](0015-java-for-all-services.md): all services moved to Java. Retained for traceability.

## Context
The PM required: "if there is a language that is more optimal for a particular service, use it". Evaluation based on load characteristics (ADR-0001),
NFRs (P1–P8, S1, A1), and the operating cost for a team of 3 devs.

## Evaluation
| Service | Main characteristics | Candidates considered | Conclusion |
|---|---|---|---|
| gateway | HTTP proxy, routing, JWT validation, rate limit | Go, **Envoy Gateway configuration-only** | Keep **.NET + YARP**: added latency < 1 ms, shared auth/OTel libraries with other services. Envoy Gateway remains the cluster entry point (TLS, HTTPRoute) |
| scheduling | Postgres transactions, double-booking prevention, 500 RPS slot search | Go, Java | Keep **.NET**: Npgsql + Native AOT/ReadyToRun sufficient for NFR-P1; Firely SDK for FHIR |
| clinical | FHIR CRUD/search on MongoDB | Java + HAPI | Keep **.NET**: Firely SDK equivalent to HAPI for R4, no need for a second JVM |
| document | SAS issuance, scan status tracking | Go | Keep **.NET**: light load, complete Azure SDK for .NET |
| notification | Queue worker, `SKIP LOCKED`, channel delivery | Go | Keep **.NET**: MassTransit (RabbitMQ ↔ Service Bus) has no equivalent in Go |
| audit | Append-only writes, hash chain, KQL queries | Go, Rust | Keep **.NET**: low throughput, needs a shared event model |
| ai | LLM router, RAG, extraction | TypeScript | Keep **Python**: AI/eval ecosystem, AI Search SDK, OpenAI-compatible/Gemini clients |
| ml | Training, model serving, drift | — | Keep **Python**: scikit-learn/LightGBM, MLflow, pandera |

## Decision
No language changes. Common rationale: adding a third language (Go/Java) creates another pipeline, base image set, and set of
auth/OTel/messaging libraries to maintain, and the 3-dev team would have to learn it, while no service has an NFR that .NET/Python cannot meet.
Optimizations are applied **within** the language instead of changing language:
- .NET images use `aspnet:10.0-noble-chiseled` (no shell, non-root); ReadyToRun/Native AOT can be enabled for scheduling if k6 measurements show it is needed (faster startup when KEDA scales at 07:00).
- Python uses slim, non-root images.

## Consequences
Reconsider if Phase 6 load measurements show that any service misses an NFR because of the runtime (for example, gateway p95 dominated by GC).
