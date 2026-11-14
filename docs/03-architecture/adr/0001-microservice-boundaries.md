# ADR-0001: Microservice boundaries

## Status
Accepted (2026-10-26) — approved by the Solution Architect/PM together with gate G3.

## Context
The brief requires a production-oriented MVP, but the lecturer explicitly said they do *not* want "dozens of microservices".
The team wants to use microservices to demonstrate cloud operations capability.

## Decision
Split by **load characteristics and non-functional requirements**, not by individual FHIR resource:

- `scheduling` — write contention, requires strong consistency.
- `clinical` — read-heavy, most sensitive data.
- `document` — large file I/O, scales with bandwidth.
- `notification`, `audit` — asynchronous workers, must tolerate dependency failures.
- `ai`, `ml` — high latency, token cost, external provider dependencies, different language.
- `api-gateway` — single entry point.

At most 8 deployables. Each service has its own Postgres schema; synchronous communication via REST FHIR,
asynchronous via events.

## Alternatives considered
- **Modular monolith**: cheap, simple; but cannot scale/deploy AI and document independently,
  and cannot demonstrate distributed operations. Kept as a fallback: the Java services share
  common building blocks, so they can be merged if time is short.
- **One service per resource**: too many, violates the brief's guidance.

## Consequences
- Requires distributed tracing (OpenTelemetry) and a transactional outbox.
- No cross-service transactions → use events + eventual consistency (for example, notification).
