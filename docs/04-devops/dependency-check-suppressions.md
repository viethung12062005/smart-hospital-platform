# Dependency-Check suppressions (CIT-11)

The `checks / dependency-check` job (OWASP Dependency-Check, reusable workflow `pr-checks.yml` in `shp-platform-templates`) fails a PR on any finding with CVSS >= 7 (`--failOnCVSS 7`). That threshold does not change. A suppression is a narrow, reviewed, **expiring** exception for a finding that cannot be fixed yet or does not apply.

## Where it lives

`dependency-check-suppressions.xml` at the root of this repository. The reusable workflow passes it to the tool (`--suppression /src/dependency-check-suppressions.xml`) when the file exists; without the file nothing changes. This needs the templates tag that contains the feature (CIT-11): until the `@v0.1.x` pin of `.github/workflows/pr-checks.yml` points at it, the file is ignored.

## Policy

1. **One package per entry, never a blanket suppression.** Match with `<packageUrl>` (for example `pkg:npm/picomatch@4.0.7`, a precise version) together with `<vulnerabilityName>`. No `<cvssBelow>`, no regex over a whole ecosystem, no `<filePath>` on a directory.
2. **Every entry has `<notes>`** with: the advisory and its score, why it does not apply or why it cannot be fixed (for example no patched version exists), where the dependency runs (dev tooling versus a runtime image), the review date and reviewer, the expiry, and the removal condition.
3. **Every entry has an `until` date, 60 days at most from the review.** On and after that date the suppression is inactive and the finding fails the gate again by itself. Extending it is a new review: change the date and the notes in a PR.
4. **A finding in a shipped runtime image is not suppressed here.** Fix or upgrade it; a suppression is only for dev-only or demonstrably unreachable code.
5. The file falls under the default CODEOWNERS rule (the Solution Architect reviews it). The PR that adds or extends an entry says why, and is not mixed with other work.

## Current entries

| Package | Advisory | Why | Review | Expires | Remove when |
|---|---|---|---|---|---|
| `pkg:npm/picomatch@4.0.7` | GHSA-fc5q-39xc-vjpf (ReDoS, CVSS 7.5, affected `<= 4.0.7`) | No patched version exists (4.0.7 is the latest release). `"dev": true` in `web/package-lock.json`, pulled only by vite, vitest, tinyglobby and fdir; not in any runtime image | 2026-11-18 | 2027-01-17 | a fixed picomatch is released and `web/package-lock.json` uses it |

## How to review it

- Open the file and check each `<suppress>` against the table above: package, advisory, `until`, notes complete.
- Is the `until` date still in the future? If not, the entry is already inactive: delete it, or re-review it if the finding is still open.
- Is there a fixed release now? `npm view picomatch versions --json` (or the advisory page). If yes, bump the lockfile through `web/package.json` (`npm install`, see `docs/00-git-github.md` §2.3), delete the entry and its row here.
- Confirm that the dependency is still dev only: `npm ls picomatch` in `web/` and `"dev": true` for it in `web/package-lock.json`.
- Validate the file after any edit against the schema shipped inside the pinned Dependency-Check image (`schema/dependency-suppression.1.4.xsd`, namespace `https://jeremylong.github.io/DependencyCheck/dependency-suppression.1.4.xsd`), for example with `xmllint --noout --schema dependency-suppression.1.4.xsd dependency-check-suppressions.xml`.
