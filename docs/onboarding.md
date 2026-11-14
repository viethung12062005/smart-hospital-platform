# Getting started - a guide for devs

Goal: in **under 1 hour**, you clone the repo, run the system on your machine, understand which part you own, and open your first PR.

## Step 1 - Set up your machine (15 minutes)
| Requirement | Who |
|---|---|
| Docker (Desktop or Engine), Docker RAM >= 6 GB; on Windows use **WSL2** ([step-by-step install guide](onboarding-windows.md)) | everyone |
| `git`, `make`, `curl`, `openssl` | everyone |
| **JDK 21** (Temurin) + **Maven 3.9**; IDE: IntelliJ IDEA or VS Code (Extension Pack for Java) | everyone |
| **Python 3.12** | SA (ai, ml); ai folks run tools in `tools/` (planning, test traceability, synthetic data) |
| **Node.js 22** (for web screens, WEB-* tasks) | anyone with a WEB-* task |

No need to install kind, kubectl or helm: `make -C dev tools` downloads the right versions. On Ubuntu/WSL2, `bash dev/scripts/setup-ubuntu.sh` installs JDK, Maven, Python and Node.js.

## Step 2 - Run the system (20 minutes)
```bash
git clone https://github.com/viethung12062005/smart-hospital-platform.git && cd smart-hospital-platform
make -C dev quickstart        # build the kind cluster + DB/bus/Keycloak + deploy 8 services (10-15 minutes the first time)
make -C dev apps              # all pods Running
make -C dev port-forward      # keep open; in another window:
curl http://localhost:18000/info
```
Full guide and troubleshooting: [dev/README.md](../dev/README.md).

## Step 3 - Read in order (20 minutes)
1. [Architecture overview](03-architecture/overview.md): read sections 1-4 (system diagram, events, modules, package layout).
2. [CONTRIBUTING.md](../CONTRIBUTING.md): the task -> branch -> PR process, check commands.
3. The README of the service you own: the "What and where devs write" section.
4. Your work: the issue assigned to you on GitHub (this week's milestone) and the *Tasks* tab of the team planning sheet. Each User Story is a parent issue,
   each task is a sub-issue; how it works: [00-workflow section 2](00-workflow.md#2-tracking-work-by-user-story).

## Step 4 - Each person's area

Per-task assignment comes from the team planning sheet and the issue assignee; the table below is only the main scope (updated 2026-11-03).
Devs only write business logic; if you need to change a shared library (`libs/`), `contracts/`, the pipeline or `dev/`, open an issue for the SA.

| GitHub | Main scope |
|---|---|
| `viethung12062005` (SA) | Architecture, `libs/java`, `libs/python`, `contracts/`, CI/CD, infrastructure (`shp-infra`, `shp-gitops`), gateway (GW-*), Keycloak (IAM-*), all AI/ML (`services/ai`, `services/ml`) |
| `qnt124` | scheduling (SCH-*), SHR-26, booking screens, doctor schedule and patient medical record (WEB-03..08, WEB-20, WEB-27, WEB-28, WEB-35, WEB-36) |
| `Her55chers` | clinical (CLN-*), FHIR core (SHR-01, 03, 04, 05, 10, 11), clinical screens (WEB-21..25) |
| `24021501-hunglk` | notification (NTF-*), document (DOC-*), audit (AUD-*), synthetic data (DAT-*), DPO and AI screens (WEB-26, WEB-30..34); AI/ML review |

### Scheduling - `qnt124`
- **Service:** [`services/scheduling`](../services/scheduling) (PostgreSQL + Flyway).
- **Design to read:**
  - [02-db-scheduling](03-architecture/detailed-design/02-db-scheduling.md), [08-api-scheduling](03-architecture/detailed-design/08-api-scheduling.md)
  - [13-events](03-architecture/detailed-design/13-events.md) (E1-E5), [14-sequences](03-architecture/detailed-design/14-sequences.md) (booking)
  - [ADR-0005](03-architecture/adr/0005-booking-consistency.md) (double-booking prevention)
- **First work:** SHR-26 (generate tests from the authorization matrix); SCH-01 -> SCH-02 -> SCH-03 -> SCH-04 -> SCH-05.
- **Ready to use:** `PostgresOutbox`, `PostgresInbox`, `PostgresIdempotency` (beans in `config/PlatformConfig`); `@RequireShpAuthz`, `ShpException`, `CloudEvents.create`.

### Clinical and the FHIR core - `Her55chers`
- **Service:** [`services/clinical`](../services/clinical) (MongoDB).
- **Design to read:**
  - [01-domain-model](03-architecture/detailed-design/01-domain-model.md), [03-db-clinical-document](03-architecture/detailed-design/03-db-clinical-document.md), [09-api-clinical](03-architecture/detailed-design/09-api-clinical.md)
  - [15-authorization](03-architecture/detailed-design/15-authorization.md), [ADR-0003](03-architecture/adr/0003-fhir-native-store.md), [ADR-0018](03-architecture/adr/0018-profile-links-and-claim.md)
- **First work:** the FHIR core in the order SHR-05, SHR-01 (HAPI FHIR parse/serialize) -> SHR-03 -> SHR-04 -> SHR-10 (Mongo resource store) -> SHR-11;
  then the internal `/internal/v1/identity/{subject}` endpoint (every other service needs it to know which `Patient/...` a `sub` is).
- **Ready to use:** `MongoOutbox`, `MongoInbox`, `MongoIdempotency`, `MongoMessaging.inTransaction`, `MongoIdentityLinkClient`.

### Notification, document, audit - `24021501-hunglk`
- **Service:** [`services/notification`](../services/notification), [`services/document`](../services/document), [`services/audit`](../services/audit). All three use MongoDB; document and audit also use Blob (Azurite locally).
- **Design to read:**
  - [04-db-notification-ml](03-architecture/detailed-design/04-db-notification-ml.md) section 1, [11-api-notification-audit-ml](03-architecture/detailed-design/11-api-notification-audit-ml.md)
  - [10-api-document](03-architecture/detailed-design/10-api-document.md), [05-storage-audit-blob](03-architecture/detailed-design/05-storage-audit-blob.md), [13-events](03-architecture/detailed-design/13-events.md)
  - [00-synthetic-data](00-synthetic-data.md) for DAT-*
- **First work:** DAT-01 -> DAT-02; NTF-04, NTF-05, NTF-06 -> NTF-07 -> NTF-10; then the NTF-02 and NTF-03 consumers (wrap with `inbox.handleOnce`).
- **Note:** never log passwords, OTP codes, phone numbers or tokens; audit contains no clinical content; audit is WORM with no API to modify or delete.

### Platform, gateway, AI/ML - `viethung12062005` (SA)
Shared libraries, contracts, pipeline, infrastructure, Keycloak and gateway; all of `services/ai`, `services/ml`. Devs who need changes in these areas open an issue.

### Web screens (anyone with a WEB-* task)
Each person builds the screens for their own backend area per the table above. The skeleton already exists: login, role-based menu, API client, design system, 6 sample screens.
- **Read:** [web/README.md](../web/README.md) (how to run, structure, 8 rules), [ADR-0016](03-architecture/adr/0016-frontend-web-app.md).
- **Run quickly without a backend:** `cd web && npm ci && npm run dev:mock`, open http://localhost:5173 and choose a role.
- **Start from the nearest sample screen:** booking (`pages/patient/BookAppointmentPage.tsx`), queue (`pages/staff/QueuePage.tsx`),
  patient medical record (`pages/clinical/PatientDetailPage.tsx`). If a backend endpoint does not exist yet, add a handler in `src/mocks/handlers.ts` that follows the contract.

## Step 5 - First PR
Pick the first task in your list and follow [CONTRIBUTING.md](../CONTRIBUTING.md#one-task-workflow):
1. Create branch `<ID>-description` from `origin/main`.
2. Read and update the design document first, then write code and tests.
3. Run the same checks as CI (`mvn ... verify`, `pytest`, `npm run ...`) **and** try it on the local cluster: `make -C dev deploy SVC=<svc>`.
4. Open a PR using the template, noting `Closes #<task issue>` and `Part of #<US issue>`.

If you get stuck on infrastructure, the pipeline or shared libraries: open an issue for the SA, do not change them yourself.
