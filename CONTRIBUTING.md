# Contributing code

Applies to everyone on the team. Start with [docs/onboarding.md](docs/onboarding.md) if you are new.

## Non-negotiable rules
1. **Synthetic data only.** Never put real patient data into the repo, tests, logs or screenshots.
2. **No secrets in the repo.**
   - No passwords, keys, tokens or connection strings in code, configuration or logs.
   - Locally, secrets are generated into `dev/.local/` (git-ignored); in the cloud, secrets live in Key Vault.
3. **Contract first.**
   - Contracts cover APIs (`contracts/openapi`), events (`contracts/events`), authorization (`contracts/authz/matrix.yaml`) and code systems (`contracts/terminology`).
   - To change a contract, edit `contracts/` together with the design doc, and the SA must agree beforehand.
4. **Developers write business logic only.**
   - Do not modify Dockerfile, `.github/`, `dev/`, Helm values, `libs/java/` or `config/PlatformConfig.java`.
   - If those need changing, open an issue for the SA.

## One-task workflow
1. Take a task issue assigned to you (this week's milestone; title `<ID>: ...`, a sub-issue of a User Story issue). Work in week order;
   do not start a task that is still *Blocked by* an open task. How to track work: [docs/00-workflow.md §2](docs/00-workflow.md#2-tracking-work-by-user-story).
2. Create a branch from `main`: `git switch -c <ID>-short-description`, e.g. `NTF-02-consumer-booked`. **One PR per task.**
3. Read the design section named in the task's "Docs" column (`docs/03-architecture/detailed-design/`).
4. Write code with tests.
5. Run the same checks as CI before pushing (section below).
6. Open a PR from the template, writing `Closes #<issue number>`. CI must be green, at least 1 review (CODEOWNERS). Merge with **squash**.

Commit and PR titles follow [docs/00-git-github.md](docs/00-git-github.md): `<story> (<task>): <description>`,
e.g. `US-041 (NTF-02): create reminders on appointment-booked` or `ENABLER (ARC-04): add architecture diagrams`.

## Checks before pushing
| Part | Command (run at the repo root) |
|---|---|
| Java service | `mvn -B spotless:apply` then `mvn -B -pl services/<svc> -am verify` (needs Docker for Testcontainers) |
| Python service | in `services/ai` or `services/ml`: `ruff check . && ruff format --check . && mypy . && pytest --cov` |
| Web app | in `web/`: `npm run format:check && npm run lint -- --max-warnings=0 && npm run typecheck && npm run test -- --coverage` (see `web/README.md`) |
| Contracts (if editing `contracts/`) | `pip install -r tools/contracts/requirements.txt && tools/contracts/check_all.sh` |
| Run in the cluster | `make -C dev deploy SVC=<svc>` (see `dev/README.md`) |

CI blocks a PR when:
- formatting is wrong (Spotless / ruff);
- the compiler emits warnings (`-Werror`);
- tests fail;
- line coverage < 70% (JaCoCo / pytest-cov);
- an endpoint lacks `@RequireShpAuthz`;
- the contract is invalid.

## Java code conventions
- Package layout: `api/` -> `app/` -> `domain/`, plus `infra/`, `messaging/`, `config/`. See [overview.md §4](docs/03-architecture/overview.md#4-inside-a-java-service).
- **Transactions open in `app/` only.** Data changes and `outbox.add(CloudEvents.create(...))` sit in the same transaction.
- Consumers are always wrapped in `inbox.handleOnce(...)`, because an event may arrive more than once.
- Business errors use `throw new ShpException("<code>")`, with the code in catalog 07 §5. Do not hand-build error responses.
- Time uses an injected `Clock` (so tests can fix it), stored in UTC. IDs use `Uuids.v7()`.
- Logs contain no PII. Log IDs only, never names, phone numbers, emails or record content.
- Tests use real databases via Testcontainers, not repository mocks. Pure domain logic is tested with JUnit.

## Need help
- Design or contract questions: open an issue labeled `question` and mention the SA.
- Bugs in `libs/java`, `dev/` or the pipeline: open an issue for the SA, including the command run and the error log (with secrets removed).
