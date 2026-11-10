# ADR-0003: Build our own FHIR store, core independent of the DB

## Status
Accepted (2026-10-26) — approved by the Solution Architect/PM together with gate G3.

## Context
The team wants a FHIR-native model like Medplum (the healthcare interoperability standard). Azure has a managed FHIR service
(Azure Health Data Services). The services use different data stores (ADR-0008).

## Decision
The `SmartHospital.Fhir` building block contains the **DB-independent** part:
- Serialize/parse FHIR R4 (HAPI FHIR structures, ADR-0015).
- Define SearchParameter and extract search values from a resource (`SearchParameterRegistry`).
- Parse FHIR queries (`status=free&start=ge2026-11-01`) into typed criteria.
- `IFhirStore` / `IFhirSession` contracts: read, create, update with `versionId` (optimistic
  concurrency), search, outbox event, idempotency, commit.

Each data store is a provider:
- `SmartHospital.Fhir.Postgres` (for `scheduling`): **one table per resource type**, with typed columns for search/constraint fields and `content jsonb` holding the full resource; business constraints live in the DB (exclusion constraint, unique partial index).
- `SmartHospital.Fhir.Mongo` (for `clinical`, `document`): **one collection per resource type**, the document includes the administrative part, `resource` (FHIR), and `search` (normalized fields); shard key `patientId`.

Choose a separate table/collection per resource (instead of a shared EAV-style search-index table) because queries use indexes
directly, have real DB constraints, and are easy to explain. Details: `03-architecture/detailed-design/02` and `03`.

## Alternatives considered
- **Azure Health Data Services FHIR**: managed, fully standard; but locks into Azure, cannot control
  the booking transaction (slot + appointment + outbox), high fixed cost for a capstone project.
- **HAPI FHIR server**: complete but heavy (JVM, hundreds of tables), hard to explain during the defense.
- **Pure relational tables**: easy to query but loses the FHIR conformance the team wants to keep.

## Consequences
- Only supports a subset of FHIR REST (read, create, update, search some parameters, `$book`, `$cancel`).
- Can export/sync to Azure Health Data Services later if interoperability is needed.
