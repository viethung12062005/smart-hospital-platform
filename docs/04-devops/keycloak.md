# Keycloak operations (IAM-01)

Decision: [ADR-0017](../03-architecture/adr/0017-keycloak-identity-everywhere.md). Cluster state: `platform/charts/shp-keycloak` in `shp-gitops`.
Azure side (PostgreSQL, workload identity, Key Vault secrets): module `keycloak-infra` in `shp-infra` (IAC-16).

## 1. What runs where

| Item | Local (`dev/`, kind `shp-local`) | uat / prod (AKS, Argo CD) |
|---|---|---|
| Image | `quay.io/keycloak/keycloak:26.8.2` | same tag, pinned by digest in the chart |
| Namespace | `shp-deps` | `shp-iam` (PSA restricted, default-deny NetworkPolicy) |
| Replicas | 1, `start-dev`, H2 | uat 1, prod 2 (`envs/prod/keycloak.yaml`), `start`, clustering over DNS_PING (`keycloak-headless`) |
| Database | embedded | dedicated PostgreSQL Flexible Server of IAC-16 (not shared with scheduling) |
| Public address | `http://localhost:18080/realms/shp` | `https://<gateway.hostname>/idp/realms/shp` through the shared Envoy Gateway |
| Realm | `dev/chart/shp-local-deps/files/realm-shp.json` | `platform/charts/shp-keycloak/files/realm-shp.json` |
| Users | fake users from `make seed-users` | none in git; staff are invited by an admin, patients self-register (IAM-03/08) |
| Secrets | random, in `dev/.local/` | Key Vault `keycloak-db-password`, `keycloak-admin-password` through the Secrets Store CSI driver |

Version pin: Keycloak 26.8.2 is the version the local chart, the container tests and the cluster all run. Kubernetes
service-account client authentication (IAM-02) and standard token exchange v2 (IAM-07) are enabled by default there; they do
not exist in 26.0, which the local chart used before IAM-01.

## 2. Realm as code: one source, two outputs

```
identity/realm/base.json           everything every environment shares (roles, clients, token lifetimes, policies)
identity/realm/overlay-local.json  local only: localhost redirect URIs, the dev client shp-test, sslRequired=none
identity/realm/overlay-prod.json   cluster: redirect URI https://${SHP_PUBLIC_HOST}/*
        |  python3 -I tools/identity/realm.py
        v
dev/chart/shp-local-deps/files/realm-shp.json      local realm (generated, committed)
identity/realm/dist/realm-shp-prod.json            cluster realm (generated, committed) -> copied to shp-gitops
```

- Never edit a generated file. Edit `base.json` or an overlay, run the generator, commit the three changes together.
- `python3 -I tools/identity/realm.py --check` (CI job `identity`) fails when a generated file drifts. The check also fails
  when the cluster realm contains `users`, `credentials`, a literal password/secret/value, `localhost` or `shp-test`.
- `python3 -m pytest tools/identity` tests the merge and that local and cluster realms differ only in redirect URIs.
- Secret-like values are `${PLACEHOLDER}`s that Keycloak replaces from the pod environment at import. In `shp-gitops` the
  copy is checked by `tools/validate.sh` (`APP_DIR=<this repo>` also compares it with the generated file).
- Realm behaviour is proven against a real Keycloak: `KeycloakRealmAsCodeTest` (cluster realm, imported with the same
  environment variable the chart sets) and `KeycloakTokenTest` (local realm), both in `libs/java/shp-security`.

### Changing the realm in a running environment
`--import-realm` imports only when the realm does not exist. A new database (every ephemeral environment, ADR-0009) is
therefore rebuilt exactly from code. For an environment whose database already holds the realm, a merged realm change is
**not** applied automatically: apply it with `kcadm.sh` through `kubectl port-forward` or recreate the realm (this deletes its
users, so only in uat before real use). A reconcile job (keycloak-config-cli with a dedicated client in `master`) is the
planned follow-up; it needs an admin service client that does not exist yet.

## 3. Network and the admin console (ADR-0017, IAC-16)

- The `/idp` HTTPRoute (namespace `shp-apps`, attached to the `https` listener) routes only
  `/idp/realms/shp/{protocol/openid-connect,.well-known,login-actions,account}`, `/idp/realms/shp` and `/idp/resources`.
  The admin console and REST API (`/idp/admin`), the master realm, dynamic client registration, health and metrics are not
  routed: a request for them reaches the web app, never Keycloak. The route is the only path control; pods in `shp-apps` can
  still reach `keycloak:8080/idp/admin` directly (section 9).
- NetworkPolicy in `shp-iam`: default-deny both ways; ingress 8080 only from `envoy-gateway-system` (public route) and
  `shp-apps` (token validators fetch the signing keys), 7800 only between Keycloak pods; egress only DNS, the other
  replicas, PostgreSQL (`database.egressCidr`) and HTTPS to public addresses for the service-account JWKS fetch (IAM-02).
- Admin access: `kubectl -n shp-iam port-forward svc/keycloak 8080:8080`, then `http://localhost:8080/idp/admin`. Access is
  controlled by Kubernetes RBAC. A fixed admin IP range can be added with `networkPolicy.adminCidrs` once an internal address exists.

## 4. Database authentication (decision of IAM-01)
Stock Keycloak connects to PostgreSQL with a password; Entra (workload identity) sign-in needs the Azure JDBC authentication
plugin inside the image. Decision: **password mode** (`keycloak_values.database.auth = "password"` in IAC-16): the password is
generated by Terraform, stored only in Key Vault, mounted by the CSI driver and read as `KC_DB_PASSWORD`. Moving to Entra sign-in
is a later change that needs a custom image (it comes with IAM-03, which builds one for the theme).

## 5. Bring-up checklist (uat / prod)
1. IAC-16 applied; `terraform output keycloak_values` copied (non-secret fields) into `shp-gitops` `envs/<env>/keycloak.yaml`.
2. `envs/<env>/services.yaml`: `OIDC_AUTHORITY=https://<gateway.hostname>/idp/realms/shp`, and per service
   `OIDC_AUDIENCE=svc-<service>` for every service that receives service tokens (the gateway keeps `shp-api`; every
   service accepts `shp-api` as well, see section 7).
3. Argo CD syncs the `keycloak` Application (wave -1). First start runs the Quarkus build, then imports the realm (2 to 4 minutes).
4. Log in through `port-forward` with the `admin` account, create a permanent administrator with MFA, then disable the bootstrap account.

## 6. What is not verified on a real cloud
The chart was rendered, validated and run on a private kind cluster with a throwaway PostgreSQL. Not verifiable without Azure:
the Key Vault CSI sync, workload identity, the private endpoint path to the managed PostgreSQL, the Envoy Gateway route and
certificate, and Argo CD sync ordering.

## 7. Service accounts for /internal (IAM-02)

**Decision: the Kubernetes ServiceAccount token, not a rotating secret.** Keycloak 26.8 ships "Signed JWT - Federated" client
authentication with an identity provider of type `kubernetes` as a supported, default-on feature (`client-auth-federated`,
`kubernetes-service-accounts`; verified through `/admin/serverinfo`: neither is listed as preview or disabled). A service pod
mounts a projected token whose audience is the realm issuer; Keycloak verifies it against the cluster's OIDC issuer keys and
finds the client by (issuer, `sub` = `system:serviceaccount:shp-apps:<service>`). Consequences:
- No client secret is stored anywhere (Key Vault, Kubernetes, Argo CD), so the 90-day rotation duty disappears; the kubelet
  rotates the token (about an hour) and the Python client re-reads the file on each request (`shp_platform.client_auth`).
- A pod can only become the client bound to its own ServiceAccount: presenting `scheduling`'s token while asking for `svc-notification` is refused.
- Needs: the cluster OIDC issuer reachable by Keycloak over HTTPS (AKS: `oidc_issuer_url` of IAC; the chart opens egress 443),
  a ServiceAccount per service named after it (already the convention), and a projected-token volume in the service pod.
- The fallback (client secret) stays available: set `serviceClientAuth` to `client-secret` in an overlay. Locally this is the
  mode used: Keycloak generates the secret at import, nothing is stored in git; read it with the admin API.

How it is built: `identity/realm/services.json` lists each caller and the services it may call. The generator creates, per
caller, the confidential client `svc-<caller>` (service account only, no browser or password flow) with roles
`internal.<target>` on its service-account user, a `roles` claim, and `aud` = **only** the target services
(`svc-<target>`). In the cluster realm the identity provider `kubernetes` takes its issuer from
`${SHP_K8S_OIDC_ISSUER}` (chart value `kubernetesClientAuth.issuer`).

**Audience is an isolation boundary (ADR-0017).** Two audience classes exist:

| Token | `aud` | Accepted by |
|---|---|---|
| User (interactive, `shp-web`/`shp-test`) | `shp-api` | every service |
| Service (client credentials, `svc-<caller>`) | the target services only (`svc-<target>`), never `shp-api` | the target only |

A service sets `OIDC_AUDIENCE=svc-<name>` and its validator accepts that value or `shp-api` (Java
`ShpJwt.acceptedAudiences`, Python `AuthSettings.accepted_audiences`); a token issued for another service is refused
before any role is read. A leaked service token therefore opens nothing beyond the services that caller may call, and
it cannot be replayed against a user API.

The endpoint side: `require_service(<target>, "svc-<caller>")` (Python `Authenticator.require_service`, Java
`ShpJwt.requireService`) checks `azp` against the allow-list and requires the role `internal.<target>`. Use the guard on
every `/internal` endpoint; the gateway additionally never routes `/internal/**` from outside.
Note: detailed-design 11 names the role of the OTP endpoints `internal.otp`; the code convention and the realm use `internal.<target service>`
(`internal.notification`), so that endpoint should require `internal.notification`.

Tested against Keycloak 26.8.2 in containers: `KeycloakServiceAccountTest` (roles and audiences per service, a service token
refused by another service and by the `shp-api` audience, wrong secret, no password grant, the token carries no user role)
and `KeycloakFederatedClientAuthTest` (cluster realm; a container plays the cluster's OIDC issuer; right ServiceAccount
accepted; another ServiceAccount, wrong audience, expired token, foreign key and a client secret all refused).
`ShpJwtTest` covers the audience rule and the guard without a container; `libs/python/tests/test_security.py` proves the
same behaviour for the Python library.

Not done here: the `microservice` chart (shp-platform-templates) has no projected-token volume, and services do not yet set
`CLIENT_ASSERTION_FILE`; until then a service can only use the secret fallback. Follow-up: add `serviceAccountToken` volume
support to the chart and set the audience to the realm issuer.

## 8. Token exchange for the ai service (IAM-07)

**Variant: standard token exchange v2** (feature `token-exchange-standard:v2`, enabled by default in Keycloak 26.8; `/admin/serverinfo`
lists the legacy `token-exchange` v1 and `token-exchange-delegation` as preview and disabled, so they are not used). Source: Keycloak
documentation "Configuring and using token exchange", section "Standard token exchange" (https://www.keycloak.org/securing-apps/token-exchange),
which states that V2 is "fully supported", V1 is a deprecated preview, that the requesting client must be confidential and have
the "Standard token exchange" switch on, that the subject token must name the requesting client in its audience, that `audience`
must be a client id, and how `audience` filters the `aud` claim.

How it is configured (`identity/realm/services.json`, generated into both realms):
- Only `svc-ai` has `standard.token.exchange.enabled=true`; its `exchange` list (`clinical`, `scheduling`) names the services it may
  exchange user tokens for. Every other client, and the public web client, has the switch off.
- The user's access token must contain `svc-ai` in `aud`, so the web clients (`shp-web`, local `shp-test`) carry an audience
  mapper for every requester in `services.json`. The web app sends the user token to the gateway, which forwards `/api/v1/ai/*` to `ai`.
- The `ai` client requests `audience=svc-<target>` only (`TokenExchanger.exchange(token, target)`). Keycloak refuses an audience
  that is not already available to the client ("Requested audience not available"), so `services.json` puts the exchange targets in
  `svc-ai`'s audience mappers as well. That client-credentials token can name those services but still carries `internal.*` only for
  its `calls`, so `require_service` refuses it where it has no role. `aud` is an isolation boundary: the exchanged token carries the
  target's `svc-<target>` and never `shp-api`.

What the exchanged token looks like (tested): `sub` = the user's `sub`, `azp` = `svc-ai`, `aud` = `[svc-clinical]` (the target
only), `roles` = the user's roles (no `internal.*`), the user's lifetime limits. The matrix therefore applies to the user
unchanged (15 section 7).

Findings and limits:
- A token for a target that is not in the caller's `exchange` list (for example `ml`) is refused: the audience cannot be widened.
- Not verified: `amr`. A staff token needs `amr` containing `mfa`; the realm does not yet force MFA, so no staff token carries it and
  it could not be shown whether the exchanged token keeps it. Re-test when IAM-06/IAM-03 turn MFA on, before `ai` serves doctors in uat.
- Standard v2 does not add an `act` claim (delegation is the preview `token-exchange-delegation`); audit must record `ai` through `azp`.
- Revoking or disabling the user stops new exchanges (the subject token is validated) but an already exchanged token lives until `exp`.

Tested in `KeycloakTokenExchangeTest` (container, Keycloak 26.8.2): sub kept, `aud` = `svc-<target>` only (an exchanged token for
`scheduling` is refused by `clinical`), shared JWT rules, five other service clients and the public client refused, target outside the
exchange list refused, tampered subject token refused, and the client switch is what allows the exchange (off: refused, on: accepted).

## 9. Hardening (IAM-06)

Realm settings (`identity/realm/base.json`, both environments), each proven in `KeycloakHardeningTest` on Keycloak 26.8.2:

| Control | Setting | Notes |
|---|---|---|
| Password hashing | `hashAlgorithm(argon2)`: Argon2id v1.3, Keycloak defaults (m=7168 KiB, t=5, p=1) | Asserted from the stored credential (`type=id`). |
| Password policy | `length(12)`, `maxLength(128)`, `notUsername`, `notEmail`, `passwordBlacklist(breached-passwords.txt)`; no composition rules, no expiry | NIST SP 800-63B-4 as in ADR-0017. `notUsername` rejects a password equal to the username, not one that contains it. |
| Breached list | `identity/realm/breached-passwords.txt`, mounted into Keycloak's `data/password-blacklists/` | Starter list of 45 entries. Replace it with a large breached-password list before real users register. |
| Lockout | `bruteForceProtected`, 5 failures, temporary (60 s growing to 15 min), never permanent | Per account. An unrelated account keeps working. |
| Events | login, failures, password and OTP changes, lockouts kept 90 days; admin events on, without details (no password in them) | Feeds IAM-05. |

Differences from ADR-0017 (stock Keycloak cannot do them; decisions for the SA):
- **Staff password length 14.** A realm has one password policy, so patients and staff share 12. Enforcing 14 for staff needs a custom
  authenticator or a separate realm.
- **IP-based blocking.** Keycloak locks accounts, not addresses. The public route has a per-client-IP rate limit in Envoy instead
  (`BackendTrafficPolicy`, 120 requests per minute); it counts the real client IP only if the load balancer preserves it
  (`externalTrafficPolicy: Local` or a `ClientTrafficPolicy` that trusts the forwarded header), which is not configured here.
- **Staff MFA** (TOTP or WebAuthn at first login) is not part of this task and is not configured; the realm does not issue `amr`.

### Admin console not exposed to the Internet
The `/idp` HTTPRoute is the **only** path control from outside: it lists only the OIDC paths (section 3), so `/idp/admin`, the master
realm, client registration, health and metrics answer 404 without reaching Keycloak, and `tools/validate.sh` fails when a rendered route
would expose them. That was exercised through a real Envoy Gateway 1.9.3 on a private kind cluster: `/idp/realms/shp/...` answered 200
and `/idp/admin/...`, `/idp/realms/master`, `/idp/realms/shp/clients-registrations/...`, `/idp/health/ready`, `/idp/metrics` answered 404.

It is not a network boundary. NetworkPolicy lets the Envoy proxy **and every pod in `shp-apps`** reach port 8080 (the services fetch the
signing keys), and NetworkPolicy has no path rules, so any pod in `shp-apps` can call `keycloak:8080/idp/admin` directly. What protects the
admin API there is the admin credential itself: a named administrator account with MFA, no service has one, and the bootstrap account is
disabled (checklist step 4). Human access is `kubectl -n shp-iam port-forward svc/keycloak 8080:8080`, which Kubernetes RBAC controls.
A path-level policy (or a separate management port) that would keep `/idp/admin` away from `shp-apps` is not configured; that is a
follow-up, not a property of the current setup.

### Backups and restore
- **Cloud:** the managed PostgreSQL server of IAC-16 keeps automated backups and point-in-time restore (retention `backup_retention_days`,
  7 by default, geo-redundant in prod); it is a separate server from scheduling so a restore never rolls scheduling back. Restore:
  `az postgres flexible-server restore --source-server <iam-server> --name <new-server> --restore-time <UTC time>`, then point `database.host`
  of `envs/<env>/keycloak.yaml` at the new server (Argo CD rolls the pods). **Not tested on Azure** (nothing is applied there yet); do a drill
  in uat and record the time against the RTO (AC-16.2).
- **Logical, tested locally:** `KeycloakBackupRestoreTest` runs Keycloak on PostgreSQL A, creates a user, dumps A with `pg_dump -Fc`, destroys A,
  restores the dump into an empty PostgreSQL B with `pg_restore`, starts Keycloak B on B with no realm import and no bootstrap admin, and checks that
  the realm settings, the clients, the master-realm administrator, and the user (same `sub`, same password) are all back. Run it with
  `mvn -B -pl libs/java/shp-security -am verify`. The same commands work against the managed server through a private connection:
  `pg_dump -h <fqdn> -U keycloak -Fc keycloak > keycloak.dump` and `pg_restore -h <new-fqdn> -U keycloak -d keycloak --no-owner keycloak.dump`.
- What a backup holds: password hashes (Argon2id) and the realm signing keys. Treat dumps like production secrets (encrypted storage, access limited to the IAM operators).
