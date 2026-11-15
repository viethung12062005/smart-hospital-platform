# Running the system on a personal machine (a small Kubernetes cluster with kind)

Decision: [ADR-0011](../docs/03-architecture/adr/0011-two-environments-local-kubernetes.md). Devs **do not need a cloud cluster**: dependencies (DB, broker, Keycloak...)
and all services run in a kind cluster on your machine, using the **same Helm charts** as UAT/prod.

## 1. Preparing the machine (once)
| Requirement | Notes |
|---|---|
| 4 CPUs, **16 GB RAM**, 15 GB free disk | profile `core` + 8 services use ~6 GB; profile `full` needs ~5 GB more |
| **Docker** | Windows/macOS: Docker Desktop (Settings → Resources: RAM ≥ 8 GB for `core`, **≥ 12 GB when using `full`**). Linux: Docker Engine |
| Windows | Do everything **inside WSL2 (Ubuntu)** and clone the repo into a WSL directory (not `/mnt/c`). Step-by-step guide and automated setup script: [docs/onboarding-windows.md](../docs/onboarding-windows.md) |
| `git`, `make`, `curl`, `openssl` | available on macOS/Ubuntu (`sudo apt install make curl openssl` if missing) |

**No need to install kind, kubectl or helm yourself**: `make -C dev tools` downloads the pinned versions (kind v0.29.0, kubectl v1.33.4, helm v3.18.6),
verifies sha256 and puts them in `dev/.local/bin` (no sudo, no changes to the machine). The `make` targets use this directory automatically.

To write code (outside the cluster) you also need: **JDK 21** + **Maven 3.9** (6 Java services) or **Python 3.12** (ai, ml).

## 2. Run everything with one command
```bash
git clone <repo> && cd <repo>
make -C dev quickstart
```
This command does the following in order: install tools → create the kind cluster `shp-local` → install PostgreSQL, MongoDB, Redis, RabbitMQ, Azurite, Keycloak →
create test users → build images and deploy **all 8 services** (gateway, scheduling, notification, clinical, document, audit, ai, ml).
The first run takes 10–15 minutes (pulling images); later runs take a few minutes.

Verify:
```bash
make -C dev apps                 # all pods Running
make -C dev port-forward         # keep this window open
curl http://localhost:18000/info               # gateway returns {"name":"gateway",...}
curl -i http://localhost:18000/fhir/Appointment  # goes through the gateway to scheduling
```

## 3. Daily development workflow
| Goal | Command |
|---|---|
| Code quickly, debug in the IDE (recommended) | `make -C dev up` (dependencies only) → `set -a; . dev/.local/connection.env; set +a` → `mvn -pl services/scheduling -am spring-boot:run` (or Run `SchedulingApplication` in IntelliJ/VS Code) |
| Run tests as CI does | `mvn -pl services/<svc> -am verify` (format, `-Werror`, Testcontainers tests, coverage ≥ 70%; Docker required) · `mvn spotless:apply` to auto-format · Python: see `services/ai/README.md` |
| Try a service in the cluster after changing code | `make -C dev deploy SVC=scheduling` (rebuild the image, load it into kind, `helm upgrade`) |
| Redeploy everything | `make -C dev deploy SVC=all` |
| View logs / status | `make -C dev app-logs SVC=scheduling` · `make -C dev apps` · `make -C dev status` (dependencies) |
| Remove a service | `make -C dev undeploy SVC=scheduling` |
| Get an API token | `make -C dev token USER=doctor.demo` (patient.demo, doctor.demo, nurse.demo, admin.demo, dpo.demo) |
| Access the DB | `make -C dev psql DB=scheduling` · `make -C dev mongosh` · `make -C dev redis-cli` |
| Shut down / free RAM | `make -C dev down` (deletes the cluster, in-cluster data is lost; passwords are kept) |

Calling the API with authentication:
```bash
TOKEN=$(make -s -C dev token USER=doctor.demo)
curl -H "Authorization: Bearer $TOKEN" http://localhost:18000/fhir/Appointment
```

## 4. What is inside the cluster
| Component | From your machine | From a service in the cluster (environment variables pre-injected) |
|---|---|---|
| PostgreSQL 16 (db `scheduling`) | `localhost:15432` | `SPRING_DATASOURCE_URL`/`_USERNAME` (the service values-local) + `SPRING_DATASOURCE_PASSWORD` |
| MongoDB 8, 1-node replica set (transactions enabled); dbs `clinical`, `document`, `audit`, `notification`, `ml`, `ai` | `mongodb://localhost:27117/?directConnection=true` | `SPRING_MONGODB_URI` (Java) · `MONGODB_URI` (Python) |
| Redis 7 | `localhost:16379` | `SPRING_DATA_REDIS_HOST/PORT/PASSWORD` |
| RabbitMQ 4 (AMQP / management) | `localhost:35672` / http://localhost:25672 | `SPRING_RABBITMQ_HOST/PORT/USERNAME/PASSWORD` · `RABBITMQ_URL` (Python) |
| Azurite (Blob/Queue emulator, account `shp`) | `AZURE_STORAGE_CONNECTION_STRING` in `connection.env` | `AZURE_STORAGE_CONNECTION_STRING` |
| Keycloak 26, realm `shp` | http://localhost:18080/realms/shp | `OIDC_AUTHORITY` (= localhost issuer), `OIDC_JWK_SET_URI` (keys fetched via in-cluster DNS), `OIDC_AUDIENCE` |
| 8 application services (namespace `shp-apps`) | via the gateway: `make port-forward` → `localhost:18000` | `http://<service name>` (port 80) |

- **Passwords and the Azurite key** are randomly generated per machine and stored in `dev/.local/` (mode 600, gitignored). The repo contains no passwords.
- Ports are exposed only on `127.0.0.1`. Locally, NetworkPolicy and PDB are disabled, and each service has 1 replica (`dev/chart/app-values-local.yaml`).
- `make up` automatically runs `contracts/db/mongo-notification-ml.js` (notification and ml collections, indexes, validators; safe to re-run).
  The `outbox`/`inbox`/`idempotency` collections are created by the Java services themselves at startup.
- Differences from cloud (accepted): Postgres has only one user; Azurite has no user delegation SAS and no WORM; Mongo authentication is disabled.
  Code that depends on these points will only fail at UAT: report to the SA if needed.

## 5. Related files (devs do not need to edit them, except marked parts)
| File | Role |
|---|---|
| `dev/Makefile`, `dev/scripts/*.sh` | the commands above (`make -C dev help` lists them all) |
| `dev/kind/cluster.yaml` | cluster configuration, host port mappings |
| `dev/chart/shp-local-deps/` | dependency Helm chart + Keycloak realm |
| `dev/chart/app-values-local.yaml` | common values for every service when running locally |
| `services/<svc>/deploy/values-local.yaml` | service-specific values (**devs may add** non-sensitive environment variables) |
| `microservice` chart | fetched from the `shp-platform-templates` repo on the first deploy (stored in `dev/.local/templates`); set `SHP_CHART_DIR=<path>` to use an existing clone |

## 6. Troubleshooting
| Symptom | Fix |
|---|---|
| `doctor` reports a port in use | Stop the program using the port (a local Postgres/Redis/Keycloak install), or change the port in `dev/kind/cluster.yaml` |
| `Docker is not running` | Open Docker Desktop / `sudo systemctl start docker` |
| Pod `Pending`/`OOMKilled` | Increase Docker RAM; deploy fewer services (`make deploy SVC=<only what you need>`) |
| The whole cluster disconnects, `docker inspect` shows the node `OOMKilled` | The Docker VM ran out of RAM while bringing up `full` + 8 services at once: increase Docker Desktop RAM, then `docker start shp-local-control-plane` |
| Pod `Not Ready` with `probe ... timed out after 1s` | The machine was overloaded during startup; wait a few minutes, the probe already sets `timeoutSeconds: 5` so it recovers on its own |
| `helm ... timed out` | Slow image pulls: re-run the command that failed (all steps are re-runnable) |
| `deploy` fails with `ErrImageNeverPull` | The image was not loaded: re-run `make -C dev deploy SVC=<svc>` |
| Service pod in `CrashLoopBackOff` | `make -C dev app-logs SVC=<svc>`: usually missing configuration or the DB is not ready |
| Docker Hub `429 Too Many Requests` | `docker login` and re-run (Python images already use the `mirror.gcr.io` mirror) |
| Keycloak `Not Ready` for a long time | The first realm import takes ~1–2 minutes; `make -C dev logs APP=keycloak` |
| Company network blocks tool downloads | Install kind/kubectl/helm yourself at the versions in section 1 and skip `make tools` |
| Want a clean slate | `make -C dev down && rm -rf dev/.local` |

## 7. Profile `full` (LOC-08)
Enable only when a task needs it: `make -C dev up PROFILE=full` (for a cluster previously created with profile `core`: `make -C dev down` then `up` again to get the new ports). Requires **Docker ≥ 12 GB RAM** (core ~6 GB + full ~5 GB) and 25 GB free disk.

Measured on a kind cluster (10/11/2026, Docker VM 15,6 GB): bringing up `core` + `full` uses 6,9 GB; right after startup CPU rises to ~300% for 1–2 minutes (OpenSearch indexing, ClamAV loading signatures, Ollama initializing). If `full` comes up at the same time as the 8 services, the Docker VM may run out of RAM and the kind node gets `OOMKilled` — run `up` first, wait for the pods to be Ready, then `deploy`. After that run `make -C dev pull-models` (once, a few GB) to get `bge-m3` and `qwen2.5:3b`.

| Component | From your machine | From a service in the cluster | Used for |
|---|---|---|---|
| ClamAV | `localhost:13310` (clamd) | `clamav.shp-deps:3310` | document: scanning uploaded files |
| OpenSearch 2 (security disabled) | http://localhost:19200 | `http://opensearch.shp-deps:9200` | ai: search |
| Ollama | http://localhost:21434 | `http://ollama.shp-deps:11434` | ai: local embedding/LLM |
| MLflow | http://localhost:15000 | `http://mlflow.shp-deps:5000` | ml: tracking + registry |
| Grafana + Loki | http://localhost:13000 (user `admin`, password `GRAFANA_ADMIN_PASSWORD` in `connection.env`) | Loki `http://loki.shp-deps:3100` | viewing logs |
| OTel Collector | — | `http://otel-collector.shp-deps:4318` (OTLP/HTTP) | services send traces/metrics/logs; logs go to Loki, traces/metrics are only printed in the collector log |

Variables for services running outside the cluster are in `dev/.local/connection.env` (the "Profile full" block). Fake data for the cluster is created by `make seed-users` and by each service's seed (the SA generates fake data per ARC-11).

## Not yet available (backlog)
- **LOC-02** — complete realm (MFA for staff, additional claims).
- **LOC-02** — complete realm (MFA for staff, additional claims).

> Verification status: `install-tools.sh` has been run for real (download + sha256 check, a second run does not re-download); the chart renders for all 8 services via `kubeconform --strict`;
> the Java images (gateway, scheduling) and Python images build and run (non-root, `/info` returns 200); dependencies have been tried with Docker. **`make quickstart` has not been run end-to-end on a real kind cluster**
> (the docs build environment cannot run kind): the first person to run it on a real machine should report any errors to the SA.
