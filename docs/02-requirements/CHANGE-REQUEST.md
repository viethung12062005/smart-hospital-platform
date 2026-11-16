# Requirements change request & SRS baseline (REQ-09)

> Status: **SRS v1.0 is the baseline** (2026-11-15). Any change to a requirement, user story, acceptance
> criterion, NFR, authorization rule or terminology after this date goes through the process below.
> The source of truth for requirements is the SRS under `docs/02-requirements/` (generated from
> `docs/02-requirements/srs/content/*.yaml` by `python3 -I tools/srs/build.py`).

## Baseline

- The approved SRS consists of `functional-requirements.md`, `non-functional-requirements.md`,
  `access-control-matrix.md`, `user-stories.md`, `traceability.md`, `workload-analysis.md` and the SRS
  package under `srs/`.
- A requirement is **identified by its FR / NFR / AC number**. Renumbering is a change request.

## When a change is needed

Open an issue with the `change-request` label and fill in:

1. **What** — the requirement (FR/NFR/AC id) being added, changed or removed.
2. **Why** — the business or technical reason.
3. **Impact** — the affected user stories, design documents, contracts, tests and services.
4. **Traceability** — the new/updated acceptance criteria and their test cases.

## Review & approval

1. The Solution Architect triages the change request.
2. For a contract-affecting change (API, event, authorization matrix, terminology): the change starts in
   the design document and `contracts/`, and needs the Solution Architect's agreement **before** code
   (see `AGENTS.md` rule 9).
3. The change is implemented in the SRS source (`srs/content/*.yaml`), the generated SRS is regenerated
   (`python3 -I tools/srs/build.py`), and the traceability report is re-checked
   (`python3 -I tools/qa/trace.py --check`).
4. The PR links the change-request issue and lists the affected acceptance criteria.

## Cross-review checklist (team sign-off)

Tool checks, last run 2026-11-16 on `main`:

- [x] Every FR has numbered acceptance criteria and is traced to a story and a test case
      (`python3 -I tools/srs/build.py --check`, `python3 -I tools/qa/trace.py --check`: OK, 203 test cases).
- [x] Every NFR has a measurement method (ARC-02): 49 of 49 rows in `non-functional-requirements.md`.
- [x] The authorization matrix matches the contract matrix (`python3 -I tools/contracts/validate_authz.py`: OK, 72 rules).
- [x] API/event contracts match the design documents and `contracts/` (`tools/contracts/check_all.sh`: all checks passed).

Sign-off (a person records it; the tools cannot):

- [ ] Each team member has reviewed SRS v1.0 and approves it as the baseline, recorded as a comment on the REQ-09 issue.
