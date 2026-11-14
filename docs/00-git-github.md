# Git and GitHub conventions (ARC-10)

Applies to all 4 repos: `smart-hospital-platform`, `shp-platform-templates`, `shp-infra`, `shp-gitops`, for every person and every tool
(including AI assistants) that creates commits or PRs. Task process and Definition of Ready/Done: [00-workflow.md](00-workflow.md). Code standards: [00-conventions.md](00-conventions.md).

Summary: **one US per parent issue, one sub-issue per task -> one branch per task from `main` -> small commits in the correct format -> one PR per the template -> review -> squash merge.**

Table of contents: [1. Identification](#1-identification-stories-and-tasks) | [2. Commit](#2-commit) | [3. Branches](#3-branches) | [4. Pull request](#4-pull-request) |
[5. Review](#5-review) | [6. Updating the branch and conflicts](#6-updating-the-branch-and-conflicts) | [7. Merge and after merge](#7-merge-and-after-merge) |
[8. Changes across multiple repos](#8-changes-across-multiple-repos) | [9. Tags and releases](#9-tags-and-releases) | [10. Branch protection and history](#10-branch-protection-and-history) |
[11. Security](#11-security) | [12. Issues and labels](#12-issues-and-labels) | [13. Git configuration on your machine](#13-git-configuration-on-your-machine) |
[14. Machine-generated exceptions](#14-machine-generated-exceptions) | [15. Automated checks](#15-automated-checks)

---

## 1. Identification: stories and tasks

Every change is tied to a task in the [backlog](05-plan/backlog.csv) (or in the project's shared planning sheet for tasks not yet in the backlog,
such as `REQ-*`, `TST-*`; these tasks have no story, so use `ENABLER`). **Do not** reuse another task's ID for unrelated work
(for example `ARC-17` is "Sprint review S5", not Keycloak). The task's `Stories` column gives the story part:

| Task `Stories` column | Story part | Task example |
|---|---|---|
| One or more `US-xxx` | those `US-xxx` | `ML-06` belongs to `US-064` |
| `ENABLER` (platform work with no user story) | `ENABLER` | `ARC-04`, `SHR-05`, `IAC-03` |

Work that has no task yet (CI fixes, configuration changes, cleanup...) must **add a task to the backlog first** (`tools/plan/tasks_*.py`, run
`python tools/plan/build.py`) - possibly in the same PR. Do not use self-invented prefixes.

## 2. Commit

### 2.1 Subject

```
<story> (<task>[, <task>...]): <description>
```

| Part | Rule | Example |
|---|---|---|
| `<story>` | `US-xxx`; multiple stories separated by `, `; consecutive range `US-053..US-056`. Without a story, `ENABLER` | `US-064`; `US-053..US-056`; `ENABLER` |
| `<task>` | Backlog ID, uppercase; multiple tasks separated by `, `; range `AI-06..AI-13`. No wildcards (`IAM-*`) | `(ML-06, ML-07)` |
| `<description>` | English, present tense, imperative, starts with a lowercase verb (`add`, `fix`, `remove`, `document`...), no trailing period | `add no-show scoring consumer` |
| Length | Whole subject <= 100 characters (excluding the `(#N)` suffix added by GitHub); details go in the body | |
| Too long | A PR touching too many stories/tasks to fit 100 characters is a sign the PR is too large: prefer splitting the PR. If it must stay, the subject names the **main** story/task and the rest are fully listed in the body as two lines `Stories: ...` and `Tasks: ...` | `US-066 (WEB-01): add tests for baseline scaffold pages` + body `Stories: US-011..US-014, US-024, US-025, US-031, US-066` |

Correct:
```
US-064 (ML-06, ML-07): add appointment-booked consumer and no-show scoring
US-053..US-056 (AI-06..AI-13): add RAG assistant with verified citations
ENABLER (ARC-04): add detailed architecture diagrams
ENABLER (SHR-12): keep mongo:8 in Testcontainers on kernel 6.19+
```

Wrong (all have appeared on `main`):

| Subject | Error |
|---|---|
| `AI: knowledge base ingestion ...`; `ML: ...`; `Plan: ...` | Self-invented prefix, no task |
| `fix(libs/python): ...`; `test(...): ...`; `ci: ...` | Conventional Commits style, not used in this project |
| `CIT: keep mongo:8 in CI ...` | Has a task group but no task number |
| `ARC-04: add ...` | Missing the story part (`ENABLER (ARC-04): ...`) |
| `Create reminders on appointment-booked` | No story/task |
| `ENABLER (IAM-*): ...` | Wildcard instead of a concrete ID |

### 2.2 Body

- Separate from the subject with one blank line; each line <= 100 characters; English or Vietnamese.
- Write **why**: the problem, the cause, the options considered. "What" is already in the diff.
- Touching patient data, authorization, audit, secrets or egress: state the security impact clearly.
- Small, self-contained commits; no meaningless content (`wip`, `fix`, `update`).
- Do **not** add tool attribution lines (bot/AI `Co-Authored-By`, `Generated with ...`). Human co-authors are allowed:
  `Co-Authored-By: Name <email>`.

### 2.3 What a commit contains

- **Atomic**: one idea per change; the build and tests are still green after that commit. Do not mix refactoring with behavior changes, or whole-file reformatting with logic fixes.
- **Generated** files (`docs/05-plan/*`, `docs/02-requirements/user-stories.md`, `docs/*/dist/*`) are committed together with the source file that generated them,
  generated with tools in `tools/`, never edited by hand.
- `web/package-lock.json` changes only when `web/package.json` changes, via `npm install`; do not merge by hand.

Never commit:

| Type | Examples |
|---|---|
| Secrets, keys, tokens, passwords, connection strings | `.env`, `*.pem`, `*.key`, `dev/.local/` (already in `.gitignore`) |
| Real patient data or screenshots containing real data | Synthetic data only, from the data generator ([00-synthetic-data.md](00-synthetic-data.md)) |
| Temporary files, editor/Office lock files | `~$*.docx`, `~$*.xlsx`, `.~lock.*`, `*.swp`, `.DS_Store` |
| Build artifacts, caches, virtual environments | `target/`, `node_modules/`, `__pycache__/`, `.venv/`, code `dist/` |
| ML data and runs | `services/ml/data/`, `services/ml/runs/` (already in `.gitignore`); commit only the generator and description, not data |
| Files larger than 5 MB | Put large images/documents in external storage or compress them |

### 2.4 Amending commits and history

| Situation | Allowed |
|---|---|
| Last commit, **not pushed** | `git commit --amend`, `git rebase -i` freely |
| Pushed, **no review yet** | You may rewrite your branch history; push with `--force-with-lease` |
| Review exists | Only add new commits (`<story> (<task>): address review: ...`), no force-push |
| pre-commit hook/CI failure | Fix the cause; **do not** use `--no-verify`, do not disable checks |
| `main` | Nobody rewrites history (see section 10) |

### 2.5 Revert

Undo a merged change with a new PR, created with the **Revert** button on GitHub or `git revert <sha>`. Subject per section 2.1 with the original task
(or the fix task): `US-064 (ML-06): revert no-show scoring consumer (breaks E1 replay)`; the body states the reason and the reverted commit.

### 2.6 Authorship

- `user.name` is your real name, `user.email` is the email tied to your GitHub account (so commits show the right person). Do not commit with a shared account.
- Signing commits (GPG/SSH) is **encouraged**, not yet required. If the repo enables signing, do not bypass it with `--no-gpg-sign`.

## 3. Branches

| Rule | Value |
|---|---|
| Name | `<TASK-ID>-short-description` (lowercase, hyphens, <= 50 characters), e.g. `ML-06-booked-consumer`. Multiple tasks: use the first ID |
| Base | **Always branch from the latest `origin/main`**: `git fetch origin && git switch -c <branch> origin/main` |
| No stacking | Do not create a branch from another task branch. If task B needs task A's code, wait for A's PR to merge, then branch B from `main` |
| Lifetime | A branch lives <= 5 working days; if longer, split the task. No long-lived branches (`develop`, `release`) |
| Hotfix | `hotfix-<description>`, still through a PR, with the `hotfix` label ([00-workflow section 6](00-workflow.md#6-hotfix)) |
| Personal backup branch | Local only (`backup/...`), never pushed |
| After merge | The branch is deleted automatically on GitHub; delete the local branch: `git branch -d <branch>`; `git fetch --prune` |

Why not stack: squash merge creates a new commit on `main`; the branch on top still carries the original commit, so Git does not recognize it as the same content,
and every later change on `main` in the same file becomes a conflict (the PR chain #45-#52 hit this at every step).

## 4. Pull request

### 4.1 Opening a PR

| Item | Rule |
|---|---|
| Scope | One PR per task (or one slice of a story); do not combine unrelated work |
| Base | Always `main` |
| Subject | Exactly section 2.1. **The PR title becomes the commit on `main`** when squashed |
| Description | Per the [template](../.github/pull_request_template.md), writing style in section 4.3 |
| Before opening | The design document has been reviewed/updated before coding ([00-workflow section 3](00-workflow.md#3-definition-of-ready-task-can-start)); self-review the diff; run the full CI checks locally (Java: `mvn verify`; Python: `ruff`, `mypy`, `pytest` **with Docker** so Testcontainers tests actually run) |
| Draft | Open as **Draft** until Definition of Done is met; switch to *Ready for review* when ready |
| Assignee | Assignee is the author; Reviewer per CODEOWNERS (GitHub assigns automatically) |
| Label | At least one type label: `enhancement`, `bug`, `documentation`, `dependencies`, `hotfix` (see section 12) |
| Links | `Closes #<task issue>` and `Part of #<US issue>` ([00-workflow section 2](00-workflow.md#2-tracking-work-by-user-story)); related PRs in other repos: full links (section 8) |

### 4.2 Size

Aim for <= 400 changed lines (excluding generated files, lockfiles, images). If larger, split by vertical slice (API -> logic -> UI) or split the preparation
(refactor, migration) into a separate PR that goes first. Split PRs are **not** stacked: open the later PR after the earlier one has merged.

### 4.3 How to write the description

Follow exactly the sections of the template; do not invent other sections:

| Section | What to write | What not to write |
|---|---|---|
| Task | Task ID, story, design document sections followed (path + section) | |
| Changes | 2-5 bullets: **why** it is needed, what changed at the behavior level, what reviewers should look at closely | A file-by-file recap (the diff has it) |
| Contract impact | Whether `contracts/` changed; if so, which API/event/matrix | |
| Security and data | Whether it touches patient data, authorization, audit, PII, secrets, egress; if so, which controls were added | Leave blank: write "No impact" with the reason |
| Checks | Commands run and actual results (number of passing tests, whether Docker was available); what could **not** be checked and why | A vague "tested" |
| Follow-up work | Work intentionally left for another PR, with the task/issue | |

- English for the description; technical names, commands and paths stay as-is in `code`. Short sentences, bullet points.
- Write for someone who has not followed the conversation: no internal abbreviations, no "as discussed".
- UI changes: before/after screenshots or GIFs (desktop and mobile), **synthetic data only**.
- Be honest: which tests were skipped, which parts were only checked in CI, what risks remain.
- Do not insert tool attribution lines (`Generated with ...`).

### 4.4 While the PR is open

- CI must be green before requesting review. If CI fails due to the environment (runner, network), state it in the PR and rerun; do not skip required checks.
- If the scope changes significantly, update the title and description.
- After pushing fixes for feedback, click *Re-request review*.
- PR inactive for 7 days: the author updates it or moves it back to Draft; 14 days: close it, record the reason, keep the branch if still needed.
- Closing a PR without merging: always record the reason and the replacement PR/issue, if any.
- Do not change the PR base to any branch other than `main`.

## 5. Review

| Rule | Value |
|---|---|
| Reviews | At least 1, from the Code Owner (CODEOWNERS); `contracts/`, `libs/`, `.github/` and infrastructure are approved by the SA |
| Timing | Reviewers respond within 1 working day |
| Comment levels | `blocking:` (must fix before merge); `suggestion:` (should fix, author decides); `nit:` (small detail); `question:` (asking to understand) |
| Content | About the code, not the person; include the reason or a reference; prioritize correctness/security over style |
| Replies | The author replies to **every** thread: if fixed, name the commit; if not, give the reason |
| Resolve | The person who opened the thread resolves it. The author does not resolve `blocking:` feedback themselves |
| Approve | A new commit after approval cancels the previous approval (ruleset); approval is needed again |
| Self-merge | Do not approve your own PR; the SA is the only exception per the ruleset and still goes through a PR with green CI |

Reviewers check in this order: matches the approved design -> authorization, data, audit -> tests sufficient and meaningful -> contracts -> readability.

## 6. Updating the branch and conflicts

| Situation | How to proceed |
|---|---|
| No review yet | Rebase: `git fetch origin && git rebase origin/main`, then `git push --force-with-lease` |
| Review exists | No force-push. `git merge origin/main`, resolve conflicts, push normally |
| Conflict in generated files (`docs/05-plan/*`, `user-stories.md`) | Resolve the source file (`tools/plan/tasks_*.py`), rerun `python tools/plan/build.py`; do not edit generated files by hand |
| Conflict in `web/package-lock.json` | Do not merge by hand; take `main`'s version and run `npm install` in `web/` if `package.json` really changed |
| Conflict in binary files (`.docx`, `.xlsx`, `.png`) | Regenerate from the source with tools in `tools/`; do not pick one side arbitrarily |
| Any conflict | After resolving: rerun all checks (`tools/plan/build.py --check`, `tools/qa/trace.py --check`, `tools/pylib/sync.py --check`, tests) |

Always use `--force-with-lease`, never `--force`. Before a command that can lose work (`reset`, `checkout --`, `rebase`), run `git status` and
`git stash -u` if there are uncommitted changes.

## 7. Merge and after merge

- **Squash merge only** (merge commits and rebase merge are disabled); `main` history is linear.
- Who clicks: the SA or the reviewer who approved, when CI is green, reviews are complete and all threads are resolved.
- Before clicking: check that the squash subject matches section 2.1 (editable in the dialog) and trim the commit body to the "why".
- Recommended repo setting (the SA enables it under Settings -> General -> Pull Requests): *Default commit message* for squash = **Pull request title and description**.
  Currently it is *Pull request title and commit details*: a single-commit PR takes the commit subject and the body merges all commits - the source of mismatched subjects on `main`.
- After merge: the branch is deleted automatically, the task issue closes automatically via `Closes #`; the author updates the task status in the planning sheet; a PR that changes workflows/templates releases a tag (section 9).

## 8. Changes across multiple repos

- One PR per repo, same task, same story/task subject part.
- Each PR description has a *Related PRs* section with full links and the **merge order** (e.g. `shp-platform-templates` first, release a tag, then the pin-bump PR in the application repo).
- Do not merge a dependent PR before the PR it needs.

## 9. Tags and releases

- Currently only `shp-platform-templates` releases tags (all 4 repos have the `protect-tags` ruleset): `git tag -a vX.Y.Z -m "vX.Y.Z: <summary>"` on a `main` commit.
- SemVer: compatible bug fix -> `Z`; compatible input/feature addition -> `Y`; breaking change (renaming an input, removing a job) -> `X`.
- Tags are never moved or deleted; a bad release gets a new tag.
- Consuming repos pin by tag (`...python-ci.yml@vX.Y.Z`); bump the pin in a separate PR per section 2.1.

## 10. Branch protection and history

- The `protect-main` ruleset (all repos) is **always Active**: required PR, review, the `pr-gate` status check, linear history, force-push and `main` deletion forbidden.
- Disabling the ruleset or rewriting `main` history only for a compelling reason, performed by the SA, in this order:
  announce to the whole team -> back up (`git branch backup/main-<date>`) -> perform -> **re-enable the ruleset immediately** -> tell the team how to update local branches
  (`git fetch && git reset --keep origin/main`) and that PRs closed by GitHub must be reopened.
- Pre-convention history is marked `BOOTSTRAP:` (the 2026-11-02 standardization batch); do not use this prefix for new commits.

## 11. Security

- A secret leaked in a commit (even on an unmerged branch, even if deleted in a later commit): treat it as leaked. **Revoke it and create a new secret immediately**, tell the SA,
  then clean history. Removing it from history is not a substitute for revoking it.
- `gitleaks` runs in CI; do not add exceptions to bypass it without SA approval.
- Do not paste real patient data, tokens, or logs containing PII into commits, PRs, comments, issues or screenshots.
- Repo write access: by role; GitHub Apps/bots get only the minimum permissions they need.

## 12. Issues and labels

| Label | Used for |
|---|---|
| `enhancement` | Features, story-based tasks |
| `bug` | Bug fixes (with a regression test) |
| `documentation` | Documentation-only changes |
| `dependencies` | Library upgrades (Dependabot adds it automatically) |
| `hotfix` | Emergency fixes per [00-workflow section 6](00-workflow.md#6-hotfix) |
| `security` | Changes/incidents related to security or health data |
| `blocked` | Waiting on work outside the backlog; state what in the description. Waiting on another task uses the *Blocked by* relationship |
| `user-story` | Parent issue of a US |
| `enabler` | Parent issue of an enabler group (no US) |
| `task` | Issue of a task, a sub-issue of a US or enabler group |
| `priority:must`, `priority:should`, `priority:could` | MoSCoW level of the US |

US and task issues follow the structure in [00-workflow section 2.2](00-workflow.md#22-issue-structure): subject `US-015: <story name>` or
`SCH-12: <task name>` (not the commit format). Bug issues: the subject states the problem; the description covers the current behavior, the expectation, reproduction steps, and the related task/story.

## 13. Git configuration on your machine

```bash
git config --global user.name  "Full Name"
git config --global user.email "<email tied to GitHub>"
git config --global pull.ff only          # no implicit merge commit when pulling
git config --global fetch.prune true      # automatically prune deleted remote branches
git config --global rerere.enabled true   # remember previous conflict resolutions
git config --global core.autocrlf false   # .gitattributes already enforces LF
```

Windows: tools in `tools/` may write files with CRLF; before committing check `git diff --ignore-cr-at-eol` - if only line endings differ, run `git checkout -- <file>`.

## 14. Machine-generated exceptions

| Source | Preserved format | Note |
|---|---|---|
| Dependabot | `Bump <package> from <a> to <b> in <directory>`, `Update <package> requirement from <a> to <b> in <directory>` | Do not edit the subject; it still goes through review and CI |
| Workflow `gitops-promote` (`promote(<env>): <service> -> <digest> ...`), `promote-prod`, `bump-consumers` (`Bump platform templates pin to vX.Y.Z`) | Fixed format in the workflow | PRs opened by bots are exempt from the subject check (identified by the PR author `...[bot]`, not by the first word of the subject). To change the format, edit the workflow in `shp-platform-templates` |
| GitHub *Revert* button | `Revert "<old subject>"` | Re-edit the subject per section 2.5 before merging |

## 15. Automated checks

Commit/PR subject check regex (used for the CI check):

```
^(?=.{1,100}$)(ENABLER|US-\d{3}(\.\.US-\d{3})?(, US-\d{3}(\.\.US-\d{3})?)*) \([A-Z]{2,4}-\d{2,3}(\.\.[A-Z]{2,4}-\d{2,3})?(, [A-Z]{2,4}-\d{2,3}(\.\.[A-Z]{2,4}-\d{2,3})?)*\): \S.*$
```

| Already present | Not yet (follow-up work) |
|---|---|
| `protect-main` and `protect-tags` rulesets; `pr-gate`; `gitleaks`; CODEOWNERS | Required PR subject check in `shp-platform-templates`, `shp-infra`, `shp-gitops` (it runs but is not yet a required status check in the ruleset) |
| The `pr-title` PR subject check (reusable workflow in `shp-platform-templates`, reruns when the subject is edited); in application repos it is part of `pr-gate`, so it is a required check | Subject check for each commit in the branch (currently only the PR title is checked, i.e. the squash commit on `main`) |
| `.gitattributes` enforces LF; `.gitignore` blocks `~$*`, `.~lock.*`, `*.swp`, `.DS_Store` | Warning for files > 5 MB |
| PR and issue templates (`task`, `bug`); `hotfix`, `security`, `blocked` labels in all 4 repos | |
