# ADR-0017: Keycloak is the single identity provider for application users, in every environment

## Status
**Accepted (2026-10-31)**: SA decision after reviewing the patient registration and login flow. Replaces the "Entra ID in the cloud" part of [ADR-0004](0004-azure-first-portable.md), [ADR-0015](0015-java-for-all-services.md), and [ADR-0016](0016-frontend-web-app.md). Does not change how Entra ID is used to administer Azure (see "Scope").

## Context
The previous design used two identity providers (IdPs): Keycloak locally and Microsoft Entra ID in the cloud. When describing the "new patient registers" flow end to end, there were three problems:

1. **Experience does not fit Vietnamese patients.** Patients were redirected to the Microsoft login page to create an account and set a password, then returned to the web app to fill in their profile. Two "registrations" in two places, with a UI not controlled by the hospital. Vietnamese users are used to identifying with a **phone number** (receiving an OTP), not a Microsoft account.
2. **Regular Entra ID is for people inside an organization.** External patients need a separate service (Entra External ID) requiring extra configuration; the design had not settled this part.
3. **Two IdPs, two sets of errors.** Entra-related errors only surface in UAT (ADR-0011 already noted the risk), and every login change must be tested twice.

## Decision
**Use Keycloak for all application users (patients and staff) in local, UAT, and prod.** Backend code does not change because it is still standard OIDC; only the IdP location and configuration change.

| Item | Decision |
|---|---|
| Operation | Keycloak runs in the cluster, separate namespace `shp-iam`, 2 replicas in prod, deployed via GitOps. Realm `shp` managed as code (`realm-shp.json` already exists for local). Served on the same domain as the web app at path `/idp` (through the Gateway), HTTPS only: no CORS needed and CSP allows only `'self'` |
| Patient registration | Self-registration directly on Keycloak's page, with Vietnamese and English UI designed by the hospital. Login and registration with **phone number or email** (choose one when creating the account): phone verified by an **OTP code sent via Zalo first, SMS after** (same code, automatically switches to SMS when Zalo fails or takes over 30 seconds), email verified by a code sent via email. Sending and checking codes is handled by the `notification` service (APIs N8, N9) so Keycloak and the record claim flow share one place (ADR-0018). Patient profiles still require a **phone number verified by OTP** (reminders, deduplication): accounts created with email verify the phone number in the profile step. **Do not collect national ID at registration**: the national ID number is added by a nurse when the patient arrives (SRS UC `supplement-national-id`) |
| Patient passwords | Minimum 12 characters, no special-character requirement, reject passwords on breached lists, no forced periodic change (per NIST SP 800-63B-4). Brute-force protection: temporary lock after 5 consecutive failures, IP-based blocking |
| Staff | Admin invites by email; password minimum 14 characters; **second factor other than SMS required** (TOTP authenticator app or WebAuthn key) at first login. No MFA bypass path |
| Tokens | Unchanged: Authorization Code + PKCE, access token ≤ 15 minutes, `roles`, `amr` claims to check MFA. `fhirUser` still not in the token but looked up via `identity_links` (ADR C7) |
| Service-to-service (`/internal`) and AI on behalf of a user | Service accounts in Keycloak with client credentials; AI uses RFC 8693 token exchange. Client authentication: prefer a signature using the Kubernetes ServiceAccount token if the Keycloak version supports it, otherwise a client secret in Key Vault rotated every 90 days (settled in IAM-02) |
| Password storage | **Only Keycloak stores them**, as salted slow hashes (Argon2id, the default in recent Keycloak). Business applications and other services never see passwords |
| Keycloak database | Dedicated PostgreSQL for IAM, not shared with scheduling (isolation and independent recovery). DB authentication settled in IAM-01 |

### Scope: where Entra ID is still used
Entra ID **does not** disappear from the project; it is simply no longer where patients and hospital staff log in to the application:
- Azure administration: AKS login, operator Argo CD login, Azure RBAC permissions.
- Workload identity (managed identity) for pods to access PostgreSQL, MongoDB, Redis, Service Bus, Storage, AI Search, Key Vault.
- Grafana admin login.

## Alternatives considered
| Option | Conclusion |
|---|---|
| Entra External ID for patients, Keycloak for local (old design) | Rejected: experience limited by the service's customization, two IdPs, configuration not settled |
| Hybrid: Entra for staff, Keycloak for patients | Rejected: still two IdPs and two MFA flows; hospital staff do not have an Entra tenant available |
| Build our own login and password storage | Rejected: security risk too high for medical data, not enough time |
| Another identity service (Auth0, Cognito...) | Rejected: extra provider, per-user cost, no clear advantage over Keycloak |

## Consequences
**Positive**
- One IdP, one test suite, same behavior in local, UAT, and prod. Login bugs surface immediately on the dev machine.
- Seamless registration flow, in Vietnamese, by phone number; the hospital controls the UI and content.
- No separate Entra tenant for application users, reducing dependence on the Azure subscription during development.

**Costs and risks (the team is responsible)**
| Risk | Mitigation |
|---|---|
| Keycloak is the most sensitive system, now operated by us: patching, backup, HA | Digest-pinned image scanned like other services; dedicated PostgreSQL with backups; Keycloak updates in the monthly patch cycle; restore testing |
| Passwords are now in system scope: a breach triggers 72-hour notification (Personal Data Protection Law) and impact assessment | Update the impact assessment; disk encryption; Argon2id hashing; do not log passwords; account lockout and anomalous-login alerts |
| Keycloak admin UI attacked | Not exposed to the Internet: access only via admin IP range or port-forward; separate admin account with MFA |
| Keycloak has no built-in OTP via Zalo and SMS | Write an extension authenticator (SPI) that calls `notification` (N8, N9); `notification` has Zalo and SMS adapters behind the `OtpChannel` interface, dev environment uses a fake that prints the code in logs. Zalo requires a business account, verification message template, and SMS sender brand registration (reference cost: Zalo lower than SMS, provider decides); current regulations on sender brands must be checked before real deployment |
| OTP has per-message cost; SMS can be SIM-swapped | Prefer Zalo (cheaper, tied to a Zalo account); limit sends per phone number and IP, budget and alerts; OTP only verifies phone ownership, not used as a staff second factor |
| One Keycloak failure makes the whole system unable to log in | 2 replicas, health checks, KEDA/HPA; accept that there is no fallback login path bypassing MFA (noted in the SRS integration section) |

## Work to do
Add to the plan (SA): **IAM-01** Keycloak on the cluster (chart, DB, realm as code, ingress, hardening, backup), **IAM-02** service accounts and token exchange for `/internal` and AI, **IAM-03** Vietnamese login, registration, and forgot-password UI, **IAM-04** SMS OTP authenticator and SMS provider interface (with fake), **IAM-05** push login, registration, lockout, and password-change events to audit. Change **IAC-16** from "Entra app registration" to Keycloak infrastructure. Update SRS to v0.96 (UC-01, UC-08, NFR-SEC1, integration), detailed design 07 and 15, ADR-0016.
