# clinical

Patient records, encounters, history search, account linking (FHIR). Owner: **D2**. Storage: MongoDB (database `clinical`).

| Command (run at repo root) | Task |
|---|---|
| `mvn -B -pl services/clinical -am verify` | build + test like CI (needs Docker for Testcontainers) |
| `set -a; . dev/.local/connection.env; set +a; mvn -pl services/clinical -am spring-boot:run` | run locally with infrastructure from `make -C dev up` |
| `make -C dev deploy SVC=clinical` | build image and deploy to the kind cluster |

## What devs write, and where
- Package layout (`docs/03-architecture/overview.md` §4): `api/` (controller) → `app/` (use case, transaction) → `domain/` (rules, no Spring)
  → `infra/` (repository, client); `messaging/` (consumer); SA's `config/`.
- Endpoint: `src/main/java/org/shp/clinical/api/*Controller.java`. **Every endpoint** must have `@RequireShpAuthz(resource, action, context)`
  (the `everyEndpointDeclaresItsAuthorizationRule` test goes red if you forget). Input for authorization: the `ResourceContextResolver` bean.
- Business errors: `throw new ShpException("slot-unavailable")` (codes in `contracts` + 07 §5).
- Event: `outbox.add(CloudEvents.create("clinical", "shp.<context>.<event>.v1", subject, payload))` **inside the transaction** of the data change;
  contract test: `EventSchemas.validate(...)` empty.
- Consumer: `inbox.handleOnce("clinical", event, () -> ...)`.
- Idempotency-Key: `idempotency.claim(subject, IdempotencyKeys.require(header), operation, IdempotencyKeys.hashBody(body))`.
- Platform beans are already configured in `config/PlatformConfig.java` — no need to change.

Docs: `docs/03-architecture/detailed-design/` (the service's API, DB, events) and `docs/05-plan/by-person/D2.md` (tasks).
