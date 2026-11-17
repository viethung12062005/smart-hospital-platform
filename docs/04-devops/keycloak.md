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
2. `envs/<env>/services.yaml`: `OIDC_AUTHORITY=https://<gateway.hostname>/idp/realms/shp`, `OIDC_AUDIENCE=shp-api`.
3. Argo CD syncs the `keycloak` Application (wave -1). First start runs the Quarkus build, then imports the realm (2 to 4 minutes).
4. Log in through `port-forward` with the `admin` account, create a permanent administrator with MFA, then disable the bootstrap account.

## 6. What is not verified on a real cloud
The chart was rendered, validated and run on a private kind cluster with a throwaway PostgreSQL. Not verifiable without Azure:
the Key Vault CSI sync, workload identity, the private endpoint path to the managed PostgreSQL, the Envoy Gateway route and
certificate, and Argo CD sync ordering.
