<!-- PR title = the commit on main when squashed: `<story> (<task>): <description>`, e.g. `US-041 (NTF-02): create reminders on appointment-booked`
     or `ENABLER (ARC-04): add architecture diagrams`. Base is always main. How to write each section: docs/00-git-github.md §4.1 -->

## Task
- ID: <!-- e.g. NTF-02 (one PR = one task; branch name `<ID>-short-description`, cut from main) -->
- Story: <!-- e.g. US-041, or ENABLER -->
- Issue: Closes #<!-- task issue -->, Part of #<!-- US issue or enabler issue -->
- Design docs followed: <!-- e.g. detailed-design/04-db-notification-ml.md §1.3 -->

## Changes
<!-- 2-5 bullets: why it is needed, what changes at the behavior level, what the reviewer should look at closely. Do not list every file. -->

## Contract impact
- [ ] No change to `contracts/` (API, event, authorization matrix, catalog codes)
- [ ] Changed - updated `contracts/` and design docs, `tools/contracts/check_all.sh` green, SA agreed beforehand

## Security and data
<!-- Does it touch patient data, authorization, audit, PII, secrets, or egress outside the cluster? If yes: which controls were added.
     If not: write "No impact" and a short reason. Do not leave it empty. -->

## Verification
<!-- Commands actually run and their real results (e.g. `pytest`: 45 passed, Docker available). What could not be verified and why. -->

## Follow-up work
<!-- Work intentionally left for another PR, with task/issue. Delete this section if none. -->

## Definition of Done
<!-- Same list as docs/00-workflow.md §4. Tick every item before leaving Draft; if one does not apply, tick it and add "(not applicable: reason)". -->
- [ ] Self-reviewed the diff on GitHub: no debug code, dead code, or unrelated changes
- [ ] Unit + integration tests green, line coverage >= 70%, lint/format clean, no compiler warnings: `mvn -B -pl services/<svc> -am verify` (Java), `ruff`/`mypy`/`pytest --cov` (Python), `npm run lint && npm test` (web)
- [ ] `contracts/` matches the code; `tools/contracts/check_all.sh` green if anything changed
- [ ] New endpoints carry `@RequireShpAuthz` and have a row in the authorization matrix; audit is emitted for every decision; business errors use catalog codes (07 §5)
- [ ] Events published via `outbox.add(...)` in the same transaction; consumers wrapped in `inbox.handleOnce(...)`
- [ ] Fake data only; no secrets/keys/passwords in code, config, tests or logs; logs contain no PII
- [ ] Image/dependency scans show no High/Critical with an available fix (the `checks` and `container` jobs of this PR)
- [ ] No changes to Dockerfile, pipeline, Helm, `config/PlatformConfig` (changes -> open an issue for SA)
- [ ] Ran on the local cluster if runtime behavior changed: `make -C dev deploy SVC=<svc>`
- [ ] Every acceptance criterion touched has a test case in `docs/06-testing/catalog` and an automated test; `catalog/automation.yaml` updated and `python -I tools/qa/trace.py --check` green; P0 cases are automated; a bug fix has a regression test
- [ ] Related documentation is updated
