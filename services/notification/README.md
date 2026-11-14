# notification

Appointment reminders, sending notifications over channels, deduplication. Owner: **D1**. Storage: MongoDB (database `notification`).

| Command (run at repo root) | Task |
|---|---|
| `mvn -B -pl services/notification -am verify` | build + test like CI (needs Docker for Testcontainers) |
| `set -a; . dev/.local/connection.env; set +a; mvn -pl services/notification -am spring-boot:run` | run locally with infrastructure from `make -C dev up` |
| `make -C dev deploy SVC=notification` | build image and deploy to the kind cluster |

## What devs write, and where
- Package layout (`docs/03-architecture/overview.md` §4): `api/` (controller) → `app/` (use case, transaction) → `domain/` (rules, no Spring)
  → `infra/` (repository, client); `messaging/` (consumer); SA's `config/`.
- Endpoint: `src/main/java/org/shp/notification/api/*Controller.java`. **Every endpoint** must have `@RequireShpAuthz(resource, action, context)`
  (the `everyEndpointDeclaresItsAuthorizationRule` test goes red if you forget). Input for authorization: the `ResourceContextResolver` bean.
- Business errors: `throw new ShpException("slot-unavailable")` (codes in `contracts` + 07 §5).
- Event: `outbox.add(CloudEvents.create("notification", "shp.<context>.<event>.v1", subject, payload))` **inside the transaction** of the data change;
  contract test: `EventSchemas.validate(...)` empty.
- Consumer: `inbox.handleOnce("notification", event, () -> ...)`.
- Idempotency-Key: `idempotency.claim(subject, IdempotencyKeys.require(header), operation, IdempotencyKeys.hashBody(body))`.
- Platform beans are already configured in `config/PlatformConfig.java` — no need to change.

Docs: `docs/03-architecture/detailed-design/` (the service's API, DB, events) and `docs/05-plan/by-person/D1.md` (tasks).
