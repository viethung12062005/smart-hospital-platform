# gateway

The single entry point (Spring Cloud Gateway, WebFlux): routes to 7 services, validates JWT (OIDC), blocks `/internal/**`.
Owner: **D3** (gateway auth rework, from 2026-11-01). Report new routes to D3.

| Command (run at repo root) | Task |
|---|---|
| `mvn -B -pl services/gateway -am verify` | build + test like CI |
| `set -a; . dev/.local/connection.env; set +a; mvn -pl services/gateway -am spring-boot:run` | run locally (port 8080; upstream set via `SHP_UPSTREAM_<SVC>`) |
| `make -C dev deploy SVC=gateway` | build image and deploy to the kind cluster |

- Route table: `src/main/resources/application.yml` (source: `docs/03-architecture/detailed-design/07-api-conventions.md` §1).
- Security: `src/main/java/org/shp/gateway/GatewaySecurity.java` — `/internal/**` is always 403, `/actuator/health/**` and `/actuator/info` are public,
  the rest require a valid JWT (issuer, audience, staff MFA per `shp.auth.*`). Fine-grained authorization lives in each service (`@RequireShpAuthz`).
