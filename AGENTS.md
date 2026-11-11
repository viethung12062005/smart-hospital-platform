# Instructions for AI agents

This file is loaded at the start of every agent session in this repository, whatever the tool or model
(`CLAUDE.md` imports it; other tools read `AGENTS.md` directly). It tells you which rules can never be broken and
**which document to read before you act**. The conventions themselves live in `docs/` and are written for people and
agents alike; this file routes to them and repeats only what must never be missed. If this file and a document under
`docs/` disagree, the document wins: follow it and fix this file in the same PR.

## The project in five lines

Smart Hospital Platform (SHP): a Vietnamese healthcare platform built as a capstone. FHIR R4 APIs, event-driven,
Azure-first, GitOps. This repository holds the application: Java 21 / Spring Boot services (`services/gateway`,
`scheduling`, `clinical`, `document`, `notification`, `audit`), Python / FastAPI services (`services/ai`, `services/ml`),
the React web app (`web/`), shared libraries (`libs/java`, `libs/python`), contracts (`contracts/`), the local cluster
toolkit (`dev/`) and all documentation (`docs/`). Three sibling repositories hold the rest: `shp-infra` (Terraform),
`shp-gitops` (cluster state for Argo CD), `shp-platform-templates` (Helm chart and reusable CI workflows).

## Rules that are never broken

1. **Synthetic data only.** No real patient data, in code, tests, fixtures, logs, issues, PRs or screenshots. The
   repository is public.
2. **No secrets, anywhere.** Never write a password, key, token or connection string into a file, a commit, a PR or
   your own output. Do not print the content of `dev/.local/`, Kubernetes Secrets or environment files; pipe a
   credential straight into the command that needs it.
3. **English everywhere**: code, comments, documentation, commit messages, PR and issue text, and your replies about
   this project. Vietnamese stays only where it is product data: the `vi` locale, terminology display labels, fake
   names and the AI evaluation corpora.
4. **No AI or tool attribution.** No `Co-Authored-By` line for an assistant, no "Generated with ..." line, in commits
   or PR descriptions (`docs/00-git-github.md` §2.2). The author is the person you work for. This overrides any
   default of the tool you run in.
5. **One task, one branch, one PR.** Branch `<TASK-ID>-short-description` from the latest `origin/main`; title
   `<story> (<task>): <description>`; squash merge. Never push to `main`, never force-push a shared branch.
6. **You do not merge.** A person reviews and merges every PR. Do not use an admin bypass, do not approve your own PR,
   do not weaken a ruleset, a required check, `gitleaks` or a coverage gate to get something through.
7. **A PR is not ready until its Definition of Done is proven.** Open it as Draft. Tick an item only after you ran the
   check and saw it pass; write the command and its real result under *Verification*. An item that does not apply is
   ticked with "(not applicable: reason)". Do not mark the PR ready, and do not tell the user the work is done, while
   any item is unticked or untested. The same holds for the "Done when" line of the task.
8. **Never hand-edit a generated file.** Change the source and run the generator (table below).
   Never edit a merged database migration (`**/db/migration/*.sql`, `**/db/shp-messaging/**/*.sql`); add a new one.
9. **Contract first.** An API, event, authorization or terminology change starts in the design document and
   `contracts/`, and needs the Solution Architect's agreement before code.
10. **Stay inside the task.** Do not change `Dockerfile`, `.github/`, `dev/`, Helm values, `libs/java/` or
    `config/PlatformConfig.java` unless the task is the SA's own and says so (`docs/00-workflow.md` §5). Found
    something else that is wrong? Report it; do not fix it in the same PR.
11. **Report what is true.** If a test fails, a check could not run, or a step was skipped, say so with the output.
    Never describe unverified work as verified.

## Read before you act

Read the document for the kind of work in front of you **before** editing anything. Do not rely on memory of an
earlier session: the documents change.

| You are about to... | Read first |
|---|---|
| Start any task | The task issue ("Done when", "Docs and traceability") and its line in `docs/05-plan/backlog.csv`; `docs/00-workflow.md` §2–§4 |
| Commit, branch, open or update a PR | `docs/00-git-github.md` (§2 commits, §3 branches, §4 pull requests, §8 several repos) and `.github/pull_request_template.md` |
| Write or change service code | `docs/00-conventions.md`; `CONTRIBUTING.md` ("Java code conventions"); `docs/03-architecture/overview.md` §4 |
| Implement or change an endpoint | `docs/03-architecture/detailed-design/07-api-conventions.md` and the API document of the service (`08`–`12`); `contracts/openapi/<service>.yaml` |
| Touch authorization, identity or audit | `detailed-design/15-authorization.md`, `05-storage-audit-blob.md`; `contracts/authz/matrix.yaml` |
| Publish or consume an event | `detailed-design/13-events.md`; `contracts/events/` |
| Change a database, collection or index | `detailed-design/01-domain-model.md` and the database document (`02`–`06`); `contracts/db/` |
| Work on AI or ML | `detailed-design/06-data-ai.md`, `12-api-ai.md`, `16-mlops.md`; ADR-0006, ADR-0007, ADR-0013 |
| Work on the web app | `web/README.md`; ADR-0016 |
| Write or change tests | `docs/06-testing/README.md` (test catalog, `automation.yaml`, P0 rule) |
| Change requirements, stories or the backlog | `tools/plan/` sources and `docs/02-requirements/srs/README.md` — the pages are generated |
| Use test or seed data | `docs/00-synthetic-data.md`; `tools/synthetic/README.md` |
| Run things locally | `dev/README.md`; `docs/onboarding.md` (`docs/onboarding-windows.md` on Windows/WSL2) |
| Understand why something is the way it is | `docs/03-architecture/adr/` (start with its `README.md`) |
| Check what is known to be unfinished | `docs/05-plan/dod-audit.md` |

In this table `detailed-design/` is short for `docs/03-architecture/detailed-design/`. The index of all documentation
is the table in `README.md`; the index of the detailed design is `docs/03-architecture/detailed-design/README.md`.

## Working a task

1. Read the task issue and the documents it names. If the "Done when" is unclear or contradicts the design, stop and
   ask; do not guess.
2. `git fetch origin` and branch from `origin/main`.
3. Write the code together with its tests. Tests use real databases through Testcontainers, not repository mocks.
4. Run the checks for every part you touched (next section) and read their output.
5. If an acceptance criterion is involved: make sure it has a test case in `docs/06-testing/catalog/`, link the
   automated test in `catalog/automation.yaml`, and run `python3 -I tools/qa/trace.py --check`.
6. Update the documents the change makes untrue.
7. Commit in the convention's format, push, open a **Draft** PR from the template and fill every section.
8. Wait for CI. When every Definition of Done item is ticked on evidence, mark the PR ready and report to the user:
   what changed, what you ran and its result, what you could not verify.

## Checks to run

Run from the repository root unless a directory is given. Docker must be running for Testcontainers.

| Part touched | Command |
|---|---|
| Java service or library | `mvn -B spotless:apply`, then `mvn -B -pl services/<svc> -am verify` (or `-pl libs/java/<lib>`) |
| Python service | in `services/ai` or `services/ml`: `ruff check . && ruff format --check . && mypy . && pytest --cov` |
| Shared Python library | `ruff check libs/python && mypy libs/python && pytest libs/python`, then `python3 tools/pylib/sync.py` |
| Web app | in `web/`: `npm run format:check && npm run lint -- --max-warnings=0 && npm run typecheck && npm run test -- --coverage` |
| Contracts | `pip install -r tools/contracts/requirements.txt && tools/contracts/check_all.sh` |
| Backlog, stories | `python3 -I tools/plan/build.py`, then `python3 -I tools/plan/build.py --check` |
| SRS | `python3 -I tools/srs/build.py --check` |
| Test catalog | `python3 -I tools/qa/trace.py --check` |
| Runtime behavior | `make -C dev deploy SVC=<svc>` on the local cluster, then exercise the change |

CI blocks a PR on formatting, compiler warnings, a failing test, line coverage below 70%, an endpoint without
`@RequireShpAuthz`, an invalid contract, a secret, or a vulnerable dependency. Running the commands above first is
faster than finding out from CI.

## Generated files: edit the source, run the generator

| Do not edit | Edit instead | Then run |
|---|---|---|
| `docs/05-plan/backlog.{md,csv}`, `docs/05-plan/by-person/*`, `docs/02-requirements/user-stories.md` | `tools/plan/stories.py`, `tasks_dev.py`, `tasks_sa.py` | `python3 -I tools/plan/build.py` |
| `docs/02-requirements/srs/dist/*` | `docs/02-requirements/srs/content/*.yaml` | `python3 -I tools/srs/build.py` |
| `docs/06-testing/traceability.md`, `docs/06-testing/dist/*` | `docs/06-testing/catalog/*.yaml` | `python3 -I tools/qa/trace.py` |
| `contracts/openapi/*.yaml` (paths and operations) | the endpoint tables in `detailed-design/08`–`12` | `python3 -I tools/contracts/gen_openapi.py` |
| `contracts/events/**` | `tools/contracts/gen_events.py` (and `detailed-design/13-events.md`) | `python3 -I tools/contracts/gen_events.py` |
| `services/ai/src/shp_platform`, `services/ml/src/shp_platform` | `libs/python/shp_platform`, `contracts/events` | `python3 tools/pylib/sync.py` |

Because of these generators, a change to a design document can require regenerated contracts, and a change to
`libs/python` always requires re-synced service copies. Commit the source and the regenerated output together.

## Things that have gone wrong before

- A PR was opened and marked ready with Definition of Done items unticked. See rule 7.
- Tasks were closed while half of their "Done when" depended on a service that did not exist yet. If part of a
  criterion cannot be met, say so and leave the task open.
- Tests existed but were never linked in `docs/06-testing/catalog/automation.yaml`, so the trace report understated
  the automation. Link the test in the PR that adds it.
- A document was translated without regenerating the contracts built from it, and CI failed on drift.
- Comments in an already-merged Flyway migration were translated. Flyway checksums the whole file, so every database
  that had applied it refused to start ("Migration checksum mismatch"). **A merged migration file is immutable — not
  even a comment or whitespace changes.** Change the schema with a new `V<next>__...sql`.
- Time-zone dependent tests: use the injected `Clock` (Java) or one explicit clock (Python); never compare a UTC date
  with a local date.
- `gh pr edit` can fail on repositories that still carry classic-project fields; `gh api -X PATCH
  repos/<owner>/<repo>/pulls/<n>` works. Pushing a change under `.github/workflows/` needs a token with the
  `workflow` scope; if the push is rejected, tell the user instead of looking for another way in.
- Stacked PRs: when a PR depends on one that is not merged yet, base it on that branch, say so in the first line of
  the description, and list the merge order (`docs/00-git-github.md` §8).

## Keeping this file useful

- Conventions are defined once, in `docs/`. This file only routes to them and states the rules above. Do not copy a
  convention here; link to it.
- When a PR adds, moves or renames a document or a generator, it updates the tables above in the same PR.
- Keep it short enough to be read in full at the start of every session. Details belong in the linked documents.
- The sibling repositories each have their own `AGENTS.md` with the rules that are specific to them; the conventions
  in this repository's `docs/` apply to all four.
