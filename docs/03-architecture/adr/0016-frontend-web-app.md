# ADR-0016: Frontend is a single PWA web app (React + TypeScript + Vite + Mantine) in the application repo

## Status
**Proposed (2026-10-29)**: proposed by SA, awaiting team approval at the next review. Supplements [ADR-0001](0001-microservice-boundaries.md), [ADR-0009](0009-cicd-ephemeral-environments.md), [ADR-0010](0010-repository-strategy.md), [ADR-0015](0015-java-for-all-services.md).

## Context
- The brief requires a web interface usable on desktop and mobile for 5 roles (patient, doctor, nurse, admin, DPO).
  The plan already has tasks WEB-01..WEB-34 but has not fixed the technology, has no source directory, and no pipeline.
- The team has a previous PoC portal: an Nx monorepo of about 64,000 lines, 19 apps, MUI + Mantine, about 130 libraries (including web3, Stripe, Firebase),
  password login and calls to a different FHIR server. Using it as-is is heavy, has many dependency vulnerabilities, the wrong login model (we use OIDC + MFA)
  and the wrong API (we have a gateway, `Idempotency-Key`, `If-Match`, Problem Details).

## Decision
| Item | Choice | Reason |
|---|---|---|
| Architecture | **One** web app (SPA, PWA) for all 5 roles, `web/` directory in the `smart-hospital-platform` repo | Same repo as contracts (`contracts/`) so API and UI changes happen in one PR (ADR-0010) |
| Language, build | **TypeScript (strict) + Vite + React 18** | Popular, fast builds; React 18 matches Mantine 7 and reusable components |
| UI | **Mantine 7** + SHP design system (`web/src/styles/tokens.css`, `web/src/theme.ts`) | The PoC portal already used Mantine 7 and the token set (teal, chip, table, KPI, stepper): selective copying works immediately |
| Login | **OIDC Authorization Code + PKCE** using `oidc-client-ts`; Keycloak `shp-web` client in every environment (ADR-0017) | No password stored in the browser; roles from the `roles` claim like the backend |
| API calls | Same origin (`/fhir`, `/api`) through the gateway; **TanStack Query**; one client layer attaches token, `Accept-Language`, `Idempotency-Key`, `If-Match` and parses OperationOutcome / Problem Details errors | No CORS needed; 07-api-conventions applies in one place |
| FHIR types | `@medplum/fhirtypes` (types only, no runtime code) | Do not define R4 types ourselves |
| Code catalogs | Read `contracts/terminology/code-systems.yaml` directly at build time | Single source with the backend |
| i18n | `react-i18next`, Vietnamese default, English | NFR-I2 |
| Test | **Vitest + Testing Library + MSW** (mock server simulating contracts); line coverage ≥ 70% like the backend | Test real screen flows without a backend |
| Run locally | `npm run dev` (proxy `/fhir`, `/api` to the kind cluster gateway) or `npm run dev:mock` (MSW, no cluster needed) | UI devs can work even when backend endpoints are not done |
| Packaging | **non-root nginx** image (uid 10001, read-only root filesystem, security headers, `/healthz`), deployed with the **shared `microservice` chart** | Same path as the 8 services (ADR-0009) |
| Cluster routing | HTTPRoute `/` → web; gateway API only receives `/fhir`, `/api` | One domain, no CORS |
| Per-environment configuration | `/config.json` mounted from a ConfigMap (chart's `configFiles`): issuer, client ID, scope | One image for all environments |
| CI/CD | `svc-web.yml` → `node-ci` (format, lint, type check, test + coverage, build, `npm audit`) → `container` (hadolint, Trivy, SBOM, sign) → `gitops-promote` | Same quality gates as services |

### Reusing the PoC portal
Only **selectively copy** into the new app; do not carry old dependencies, git history, or old names:
- UI shell `AppShell` / `Header` / `Navbar` (original from Medplum, **Apache-2.0**): keep source notes in each file, `web/NOTICE`, and `web/licenses/Apache-2.0.txt`.
- Design token set (colors, typography, teal table headers, status chips, KPI, stepper, timeline).
- Sample UX layouts: doctor search, booking, weekly doctor schedule, queue, encounters, patient summary. These screens are rewritten with Mantine for the SHP API
  (the originals use MUI and a custom client, so code cannot be copied).

## Work split
- **SA** builds the foundation (WEB-01, WEB-02, WEB-10): shell, login, API client, design system, CI/CD, image, chart, GitOps.
- **Dev** writes screens per the backlog (WEB-03 onward), using existing components and client; do not modify `web/src/api/client.ts`, `auth/`, `layout/`, Dockerfile, nginx without asking SA.

## Alternatives considered
- **Use the PoC portal as-is and fix gradually**: rejected because most features are out of scope, over 100 old libraries (many security warnings), password login, and a very different API.
- **Next.js (SSR)**: no need for SEO or server-side rendering; adds a Node runtime to operate and secure. A static SPA on nginx is simpler.
- **React 19 + Mantine 8**: newer but copied PoC components target Mantine 7; upgrade after the foundation is stable (Dependabot tracks it).
- **One app per role**: duplicates shell, login, client; increases pipelines. One app with role-based routes is enough (actual permissions are still enforced by the backend).
- **Separate frontend repo**: contracts and UI drift more easily; contrary to ADR-0010.

## Consequences
- Adds one image (`web`) to the pipeline, GitOps, and Kyverno (still a signed image from ACR).
- Gateway API changes HTTPRoute from `/` to `/fhir`, `/api` (chart adds `httpRoute.pathPrefixes`).
- Keycloak needs a public `shp-web` client (PKCE) and `shp-api` audience in the realm (IAM-01); realm configuration is code so it is the same in every environment (ADR-0017).
- nginx CSP allows `connect-src https:` (login server) and `http://localhost:*` (local Keycloak); narrow to the exact issuer when a real domain exists.
- Role-based menus and routes are navigation only; **all permission decisions remain in the backend** (`contracts/authz/matrix.yaml`).
