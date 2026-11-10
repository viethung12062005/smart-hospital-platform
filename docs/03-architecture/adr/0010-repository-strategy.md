# ADR-0010: Repository strategy

## Status
Accepted (2026-10-26) — approved by the Solution Architect/PM together with gate G3.

## Context
A team of 4 (1 Solution Architect/PM/DevOps, 3 devs), 8 services, a requirement for a separate pipeline per service,
shared templates, GitOps, and clear permission separation between application code, infrastructure, and deployment configuration.

## Decision: 4 repositories

| Repo | Contents | Who can merge | Reason for separation |
|---|---|---|---|
| `smart-hospital-platform` (current repo) | Application monorepo: `services/*`, `libs/*`, `web/`, `docs/`, caller workflow for each service | Dev (PR + review) | Few services, few people → monorepo reduces coordination cost; pipelines are still separated by path |
| `shp-platform-templates` | Reusable workflows (CI templates) + shared `microservice` Helm chart, versioned release (tag `vX.Y.Z`, OCI chart on ACR) | DevOps | Versioned templates: a template change does not break all services at once; services pin a version |
| `shp-infra` | Terraform: `bootstrap/`, `modules/`, `envs/{uat,prod}`; `env-provision`, `env-destroy`, `cost-guard` workflows | DevOps | Permission to create/destroy infrastructure is separated from dev; the Azure identity only trusts this repo |
| `shp-gitops` | Argo CD bootstrap, platform add-ons, policies, per-service values by environment | Bot (uat) + DevOps (prod) | Source of truth for the cluster; CI writes image tags here without triggering application CI; CODEOWNERS locks `envs/prod` |

## Alternatives considered
- **One monorepo for everything**: simplest, but the GitOps bot committing to the same repo causes CI loops, and
  infrastructure/prod permissions cannot be separated.
- **One repo per service (polyrepo)**: appropriate for large systems, but 8 repos for 3 devs creates overhead
  (shared library syncing, changing multiple repos for one feature); the benefits only appear with many independent teams.

## Consequences
- Reusable workflows in another private repo can only be called when "Accessible from repositories owned by the user"
  is enabled in the Actions settings of `shp-platform-templates`.
- Need a GitHub App (bot) with write access to `shp-gitops` so CI can update image tags.
