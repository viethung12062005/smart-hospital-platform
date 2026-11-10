# ADR-0002: .NET 10 for business services, Python for AI/ML

## Status
**Superseded (2026-10-28)** by [ADR-0015](0015-java-for-all-services.md): all services moved to Java. Retained for traceability.

## Decision
- Business services: **.NET 10 (LTS)**, ASP.NET Core Minimal API, Npgsql + Dapper (explicit SQL,
  easy to optimize and explain), Firely SDK (`Hl7.Fhir.R4`) for FHIR models and serialization.
- AI/ML: **Python** (FastAPI, scikit-learn/LightGBM, MLflow) because the AI ecosystem is markedly better.

## Alternatives considered
- **Spring Boot + HAPI FHIR**: HAPI is very mature, but .NET integrates naturally with Azure
  (Managed Identity, App Insights) and has small images and fast startup (important when HPA/KEDA scales at 07:00).
- **Node.js/TypeScript**: good FHIR ecosystem (Medplum), but stronger static
  typing helps reduce errors with medical data.
- **EF Core** instead of Dapper: convenient, but JSONB/search-index queries need hand-written SQL.

## Consequences
Two languages → two CI pipelines, two sets of base images that need vulnerability scanning.
