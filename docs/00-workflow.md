# Workflow: branches, PRs, Definition of Ready/Done (ARC-10)

## 1. The `main` branch is protected
Nobody writes directly to `main` (ruleset `protect-main` in all 4 repos):

| Rule | Value |
|---|---|
| Must go through a Pull Request | yes |
| Minimum reviews | 1, plus Code Owner review (see `.github/CODEOWNERS`) |
| New commit after approval | cancels the previous approval |
| Review threads | all must be resolved before merge |
| History | linear; **squash** merge (merge commits and rebase are disabled) |
| Force-push, deleting `main` | forbidden |
| Exceptions | repo owner only (SA), and still through a PR |

> Current GitHub plan limits: private repos have no required reviewers for Environments and no GitHub secret scanning. The deploy approval gate is replaced by PR review on `shp-gitops` (the `envs/prod/` path is approved by the SA) and `gitleaks` in CI. When upgrading the plan or making the repo public, re-enable it per `docs/04-devops/github-setup.md`.

## 2. Tracking work by User Story

Work is tracked by **User Story (US)**, but code still follows **tasks**: a US consists of several tasks, each done by one person and
with its own PR. There is no shared branch or shared PR for a whole US.

### 2.1 Sources of truth

| Where | What it holds | Who updates it |
|---|---|---|
| [backlog.csv](05-plan/backlog.csv) | Task definitions: ID, story, "Done when", estimate, dependencies | SA (via `tools/plan`) |
| Team planning sheet (Google Sheet, *Tasks* and *By User Story* tabs) | Owner, reviewer, week, status, % | Task owner; SA reconciles at the end of the week |
| GitHub Issues | Daily work: US issues, task issues, PR links, dependencies | Task owner |

If they disagree, fix them in this order: backlog (definition) -> sheet (assignment) -> issue.

### 2.2 Issue structure

| Level | On GitHub | Title | Labels | Assignee | Milestone |
|---|---|---|---|---|---|
| User Story | Parent issue | `US-015: Patient books an appointment` | `user-story`, `priority:must` / `should` / `could` | SA (acceptance owner) | Planned end week |
| Enabler | Parent issue | `ENABLER: Azure infrastructure (Terraform)` | `enabler` | SA | Planned end week |
| Task | **Sub-issue** of the US or of the enabler | `SCH-12: <task name>` | `task` plus a type label (`enhancement`, `documentation`...) | **One person**, matching the sheet's *Owner* column | The task's week |

- A task that belongs to several USs: it is a sub-issue of the primary US (the sheet's *User Story* column); the other USs are noted in the description.
- Dependencies between tasks: use GitHub's **Blocked by** relationship per the *Dependencies* column. The `blocked` label is only used when waiting on something outside the backlog (Azure permissions, a team decision...), and states what it is waiting for.
- Milestones are named by plan week: `Week 6 (09/11-15/11)`.
- US issue description: the user story, acceptance criteria (AC) and related test cases in `docs/06-testing/catalog`. GitHub automatically shows the sub-issue list and progress.

### 2.3 Lifecycle of a task
1. Pick your task issue in this week's milestone that meets the **Definition of Ready** (section 3) and has no open *Blocked by*.
2. `git fetch origin && git switch -c <ID>-short-description origin/main` (for example `SCH-12-book-appointment`). Always branch from `main`, never from another task branch.
3. Code + tests, run the same checks as CI ([CONTRIBUTING](../CONTRIBUTING.md)).
4. `git push -u origin HEAD`, open a PR using the template, title `<story> (<task>): <description>`, and in the Task section write `Closes #<task issue>` and
   `Part of #<US issue>`. Branch, commit, PR and merge details: [00-git-github.md](00-git-github.md).
5. Wait for green CI and 1 review; address feedback with new commits (no force-push after review).
6. The SA or reviewer **squash merges**; the branch is deleted automatically and the task issue closes automatically. The task owner updates the status and % in the sheet.

A task larger than 8 hours is split into several tasks (several issues, several PRs); do not keep one PR open for many days.

### 2.4 A US is not done when only part has merged
Tasks of a US merge into `main` gradually. Merged parts must not break the running flow:
- Incomplete features sit behind the `SHP_FEATURE_<NAME>` flag ([00-conventions section 4](00-conventions.md#4-configuration)), off by default in uat and prod; or the route is not exposed at the gateway or the menu in web.
- New APIs are added to the contract first; clients only call them after the server side has merged.
- The flag is removed in the US's final task, after acceptance.

### 2.5 Closing a US
The SA closes a US issue when:
- Every sub-issue is closed (task done or moved to another US, with the reason recorded).
- Every AC has a test case in the catalog and the automated tests are green on `main`.
- The flow has been tried on the cluster (local or uat) with the feature flag on and synthetic data only; the result is recorded in a comment on the US issue.

### 2.6 Security when using issues
- This repo is **public**: issues, comments and screenshots must use synthetic data only. Never paste real patient data, tokens, passwords, connection strings or logs containing PII.
- Do not open a regular issue for a security vulnerability: report it to the SA via *Security -> Report a vulnerability* (private) and only record the fix task at a general level.
- For a task touching patient data, authorization, audit or AI, the issue states the required controls (authorization, audit, PII masking) so the reviewer can check them.

## 3. Definition of Ready (task can start)
- The related story/FR exists, the "Done when" criteria are clear, the estimate is <= 8 hours (split if larger).
- Dependencies are done or an agreed interface exists (API/event contract in `contracts/`).
- The design documents to read are named (`detailed-design/...`).
- It is clear where the test data comes from (synthetic data generator or seed).

## 4. Definition of Done (can merge)
The checklist in the [PR template](../.github/pull_request_template.md) is this list, item for item: change one and change
the other in the same PR. Every item is ticked before the PR leaves Draft; an item that does not apply is ticked with
"(not applicable: reason)", never left empty.

1. Self-reviewed the diff on GitHub: no debug code, dead code or unrelated changes.
2. Unit + integration tests green; line coverage >= 70%; lint/format clean; no compiler warnings.
3. The contract (`contracts/`) matches the code; `tools/contracts/check_all.sh` is green if anything changed.
4. The endpoint has `@RequireShpAuthz` and a row in the authorization matrix; audit is emitted for every decision; business errors use catalog codes (07 §5).
5. Events are published with `outbox.add(...)` in the same transaction; consumers are wrapped in `inbox.handleOnce(...)`.
6. No secrets, no PII in code/logs/tests; synthetic data only.
7. Image/dependency scans have no High/Critical with an available fix.
8. No change to files owned by the SA (section 5: Dockerfile, pipeline, Helm, `config/PlatformConfig`) without an SA issue.
9. Tried on the local cluster (`make -C dev deploy SVC=<svc>`) if runtime behavior changed.
10. Testing per requirements: every acceptance criterion (AC) of the feature has a test case in `docs/06-testing/catalog` and a corresponding automated test; update `catalog/automation.yaml` and run `python -I tools/qa/trace.py --update-baseline` so the automation level only increases. Test cases involving patient data, authorization, audit or AI (P0) must have automated tests before merge. Bug fixes come with a regression test.
11. Related documentation is updated; the PR is reviewed and squash merged through the pipeline.

## 5. Who changes what
| Area | Changed by | Note |
|---|---|---|
| `services/<svc>/src`, `tests`, `deploy/values-local.yaml` | The service's dev | The Code Owner is that dev, with cross-review by the SA |
| `contracts/`, `libs/`, `dev/`, `.github/`, `Dockerfile`, Helm, `config/` | SA | Devs open an issue if they need a change |
| `shp-infra`, `shp-gitops`, `shp-platform-templates` | SA / DevOps | `envs/prod/` requires SA approval |

## 6. Hotfix
Still through a PR: branch `hotfix-<description>`, fast review by the SA, `hotfix` label; there is no shortcut into `main`.
