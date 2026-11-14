# Software Requirements Specification (SRS)

The Smart Hospital Platform SRS follows an enterprise SRS template structure (all branding from the original template has been removed).
Content is written as YAML so it can be reviewed through Pull Requests, checked automatically and traced to test cases; the Word, Excel
and diagram files are **generated** from the YAML, never edited by hand.

| Directory / file | Content |
|---|---|
| `content/00-document.yaml` | Document information, approval page, change history, Chapter 1 (Introduction) |
| `content/10-actors-objects.yaml` | Actors, external systems, data objects, high-level requirements list |
| `content/20-high-level.yaml` | Chapter 2: object diagram, workflows, state machines, use case groups, access control matrix, site map |
| `content/3x-uc-*.yaml` | Chapter 3: use cases by module (summary table, steps, business rules, acceptance criteria) |
| `content/40-common.yaml` | Common use cases and common business rules (authorization, audit, no PII leakage, AI safety...) |
| `content/50-screens.yaml` | Chapter 4: screens and components |
| `content/60-nfr.yaml` | Chapter 5: non-functional requirements (metrics, measurement methods, test types) |
| `content/70-other.yaml`, `80-messages.yaml` | Chapters 6-9: general conventions, integrations, data migration, messages, notification templates |
| `dist/SHP_System_Requirement_Specification_v*.docx` | SRS document (generated) |
| `dist/SHP_System_Analysis_Result_v*.xlsx` | List of requirements, actors, data objects, functions, screens (generated) |
| `content/55-wireframes.yaml` | Wireframes for 15 screens not yet implemented in the web app |
| `content/90-rationale.yaml` | Appendix A: threshold rationale, legal basis, sources, items to verify |
| `mockups/*.png` | Screenshots of 7 real web screens (synthetic data mode, clock fixed at 29/10/2026 08:45) |
| `dist/diagrams/*.drawio`, `dist/diagrams/png/` | Diagrams and wireframes that can be opened/edited with draw.io, exported images (generated) |

## Editing and regenerating

```bash
python -I tools/srs/build.py --check                 # check only (CI runs this command)
python -I tools/srs/build.py                         # regenerate everything (needs draw.io desktop to export PNG, ~5 minutes)
python -I tools/srs/build.py --only act-book,ord     # redraw only a few diagrams; other diagrams use the existing PNGs
```

Requires `pyyaml`, `python-docx`, `openpyxl`, `Pillow`. draw.io desktop is looked up at `C:\Program Files\draw.io\draw.io.exe`
or via the `DRAWIO_CLI` environment variable. After opening the Word file, choose *Update Field* to refresh the table of contents and page numbers.

## Content authoring conventions

- Numbers (UC n, BR n, CBR n, Screen n, EMSG/IEM/CFD/SMSG n, ET n, Figure n) are assigned by the script in order of appearance; do not type them manually.
- Cross-references use `[[UC:key]]`, `[[BR:key]]`, `[[CBR:key]]`, `[[CMUC:key]]`, `[[SCR:key]]`, `[[MSG:key]]`, `[[ET:key]]`.
  A broken reference makes `--check` fail.
- Every use case must have: `objective`, `actors`, `trigger`, `pre`, `post`, `trace` (original UC-xx/AC/BR/NFR/API codes).
  Steps are numbered 1..n; each business rule records the step it applies to `(n)` and that step must exist.
- Health data is sensitive personal data: any use case that reads/writes patient data must state the permission scope,
  the behavior on denial and the audit requirement (see `CBR authz`, `CBR audit`, `CBR no-pii`, `CBR ai-safety`).
- Acceptance criteria are written in Given/When/Then form; the `AC-...` code is the key used by the Test Plan to trace test cases.

## Mockups

Every screen in `50-screens.yaml` must have a screenshot `mockups/<key>.png` or a wireframe with the same key in `55-wireframes.yaml`; `--check` reports an error if one is missing.
Taking screenshots: run `npm run dev:mock` in `web/`, log in with the corresponding role and capture with a browser (window 1360x820, zoom 1.5).
When a wireframe is implemented in the web app, take a real screenshot, place it in `mockups/` and delete the corresponding wireframe.
