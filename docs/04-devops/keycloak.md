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
  routed: a request for them reaches the web app, never Keycloak.
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
