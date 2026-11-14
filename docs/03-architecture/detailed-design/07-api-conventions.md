# 07 — API conventions

## 1. Entry point and routing

Clients only call the **API Gateway**: `https://api.<env>.shp.example.org`. The gateway routes according to the table below; every `/internal/*` path
is **not** routed externally (it can only be called inside the cluster, restricted by NetworkPolicy).

| Path | Service |
|---|---|
| `/fhir/Patient*`, `/fhir/Practitioner*`, `/fhir/PractitionerRole*`, `/fhir/Encounter*`, `/fhir/Observation*`, `/fhir/Condition*`, `/fhir/AllergyIntolerance*` | clinical |
| `/fhir/Schedule*`, `/fhir/Slot*`, `/fhir/Appointment*` | scheduling |
| `/fhir/DocumentReference*` | document |
| `/api/v1/registration`, `/api/v1/me`, `/api/v1/clinical/*`, `/api/v1/admin/*` | clinical |
| `/api/v1/scheduling/*` | scheduling |
| `/api/v1/documents/*` | document |
| `/api/v1/notifications/*` | notification |
| `/api/v1/audit/*` | audit |
| `/api/v1/ai/*` | ai |
| `/api/v1/ml/*` | ml |

Two API styles:
- **FHIR REST** (`/fhir/...`): `Content-Type: application/fhir+json`, errors return `OperationOutcome`.
- **Application API** (`/api/v1/...`): for operations not in FHIR (upload, AI, audit, etc.); `application/json`, errors return
  **Problem Details** (RFC 9457, `application/problem+json`) with a `code` field per the catalog in §5.

## 2. Authentication

- `Authorization: Bearer <access token>` OIDC from Keycloak (all environments, ADR-0017). Token lifetime ≤ 15 minutes.
- The gateway verifies the signature, `iss`, `aud`, `exp`; **the service re-verifies** (defense in depth, do not trust headers from the gateway).
- Roles come from the `roles` claim (Keycloak client roles): `patient`, `doctor`, `nurse`, `admin`, `dpo`.
- `fhirUser` (for example `Patient/0192...`) is **not** in the token; the service looks it up by `sub` through clinical
  (`/internal/v1/identity/{subject}`), cached for 60 seconds. Locked accounts are rejected within ≤ 60 seconds (AC-08.1). Details in
  [15-authorization.md](15-authorization.md).

## 3. Headers

| Header | Direction | Rule |
|---|---|---|
| `Idempotency-Key` | request | **Required** for `$book`, `$cancel`, `$reschedule`, creating an upload session (D1), record summary (AI4), extraction review (AI6), chatbot action confirmation (AI8). 8–128 characters (UUID recommended). Stored for 24 hours per (caller, key). Resending the same key and same body → return the old result; same key with a different body → 422 `idempotency-key-reused` |
| `If-Match` | request | **Required** for every `PUT` and state-changing operation (`$cancel`, `$reschedule`, `$check-in`, `$finish`): `W/"<versionId>"`. Missing → 428 `precondition-required`; wrong version → 412 `version-conflict` |
| `ETag` | response | `W/"<versionId>"` on every response that returns a resource |
| `Location` | response | on 201: `/fhir/<Type>/<id>/_history/<version>` |
| `traceparent` | both | W3C Trace Context; the gateway creates it if the client does not send it |
| `X-Request-Id` | response | = trace id, so users can report errors |
| `Accept-Language` | request | `vi` (default) or `en`, for error messages |
| `Retry-After` | response | on 429 and 503 |

## 4. Read, search, pagination

- Read: `GET /fhir/<Type>/<id>` → the resource with `meta.versionId`, `meta.lastUpdated`. A deleted resource or one the caller may not read
  → **404** (does not reveal existence), except when there is a relationship that requires break-glass → 403 `break-glass-required`.
- History: `GET /fhir/<Type>/<id>/_history` (only for resources that store history).
- Search: `GET /fhir/<Type>?<param>=...` → `Bundle` with `type=searchset`.
  - **Search by personal data** (name, phone number, date of birth) **must use `POST /fhir/Patient/_search`** with a body
    of `application/x-www-form-urlencoded`, so PII does not appear in the URL and access log.
  - `_count`: default 20, maximum 100. **keyset** pagination: `Bundle.link[next]` contains an opaque `_page_token`, not
    an offset. `Bundle.total` is not returned (costly), unless `_total=accurate` is sent on an endpoint that allows it.
  - Unsupported parameter → **400** (strict). Each endpoint lists the parameters it supports.
- Application API: `?limit=` (≤ 100) and `?cursor=`; response `{ "items": [...], "nextCursor": "..." }`.

## 5. Errors

| HTTP | When | `code` |
|---|---|---|
| 400 | Syntax error, wrong FHIR profile, unsupported parameter | `validation-failed`, `unsupported-parameter` |
| 401 | Missing/invalid token | `unauthenticated` |
| 403 | No permission | `forbidden`, `no-care-relationship`, `break-glass-required` |
| 404 | Does not exist or the caller is not allowed to know it exists | `not-found` |
| 409 | State conflict | `slot-unavailable`, `slot-has-appointment`, `invalid-state-transition` |
| 412 | Wrong `If-Match` | `version-conflict` |
| 413 | File too large | `file-too-large` |
| 415 | File type not allowed | `invalid-file-type` |
| 422 | Business rule violation | `max-future-appointments`, `outside-booking-window`, `cancellation-too-late`, `idempotency-key-reused`, `duplicate-patient-suspected`, `upload-incomplete`, `confirmation-expired` |
| 428 | Missing `If-Match` / `Idempotency-Key` | `precondition-required`, `idempotency-key-required` |
| 429 | Rate limit exceeded | `rate-limited` |
| 503 | Dependency failure, fallback exhausted | `dependency-unavailable`, `ai-unavailable`, `ai-budget-exceeded` |

The `code` is in `OperationOutcome.issue[].details.coding` (system `https://shp.example.org/fhir/CodeSystem/error`) or in the
`code` field of Problem Details. `diagnostics`/`detail` is a user message according to `Accept-Language`, **containing no
PII or stack trace**.

Example:
```json
{
  "resourceType": "OperationOutcome",
  "issue": [{
    "severity": "error",
    "code": "conflict",
    "details": { "coding": [{ "system": "https://shp.example.org/fhir/CodeSystem/error", "code": "slot-unavailable" }] },
    "diagnostics": "This time slot has just been booked. Please choose another time slot."
  }]
}
```

## 6. Rate limiting (at the gateway, by `sub`)

| Group | Limit |
|---|---|
| Default | 120 requests/minute, burst 30 |
| `$book`, `$reschedule` | 10/minute |
| `POST /fhir/Patient/_search` | 60/minute (anti data scraping) |
| AI (`/api/v1/ai/*`) | 20/minute, at most 3 concurrent streams |
| Registration (`/api/v1/registration`) | 5/hour per IP |

When slots open (07:00) and load exceeds the threshold, the gateway enables a **waiting room**: it returns 429 with `Retry-After` and the queue position (GW-03).

## 7. Timeout and streaming

| Type | Gateway timeout |
|---|---|
| FHIR and normal APIs | 10 seconds |
| AI (non-streaming) | 30 seconds |
| AI streaming (SSE `text/event-stream`) | 60 seconds, heartbeat every 15 seconds |

## 8. Internal API (service ↔ service)

- Paths `/internal/v1/...`, on the service's own port; NetworkPolicy allows only the exact services listed in each
  API document to call.
- Authentication uses the **workload identity** token (client credentials, `aud` = target service app ID) with app role
  `internal.<name>`; do not use user tokens.
- When AI needs to read patient data **on behalf of the user**, AI uses RFC 8693 token exchange
  (Keycloak) to call public APIs, so all of the user's permission checks still apply.

## 9. Health and metrics

`GET /health/live`, `GET /health/ready` (checks DB/bus), `GET /metrics` (Prometheus) on the management port, not through the gateway.

## 10. Versioning

- FHIR: R4 fixed. Application API: version in the URL (`/api/v1`); breaking changes → `/api/v2`, keep v1 for at least
  one sprint.
- Events: version in the name (`...v1`), see [13-events.md](13-events.md).
- Official contract: OpenAPI 3.1 files per service, generated from documents 08–12 (task ARC-06); CI checks that the code matches the contract.
