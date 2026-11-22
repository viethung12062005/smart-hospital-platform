# audit

Receive and query audits, immutable hash chain. Owner: **D2**. Storage: MongoDB (database `audit`).

| Command (run at repo root) | Task |
|---|---|
| `mvn -B -pl services/audit -am verify` | build + test like CI (needs Docker for Testcontainers) |
| `set -a; . dev/.local/connection.env; set +a; mvn -pl services/audit -am spring-boot:run` | run locally with infrastructure from `make -C dev up` |
| `make -C dev deploy SVC=audit` | build image and deploy to the kind cluster |

## What devs write, and where
- Package layout (`docs/03-architecture/overview.md` §4): `api/` (controller) → `app/` (use case, transaction) → `domain/` (rules, no Spring)
  → `infra/` (repository, client); `messaging/` (consumer); SA's `config/`.
- Endpoint: `src/main/java/org/shp/audit/api/*Controller.java`. **Every endpoint** must have `@RequireShpAuthz(resource, action, context)`
  (the `everyEndpointDeclaresItsAuthorizationRule` test goes red if you forget). Input for authorization: the `ResourceContextResolver` bean.
- Business errors: `throw new ShpException("slot-unavailable")` (codes in `contracts` + 07 §5).
- Event: `outbox.add(CloudEvents.create("audit", "shp.<context>.<event>.v1", subject, payload))` **inside the transaction** of the data change;
  contract test: `EventSchemas.validate(...)` empty.
- Consumer: `inbox.handleOnce("audit", event, () -> ...)`.
- Idempotency-Key: `idempotency.claim(subject, IdempotencyKeys.require(header), operation, IdempotencyKeys.hashBody(body))`.
- Platform beans are already configured in `config/PlatformConfig.java` — no need to change.

Docs: `docs/03-architecture/detailed-design/` (the service's API, DB, events) and `docs/05-plan/by-person/D2.md` (tasks).

## Blob WORM audit log (AUD-02)
`WormAuditLog` appends one NDJSON append blob per batch to the `audit` container and chains batches with `prevHash` / `batchHash`
(`docs/03-architecture/detailed-design/05-storage-audit-blob.md` §1.2). Settings (`shp.audit-blob.*`): `AZURE_STORAGE_CONNECTION_STRING`
(local Azurite) **or** `SHP_AUDIT_BLOB_ACCOUNT_URL` (cloud, managed identity, no secret); `SHP_AUDIT_BLOB_CONTAINER` (default `audit`);
`SHP_AUDIT_BLOB_INSTANCE` (chain key, default `$HOSTNAME`). Azurite has no immutability policy, so locally the container is a plain
container; the lock is created by IAC-12 in the cloud.

## Consuming `audit.recorded` (AUD-01)
`auditEvents` (binding `auditEvents-in-0`, queue `shp-events.audit`) validates each `shp.audit.recorded.v1` event, deduplicates by
`eventId` through the inbox and spools the record in the same transaction (collection `audit_spool`); a 1-second timer writes a
batch to `WormAuditLog` when `shp.audit-batch.max-records` (5000) records wait or the oldest is `max-age` (60 s) old. Without blob
settings the service starts, warns once and keeps the records in the spool. Design: `05-storage-audit-blob.md` §1.2.
