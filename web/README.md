# web — UI for patients, doctors, nurses, administrators and the DPO

A web app (SPA, PWA) for all 5 roles: React 18 + TypeScript + Vite + Mantine 7. Decision: [ADR-0016](../docs/03-architecture/adr/0016-frontend-web-app.md).
Requires **Node.js 22** (`bash dev/scripts/setup-ubuntu.sh` installs it).

## Running

| Want                                                                       | Command (in `web/`)                                                                                                            | Requires                                                                                               |
| -------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------ | ------------------------------------------------------------------------------------------------------ |
| Work on the UI without a backend (recommended while endpoints are missing) | `npm ci` then `npm run dev:mock` → http://localhost:5173                                                                       | Nothing. Pick a role on the login screen; mock data comes from `src/mocks/`                            |
| Run against the real backend on the local cluster                          | `make -C dev up` + `make -C dev deploy SVC=all` + `make -C dev port-forward` (keep it open), then `npm run dev`                | Sign in to Keycloak with the test users (`make -C dev seed-users`, password in `dev/.local/users.env`) |
| Check like CI                                                              | `npm run format:check && npm run lint -- --max-warnings=0 && npm run typecheck && npm run test -- --coverage && npm run build` |                                                                                                        |
| Format code automatically                                                  | `npm run format`                                                                                                               |                                                                                                        |

`npm run dev` forwards `/fhir` and `/api` to the gateway `http://localhost:18000` (change it with the `SHP_GATEWAY_URL` variable), so the browser calls the same origin and no CORS is needed.
Port 5173 is fixed because that is the redirect URI declared for the `shp-web` client in the Keycloak realm.

## Structure

| Directory                                   | Contents                                                                                                                     | Who edits                                              |
| ------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------ |
| `src/pages/<group>/`                        | Screens (patient, staff, clinical)                                                                                           | **Dev** (task WEB-03 onward)                           |
| `src/components/`                           | Shared components: `PageHeader`, `Card`, `StatusChip`, `KpiTile`, `EmptyState`, `ProblemAlert`, `PatientSummary`             | Dev may add; tell SA when changing existing ones       |
| `src/api/`                                  | API-calling layer (`client.ts`) and per-service functions (`scheduling.ts`, `clinical.ts`), code catalogs (`terminology.ts`) | Dev adds new functions; **`client.ts` is owned by SA** |
| `src/auth/`, `src/layout/`, `src/config.ts` | OIDC sign-in, roles, menu, app shell, runtime configuration                                                                  | **SA**                                                 |
| `src/i18n/`                                 | UI strings `vi.ts` (default) and `en.ts`                                                                                     | Dev (add keys to both files)                           |
| `src/styles/tokens.css`, `src/theme.ts`     | Design system: colors, tables, chips, KPIs, slots, weekly schedule                                                           | SA (devs propose via PR)                               |
| `src/mocks/`                                | MSW mock server used by `dev:mock` and tests                                                                                 | Dev adds handlers when adding APIs                     |
| `Dockerfile`, `nginx.conf`                  | Non-root nginx image                                                                                                         | **SA**                                                 |

## Rules when writing screens

1. **Call the API through functions in `src/api/`**, not `fetch` directly: the client attaches the token and `Accept-Language`, and parses errors in the standard format.
2. **Data-changing operations**: create an `Idempotency-Key` with `newIdempotencyKey()` **once per user click**, and reuse it when retrying the same content;
   send `If-Match` (the ETag of the displayed version) for status-changing operations. See `BookAppointmentPage.tsx`, `MyAppointmentsPage.tsx`.
3. **Errors**: display with `<ProblemAlert error={...} />`. The server has already translated the message into the active language; handle specific cases by `error.code` when needed (for example `slot-unavailable`).
4. **UI strings**: always go through `t('...')`, adding the key to `vi.ts` and `en.ts`. Do not hard-code text in JSX.
5. **Code catalogs** (specialties, cancellation reasons...): use `src/api/terminology.ts` (read from `contracts/terminology`), do not type the list yourself.
6. **Permissions**: add the route in `src/App.tsx` with `RequireRole`, and the link in `src/layout/menus.tsx`. This is navigation only; the backend still enforces real permissions.
7. **Data**: mock data only; do not log patient data to the console; do not store patient data in `localStorage`.
8. **Tests**: each screen has a main-flow test in `*.test.tsx` (see `src/pages/screens.test.tsx`, using `renderApp(path, role)`); line coverage ≥ 70%.

## Environment configuration

`public/config.json` (local) or a ConfigMap on the cluster (`shp-gitops/envs/<env>/web/values.yaml`):
`authority` (OIDC issuer), `clientId`, `scope` (default realm scope; the Keycloak realm already attaches the API audience), `apiBase` (empty = same origin).

## CI/CD

`.github/workflows/svc-web.yml`: `node-ci` (format, lint, types, test + coverage, build, `npm audit`) → `container` (hadolint, Trivy, SBOM, signing)
→ `gitops-promote` (uat). On the cluster, the HTTPRoute sends `/` to the web app, and `/fhir` and `/api` to the gateway.

## Provenance

The design system and screen layouts inherit from the previous PoC portal (copied selectively). The `src/layout/` shell is adapted from Medplum (Apache-2.0): see [NOTICE](NOTICE).
