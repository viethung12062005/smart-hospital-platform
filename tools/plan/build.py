"""Validate the plan and render docs.

Run:  python3 -I tools/plan/build.py
Reads stories.py, tasks_sa.py, tasks_dev.py, status.py. Writes:
  docs/02-requirements/user-stories.md
  docs/05-plan/backlog.md, docs/05-plan/backlog.csv
  docs/05-plan/by-person/{SA,D1,D2,D3}.md
Exit code != 0 if any validation fails.
"""
import csv
import re
import sys
from collections import defaultdict
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
from stories import ALL_API_IDS, ALL_FR_ACS, STORIES  # noqa: E402
from tasks_dev import DEV_TASKS  # noqa: E402
from status import STATUS  # noqa: E402
from tasks_sa import SA_TASKS  # noqa: E402

ROOT = HERE.parents[1]
DOCS = ROOT / "docs"

WEEK = {"S0": 1, "P1": 2, "P2": 3, "P3": 4, "P4": 5, "S1": 4, "S2": 5, "S3": 6, "S4": 7, "S5": 8, "S6": 9,
        "H1": 10, "H2": 11, "DEF": 12}
SPRINT_LABEL = {
    "S0": "W1 26/10-01/11: finalize gates G1-G3, create repos, onboarding",
    "P1": "W2 02/11-08/11: platform 1; dev builds shared libraries + synthetic data",
    "P2": "W3 09/11-15/11: platform 2; dev builds shared libraries",
    "P3": "W4 16/11-22/11: platform 3 (parallel with S1)",
    "P4": "W5 23/11-29/11: identity, ephemeral environments, G4 (parallel with S2)",
    "S1": "W4 16/11-22/11: dev starts services",
    "S2": "W5 23/11-29/11", "S3": "W6 30/11-06/12", "S4": "W7 07/12-13/12", "S5": "W8 14/12-20/12",
    "S6": "W9 21/12-27/12", "H1": "W10 28/12-03/01: hardening, load/failure test",
    "H2": "W11 04/01-10/01: eval, FinOps, security", "DEF": "W12 11/01-17/01: defense preparation",
}
EPICS = {
    "ARC": "Architecture & project governance", "GOV": "Repos, workflow & GitHub governance", "IAC": "Azure infrastructure with Terraform",
    "CIT": "CI templates (GitHub Actions)", "GOP": "Helm & GitOps (Argo CD)", "ENV": "Ephemeral environment lifecycle",
    "OBS": "Platform observability", "LOC": "Local environment & walking skeleton", "SCF": "Scaffold service (SA)", "GW": "API Gateway",
    "MLO": "MLOps pipeline (SA)", "PRD": "Production engineering & defense",
    "SHR": "Shared libraries (Java)", "PY": "Shared libraries (Python, for ai/ml, SA)", "DAT": "Synthetic data",
    "SCH": "Scheduling service", "NTF": "Notification service", "CLN": "Clinical service", "DOC": "Document service",
    "AUD": "Audit service", "AI": "AI service", "ML": "ML no-show", "WEB": "Web app (PWA)",
}
PERSON = {"SA": "SA — Solution Architect / PM / DevOps; Keycloak, shared libraries, AI, ML, synthetic data", "D1": "D1 — Java dev (scheduling, authorization, booking UI)",
          "D2": "D2 — Java dev (clinical, FHIR core, clinical UI)", "D3": "D3 — Java dev (gateway, notification, document, audit)"}
EVENTS = [f"E{i}" for i in range(1, 12)]
HOURS_CAP = {"SA": 8, "D1": 4, "D2": 4, "D3": 4}
LOAD_WARN = {"SA": 45, "D1": 30, "D2": 30, "D3": 30}
DEV_CAP = 30          # leveling target: hours per developer per week
DEV_START = 2         # developers start in W2 (W1 is design sign-off and onboarding)
LAST_WEEK = 11        # developers may spill until W11 (W12 is for the defence)
PRIO = {"M": 0, "S": 1, "C": 2}

Task = tuple  # (id, stories, title, done, owner, hours, deps, sprint, refs)


def story_ids(field: str) -> list[str]:
    return [] if field.strip() == "ENABLER" else [s.strip() for s in field.split(",") if s.strip()]


def tokens(text: str) -> set[str]:
    return {t for t in re.split(r"[,\s]+", text) if t}


SW: dict[str, int] = {}  # scheduled week per task id (filled by schedule())


def wk(t: Task) -> int:
    return SW[t[0]]


def schedule(tasks: list[Task]) -> list[str]:
    """Level developer load. A task starts as soon as its dependencies allow (post-freeze H1/H2 tasks keep their
    sprint as a lower bound) and moves later only when the week is full. SA tasks start at their sprint week or when their
    dependencies allow, whichever is later; they are NOT capped (SA overload is reported, not hidden)."""
    errs: list[str] = []
    by_id = {t[0]: t for t in tasks}
    story_prio = {s[0]: PRIO[s[5]] for s in STORIES}
    SW.clear()
    pending = list(tasks)

    def prio(t: Task) -> int:
        ids = story_ids(t[1])
        return min((story_prio.get(i, 0) for i in ids), default=0)

    load: dict[tuple[str, int], int] = defaultdict(int)
    while pending:
        ready = []
        for t in pending:
            deps = [d.strip() for d in t[6].split(",") if d.strip() and ".." not in d]
            if all(d in SW or d not in by_id for d in deps):
                lower = WEEK[t[7]] if t[4] == "SA" or t[7] in ("H1", "H2", "DEF") else DEV_START
                ready.append((t, max([lower] + [SW[d] for d in deps if d in SW])))
        if not ready:
            errs.append("dependency cycle among: " + ", ".join(t[0] for t in pending))
            break
        ready.sort(key=lambda x: (x[1], prio(x[0]), x[0]))
        t, earliest = ready[0]
        w = earliest
        while t[4] != "SA" and load[(t[4], w)] + t[5] > DEV_CAP and w <= LAST_WEEK:
            w += 1
        last = 12 if t[4] == "SA" else LAST_WEEK  # SA may work until W12 (defense week)
        if w > last:
            errs.append(f"{t[0]}: cannot be scheduled by W{last} ({t[4]}, cap {DEV_CAP}h/week for developers)")
            w = last
        SW[t[0]] = w
        load[(t[4], w)] += t[5]
        pending.remove(t)
    return errs


def validate(tasks: list[Task]) -> list[str]:
    errs: list[str] = []
    ids = [t[0] for t in tasks]
    for i in {x for x in ids if ids.count(x) > 1}:
        errs.append(f"duplicate task id {i}")
    by_id = {t[0]: t for t in tasks}
    sids = {s[0] for s in STORIES}
    for s in STORIES:
        if s[5] not in "MSC":
            errs.append(f"{s[0]}: bad priority {s[5]}")
        for a in s[8]:
            if a not in ALL_FR_ACS:
                errs.append(f"{s[0]}: unknown FR AC {a}")
        for a in s[7]:
            if a not in ALL_API_IDS:
                errs.append(f"{s[0]}: unknown API id {a}")

    for t in tasks:
        i, stories, title, done, owner, h, deps, sprint, refs = t
        if owner not in HOURS_CAP:
            errs.append(f"{i}: bad owner {owner}")
        elif h > HOURS_CAP[owner] or h <= 0:
            errs.append(f"{i}: {h}h exceeds cap {HOURS_CAP[owner]}h for {owner}")
        if sprint not in WEEK:
            errs.append(f"{i}: bad sprint {sprint}")
        for s in story_ids(stories):
            if s not in sids:
                errs.append(f"{i}: unknown story {s}")
        if not title.strip() or not done.strip():
            errs.append(f"{i}: empty title/done")
        for d in (x.strip() for x in deps.split(",") if x.strip()):
            if ".." in d:
                continue
            if d not in by_id:
                errs.append(f"{i}: unknown dependency {d}")
            elif SW.get(d, 0) > SW.get(i, 99):
                errs.append(f"{i} (W{SW.get(i)}) scheduled before dependency {d} (W{SW.get(d)})")

    covered_stories = {s for t in tasks for s in story_ids(t[1])}
    for s in STORIES:
        if s[0] not in covered_stories:
            errs.append(f"story {s[0]} has no task")
    dev_refs = set().union(*(tokens(t[8]) for t in tasks))  # SA owns AI/ML from 2026-11-01
    for a in ALL_API_IDS:
        if a not in dev_refs:
            errs.append(f"API {a} is not implemented by any task")
    all_refs = set().union(*(tokens(t[8]) for t in tasks))
    for e in EVENTS:
        for role in ("pub", "sub"):
            if f"{e}({role})" not in all_refs:
                errs.append(f"event {e} has no {role} task")
    for ac in ALL_FR_ACS:
        if not any(ac in s[8] for s in STORIES):
            errs.append(f"FR {ac} not covered by any story")
    return errs


def epic_of(task_id: str) -> str:
    return task_id.split("-")[0]


def render_stories(tasks: list[Task]) -> str:
    tasks_by_story = defaultdict(list)
    for t in tasks:
        for s in story_ids(t[1]):
            tasks_by_story[s].append(t)
    prio = {"M": "Must", "S": "Should", "C": "Could"}
    out = ["# User story", "", "> Status: **Approved — G2 passed on 2026-10-26**.", "",
           "> Generated automatically from `tools/plan/stories.py` and `tools/plan/tasks_*.py` by `python3 -I tools/plan/build.py`. "
           "Do not edit this file by hand: edit the source and re-run.",
           "> Every story traces to a use case ([FR](functional-requirements.md)), API ([detailed design](../03-architecture/detailed-design/README.md)) "
           "and each person's specific tasks ([backlog](../05-plan/backlog.md)).", ""]
    cnt = defaultdict(int)
    for s in STORIES:
        cnt[s[5]] += 1
    out += [f"Total: **{len(STORIES)} stories** — Must {cnt['M']}, Should {cnt['S']}, Could {cnt['C']}.", "",
            "| ID | Story | Role | MoSCoW | UC | Task | Hours |", "|---|---|---|---|---|---|---|"]
    for s in STORIES:
        ts = tasks_by_story[s[0]]
        out.append(f"| [{s[0]}](#{s[0].lower()}) | {s[1]} | {s[2]} | {prio[s[5]]} | {', '.join(s[6])} | {len(ts)} | {sum(t[5] for t in ts)} |")
    out += ["", "## Functional requirement acceptance criteria coverage", "",
            "Every AC in [functional-requirements.md](functional-requirements.md) must belong to at least one story (checked by the script).", "",
            "| AC | Story |", "|---|---|"]
    for ac in ALL_FR_ACS:
        out.append(f"| {ac} | {', '.join(s[0] for s in STORIES if ac in s[8])} |")
    for s in STORIES:
        sid, title, role, want, so, pr, ucs, apis, acs, crit = s
        out += ["", f"## {sid}", f"### {title} — {prio[pr]}", "",
                f"**As** {role}, **I want** {want}, **so that** {so}.", "",
                f"- Use case: {', '.join(ucs) or '—'} · API: {', '.join(apis) or '—'} · FR ACs: {', '.join(acs) or '—'}", "",
                "**Acceptance criteria**", ""]
        out += [f"{n}. {c}" for n, c in enumerate(crit, 1)]
        ts = sorted(tasks_by_story[sid], key=lambda t: (wk(t), t[0]))
        out += ["", "**Task**", "", "| Task | Work | Owner | Hours | Week |", "|---|---|---|---|---|"]
        out += [f"| {t[0]} | {t[2]} | {t[4]} | {t[5]} | W{wk(t)} |" for t in ts]
    return "\n".join(out) + "\n"


def load_table(tasks: list[Task]):
    load = defaultdict(lambda: defaultdict(int))
    for t in tasks:
        if STATUS.get(t[0], ("todo",))[0] != "done":  # completed tasks no longer consume week hours
            load[t[4]][wk(t)] += t[5]
    return load


def render_backlog(tasks: list[Task]) -> str:
    load = load_table(tasks)
    owners = ["SA", "D1", "D2", "D3"]
    out = ["# Detailed backlog", "",
           "> Generated automatically by `python3 -I tools/plan/build.py` from `tools/plan/tasks_sa.py`, `tasks_dev.py`, `stories.py`. "
           "Do not edit by hand. Import into GitHub Projects with [backlog.csv](backlog.csv). Work per person: [by-person](by-person/).", "",
           f"Total: **{len(tasks)} tasks**, **{sum(t[5] for t in tasks)} hours** · dev: each task ≤ 4 hours, SA: ≤ 8 hours.", "",
           "## Remaining load per person and per week (hours; completed tasks excluded)", "", "| Week | " + " | ".join(owners) + " |", "|---|" + "---|" * len(owners)]
    for week in range(1, 13):
        cells = []
        for o in owners:
            v = load[o][week]
            cells.append("" if not v else (f"**{v}**" if v > LOAD_WARN[o] else str(v)))
        out.append(f"| W{week} | " + " | ".join(cells) + " |")
    out.append("| **Total** | " + " | ".join(f"**{sum(load[o].values())}**" for o in owners) + " |")
    out += ["", f"Bold = above the warning threshold (dev > {LOAD_WARN['D1']}h/week, SA > {LOAD_WARN['SA']}h/week).", "",
            "## Sprint schedule", "", "| Code | Period |", "|---|---|"] + [f"| {k} | {v} |" for k, v in SPRINT_LABEL.items()]
    for code, name in EPICS.items():
        rows = [t for t in tasks if epic_of(t[0]) == code]
        if not rows:
            continue
        out += ["", f"## {code} — {name}", "",
                "| ID | Story | Task | Done when | Owner | Hours | Depends on | Sprint | Traceability |", "|---|---|---|---|---|---|---|---|---|"]
        for t in sorted(rows, key=lambda t: (wk(t), t[0])):
            out.append(f"| {t[0]} | {t[1]} | {t[2]} | {t[3]} | {t[4]} | {t[5]} | {t[6]} | {t[7]} | {t[8]} |")
    return "\n".join(out) + "\n"


def mark(task_id: str) -> str:
    state = STATUS.get(task_id, ("todo",))[0]
    return {"done": "☑", "partial": "◐"}.get(state, "☐")


def status_note(task_id: str) -> str:
    if task_id not in STATUS:
        return ""
    state, commit, note = STATUS[task_id]
    return f" **[{'done' if state == 'done' else 'partially done'}, commit {commit}: {note}]**"


def render_person(owner: str, tasks: list[Task]) -> str:
    mine = [t for t in tasks if t[4] == owner]
    done_n = sum(1 for t in mine if STATUS.get(t[0], ("todo",))[0] == "done")
    part_n = sum(1 for t in mine if STATUS.get(t[0], ("todo",))[0] == "partial")
    out = [f"# Work for {PERSON[owner]}", "",
           "> Generated automatically by `python3 -I tools/plan/build.py`. Each task is one PR (branch name `<ID>-short-description`). "
           "Follow the week order; do not start a task whose dependencies are not done.", "",
           "**Definition of Done for every task:** unit tests green, lint clean, no secrets, PR reviewed and merged through the pipeline.", "",
           f"Total: **{len(mine)} tasks, {sum(t[5] for t in mine)} hours**. Done: **{done_n}**, partially done: **{part_n}** "
           "(☑ done, ◐ partial, ☐ not started; recorded in `tools/plan/status.py`).", ""]
    weeks = sorted({wk(t) for t in mine})
    for week in weeks:
        ws = sorted((t for t in mine if wk(t) == week), key=lambda t: t[0])
        out += [f"## Week {week} — {sum(t[5] for t in ws)} hours", "", "| ✓ | ID | Work | Done when | Hours | Depends on | Story |", "|---|---|---|---|---|---|---|"]
        out += [f"| {mark(t[0])} | {t[0]} | {t[2]}{status_note(t[0])} | {t[3]} | {t[5]} | {t[6]} | {t[1]} |" for t in ws]
        out.append("")
    return "\n".join(out)


def write_csv(tasks: list[Task]) -> None:
    with open(DOCS / "05-plan" / "backlog.csv", "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(["ID", "Epic", "Stories", "Title", "Done when", "Owner", "Estimate (h)", "Depends on", "Sprint", "Week", "Refs"])
        for t in sorted(tasks, key=lambda t: (wk(t), t[0])):
            w.writerow([t[0], epic_of(t[0]), t[1], t[2], t[3], t[4], t[5], t[6], t[7], wk(t), t[8]])


def main() -> int:
    tasks: list[Task] = list(SA_TASKS) + list(DEV_TASKS)
    errs = schedule(tasks) + validate(tasks)
    errs += [f"status.py: task does not exist: {i}" for i in STATUS if i not in {t[0] for t in tasks}]
    if errs:
        print(f"{len(errs)} validation error(s):")
        for e in errs:
            print("  -", e)
        return 1
    (DOCS / "05-plan" / "by-person").mkdir(parents=True, exist_ok=True)
    (DOCS / "02-requirements" / "user-stories.md").write_text(render_stories(tasks), encoding="utf-8")
    (DOCS / "05-plan" / "backlog.md").write_text(render_backlog(tasks), encoding="utf-8")
    write_csv(tasks)
    for o in ("SA", "D1", "D2", "D3"):
        (DOCS / "05-plan" / "by-person" / f"{o}.md").write_text(render_person(o, tasks), encoding="utf-8")
    load = load_table(tasks)
    print(f"OK: {len(STORIES)} stories, {len(tasks)} tasks, {sum(t[5] for t in tasks)}h")
    for o in ("SA", "D1", "D2", "D3"):
        row = " ".join(f"W{w}:{load[o][w]}" for w in sorted(load[o]))
        print(f"  {o:2} total {sum(load[o].values()):3}h | {row}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
