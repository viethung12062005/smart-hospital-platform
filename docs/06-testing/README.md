# Testing

Test plan, test case catalog, requirements traceability and automated tests for Smart Hospital Platform. Start with the
[Master Test Plan](dist/SHP_Master_Test_Plan_v0.9.docx) and the [traceability report](traceability.md).

| Directory / file | Content |
|---|---|
| `content/10-test-plan.yaml` | Master Test Plan content (generates `dist/SHP_Master_Test_Plan_*.docx`) |
| `catalog/<module>.yaml` | Test cases: every acceptance criterion (AC) and NFR of the SRS has at least one test case; `security.yaml` holds additional security tests |
| `catalog/automation.yaml` | Which test cases already have automated tests, where, what is missing, who writes them |
| `baseline.json` | Minimum automation level; a PR that lowers this number is blocked |
| `traceability.md` | Traceability and coverage report (generated, not hand-edited) |
| `dist/Test_Cases.xlsx` | Test case list for tracking (generated) |

Automated tests live in: `libs/java/*/src/test`, `services/*/src/test`, `web/src/**/*.test.ts(x)` (unit), `web/e2e` (E2E,
accessibility, security headers), `tests/performance/k6` (performance), `tools/contracts` (contracts). Workflow: `.github/workflows/qa.yml`.

## Daily work

```bash
# Add a requirement/AC to the SRS -> create draft test cases for the missing parts (do not change existing test cases)
python -I tools/qa/seed_catalog.py --write

# Check traceability like CI
python -I tools/qa/trace.py --check

# Regenerate reports, Excel, Test Plan (docx)
python -I tools/qa/trace.py

# After writing an automated test for a test case: update automation.yaml then raise the baseline
python -I tools/qa/trace.py --update-baseline
```

E2E on a dev machine (no backend needed): `cd web && npm ci && npx playwright install chromium && npm run e2e`.
If Edge/Chrome is already installed, use `PW_CHANNEL=msedge npm run e2e` to avoid downloading a browser.

## How to attach automated tests to a test case

- **Java, Vitest, k6, workflow:** add to `catalog/automation.yaml`: `refs: ["path/to/file::test-name"]` (the tool checks that the file exists and the name is in the file).
- **Playwright E2E:** put the test case code in the title, for example `test('TC-AC-03.4 ...')`; the tool detects it automatically. `test.fixme` = not implemented, counted as awaiting implementation.
- **Status:** `automated` (fully covered), `partial` (record what is missing in `gap`), `planned` (record `blocked_by` and `owner`), `manual`.

## Test conventions

- Synthetic data only. Synthetic data has recognizable names, phone numbers and emails so log-scanning tests can find them.
- P0 tests (patient data, authorization, audit, AI, patient safety) also check the denied case: if blocked, verify the correct 404/403, and that an audit record exists.
- Each test sets up and cleans up its own data; no dependence on execution order; E2E fixes the browser clock.
- Bug fixes come with regression tests. Reporting a vulnerability: use the repo's private channel, do not open a public issue.
