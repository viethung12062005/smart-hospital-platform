# Runbook: GitHub setup for 4 repos (GOV-01..GOV-06, IAC-03)

> Performed by: SA. Done once in W1, after that every change goes through a PR. Foundational decisions: [ADR-0009](../03-architecture/adr/0009-cicd-ephemeral-environments.md), [ADR-0010](../03-architecture/adr/0010-repository-strategy.md).
> **Do not put secrets** (client secret, key, token) in the repo, variables or workflows: Azure login uses OIDC.

## 1. Create repos (GOV-01)

| Repo | Visibility | Initial content |
|---|---|---|
| `smart-hospital-platform` (already exists under the name `smart-hospital-platform`; rename if desired, GitHub redirects automatically) | private | documents, existing `tools/plan/` |
| `shp-platform-templates` | private | `.github/workflows/` (reusable), `charts/microservice/`, `CHANGELOG.md` |
| `shp-infra` | private | `bootstrap/`, `modules/`, `envs/{uat,prod}/`, `.github/workflows/` |
| `shp-gitops` | private | `bootstrap/`, `platform/`, `policies/`, `envs/{uat,prod}/`, `services/` |

Default branch `main`. Enable for all 4 repos: **Secret scanning + Push protection**, **Dependabot alerts**, **Private vulnerability reporting**.

## 2. Ruleset protecting `main` (GOV-02)

| Rule | Value |
|---|---|
| Require Pull Request | yes; minimum **1 review**; dismiss approval when new commits are pushed; require review from Code Owners |
| Required status checks | app repo: only `pr-gate` (workflow `pr-gate.yml` aggregates the results of `pr-checks`, `contracts`, `libs-java`, every `svc-*`; these workflows are path-filtered so they cannot be required individually); infra: `terraform / checks` + `pr-checks / *`; gitops: `validate` + `checks / *`; templates: `chart / validate` + `checks / *` |
| Block force-push and deletion of branch `main` | yes |
| Require linear history | yes (squash merge) |
| Bypass | only the **GitOps bot GitHub App** on `shp-gitops` for path `envs/uat/**` |

## 3. CODEOWNERS and template (GOV-03)

```
# smart-hospital-platform
/services/scheduling/   @D1
/services/notification/ @D1
/services/clinical/     @D2
/services/document/     @D2
/services/audit/        @D2
/services/ai/ /services/ml/ @D3
/libs/ /contracts/ /.github/ /docs/03-architecture/ @SA
# shp-infra, shp-gitops, shp-platform-templates: all @SA ; shp-gitops/envs/prod/ requires @SA (Architect)
```
(`@D1`, `@D2`, `@D3`, `@SA` are replaced with each person's real GitHub account; do not put emails or personal information in the file.)

PR template (`.github/pull_request_template.md`): task ID, story, API/event contract impact (yes/no), Definition of Done checklist (tests, lint, no secrets, docs).

## 4. GitHub Environments (GOV-04)

| Repo | Environment | Reviewer | Wait timer |
|---|---|---|---|
| app / gitops | `deploy-uat` | SA | - |
| app / gitops | `deploy-prod` | SA (Architect) + SA (DevOps) | 10 minutes |
| shp-infra | `infra-uat`, `infra-prod` | SA | - |
| shp-infra | `infra-uat-destroy`, `infra-prod-destroy` | SA | 5 minutes |

> When the team has only one person holding the SA role, enabling "Prevent self-review" makes the environment impossible to approve: either accept this risk deliberately, or add a developer as a second reviewer for `prod` (recommended for `prod`).

## 5. Actions (GOV-06, CIT-09)

- `shp-platform-templates` -> Settings -> Actions -> Access: **Accessible from repositories owned by the user** so that other repos can call the reusable workflow and pull the chart.
- Every repo: Actions permissions = allow only actions from GitHub and the pinned list; **pin by commit SHA**; `GITHUB_TOKEN` read-only by default, workflows declare least privilege.
- Dependabot for: github-actions, maven, pip, docker, terraform (app repo: `.github/dependabot.yml`) (GOV-06).

## 6. Azure login via OIDC (IAC-03)

Do not store a client secret. Each identity (User-Assigned Managed Identity) has a federated credential by `subject`:

| Identity | Azure permissions | Federated credential `subject` |
|---|---|---|
| `id-shp-ci-plan` | Reader + state read | `repo:<owner>/shp-infra:pull_request` |
| `id-shp-infra-<env>` | Contributor on the environment's resource group + conditional User Access Administrator | `repo:<owner>/shp-infra:environment:infra-<env>` (and `infra-<env>-destroy`) |
| `id-shp-acr-push` | AcrPush | `repo:<owner>/smart-hospital-platform:ref:refs/heads/main` |
| `id-shp-gitops-bot` | (no Azure permissions) | - |

Variables (not secrets) in each repo: `AZURE_TENANT_ID`, `AZURE_SUBSCRIPTION_ID`, `ACR_NAME`, `AZURE_CLIENT_ID_<identity>`.

## 7. GitOps bot (GOV-05)

Separate GitHub App: **Contents: read/write** and **Pull requests: read/write** permissions on `shp-gitops` only; no Actions, Secrets, Administration permissions.
App ID and private key are stored in the **Environment secret** of the app repo (a single place), CI uses them to open PRs or commit image digests into `shp-gitops`.
Rotate the key every 90 days (reminder in `cost-guard`/periodic issue).

### 7.1. Steps to create the App (done in the UI, no API/CLI)

1. GitHub -> **Settings -> Developer settings -> GitHub Apps -> New GitHub App**.
2. Name `shp-gitops-bot`, Homepage is any repo URL; **uncheck Webhook Active** (no webhook needed).
3. Repository permissions: **Contents: Read and write**, **Pull requests: Read and write**; **Metadata: Read-only** (enabled automatically). Do not grant Actions, Secrets, Administration permissions.
4. "Where can this GitHub App be installed": **Only on this account**.
5. **Create GitHub App** -> note the **App ID** -> **Generate a private key** (download the `.pem`, it can only be downloaded once).
6. **Install App** -> select **Only select repositories -> `shp-gitops`**.
7. Store the information in **a single place**, the `deploy-uat`/`deploy-prod` Environment of the application and templates repos:
   - variable `GITOPS_APP_ID` = App ID;
   - secret `gitops-app-private-key` = the entire contents of the `.pem` (including the BEGIN/END lines).
8. Do not put the App ID/private key in the repo, workflows or ordinary variables.

### 7.2. Verification

- `gitops-promote.yml` calls `actions/create-github-app-token` with the `app-id` and the secret above; the log of this step must print only the short-lived token (never the private key).
- Run the workflow in `pr` mode: it must be able to open a PR in `shp-gitops` with the bot as the author (`shp-gitops-bot[bot]`), not a personal account.
- Check least privilege: the bot **cannot** open PRs in other repos and **cannot** read secrets/Actions.

### 7.3. Key rotation

Create a new private key on the App page -> update the secret -> re-run a `gitops-promote` workflow -> delete the old key on the App page. Record the rotation date in the `cost-guard` periodic issue.

## 8. Post-setup checks (feed into ENV-05)

- [ ] Direct push to `main` is blocked in all 4 repos
- [ ] PRs missing a status check or a review cannot be merged
- [ ] The `env-provision`/`env-destroy` workflows stop immediately at the actor check when triggered by someone other than the repo owner
- [ ] The reusable workflow from `shp-platform-templates` can be called from the app repo
- [ ] Unsigned images are rejected by the cluster (GOP-07)

## 9. Actual status and GitHub plan limitations (updated 2026-10-28)

Applied to all 4 repos: ruleset `protect-main` (section 2), squash-only, delete branch after merge, Dependabot alerts, Environments (section 4, no reviewers yet).

| Runbook item | Actual | Workaround |
|---|---|---|
| Secret scanning + push protection | Not available on private repos on the standard plan | `gitleaks` in `pr-checks` replaces it; re-enable when upgrading the plan |
| Required reviewers / wait timer for Environments | Not supported on private repos on the standard plan | Approval gate = PR review (CODEOWNERS); `envs/prod/` approved by SA. The infrastructure workflows (`env-provision`, `env-destroy`) check that `github.triggering_actor` is the repo owner |
| Push images before Azure exists | `svc-*` on `main` only push when the `ACR_NAME` variable has a value | No variable yet: the `not-pushed` job writes a note to the Summary, `promote-uat` is skipped, `main` stays green |
| GitOps bot committing directly to `envs/uat/**` | The `protect-main` ruleset of `shp-gitops` does not yet list the App in its bypass list | When creating the GitOps App: add the App to the bypass list of the `shp-gitops` ruleset, otherwise `promote-uat` (mode `commit`) is blocked |
| `dependency-review`, CodeQL | Require GitHub Advanced Security | Skipped on private repos (templates PR #2) |
| GitHub Project (GOV-07) | The `gh` token lacks the `project` scope | `gh auth refresh -s project,read:project` then import `docs/05-plan/backlog.csv` |
| GitOps bot (GOV-05) | GitHub App not yet created (requires web UI actions) | See section 7 |

### Letting `shp-gitops` read the chart in the private repo (read-only deploy key)
```bash
ssh-keygen -t ed25519 -N "" -C shp-gitops-ci -f ./k
gh repo deploy-key add ./k.pub -R <owner>/shp-platform-templates -t "gitops-ci (read-only)"
gh secret set TEMPLATES_DEPLOY_KEY -R <owner>/shp-gitops < ./k
shred -u ./k ./k.pub   # remove the key from the machine
```
The `validate` workflow of `shp-gitops` already uses this secret (`ssh-key`).
