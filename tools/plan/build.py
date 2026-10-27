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
    "S0": "W1 26/10-01/11: chốt gate G1-G3, tạo repo, onboarding",
    "P1": "W2 02/11-08/11: platform 1; dev làm thư viện dùng chung + dữ liệu giả",
    "P2": "W3 09/11-15/11: platform 2; dev làm thư viện dùng chung",
    "P3": "W4 16/11-22/11: platform 3 (song song S1)",
    "P4": "W5 23/11-29/11: identity, môi trường ephemeral, G4 (song song S2)",
    "S1": "W4 16/11-22/11: dev bắt đầu service",
    "S2": "W5 23/11-29/11", "S3": "W6 30/11-06/12", "S4": "W7 07/12-13/12", "S5": "W8 14/12-20/12",
    "S6": "W9 21/12-27/12", "H1": "W10 28/12-03/01: hardening, load/failure test",
    "H2": "W11 04/01-10/01: eval, FinOps, bảo mật", "DEF": "W12 11/01-17/01: chuẩn bị bảo vệ",
}
EPICS = {
    "ARC": "Kiến trúc & quản trị dự án", "GOV": "Repo, quy trình & GitHub governance", "IAC": "Hạ tầng Azure bằng Terraform",
    "CIT": "CI templates (GitHub Actions)", "GOP": "Helm & GitOps (Argo CD)", "ENV": "Vòng đời môi trường ephemeral",
    "OBS": "Observability nền", "LOC": "Môi trường local & walking skeleton", "SCF": "Scaffold service (SA)", "GW": "API Gateway (SA)",
    "MLO": "MLOps pipeline (SA)", "PRD": "Production engineering & bảo vệ",
    "SHR": "Thư viện dùng chung (.NET)", "PY": "Thư viện dùng chung (Python)", "DAT": "Dữ liệu giả",
    "SCH": "Scheduling service", "NTF": "Notification service", "CLN": "Clinical service", "DOC": "Document service",
    "AUD": "Audit service", "AI": "AI service", "ML": "ML no-show", "WEB": "Web app (PWA)",
}
PERSON = {"SA": "SA — Solution Architect / PM / DevOps", "D1": "D1 — Dev .NET (scheduling, notification, phân quyền, UI đặt lịch)",
          "D2": "D2 — Dev .NET (clinical, document, audit, lõi FHIR, UI lâm sàng)", "D3": "D3 — Dev Python/AI (AI, ML, dữ liệu giả, UI AI)"}
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
    sprint as a lower bound) and moves later only when the week is full. SA tasks are fixed (their overload is reported, not hidden)."""
    errs: list[str] = []
    by_id = {t[0]: t for t in tasks}
    story_prio = {s[0]: PRIO[s[5]] for s in STORIES}
    SW.clear()
    for t in tasks:
        if t[4] == "SA":
            SW[t[0]] = WEEK[t[7]]
    pending = [t for t in tasks if t[4] != "SA"]

    def prio(t: Task) -> int:
        ids = story_ids(t[1])
        return min((story_prio.get(i, 0) for i in ids), default=0)

    load: dict[tuple[str, int], int] = defaultdict(int)
    while pending:
        ready = []
        for t in pending:
            deps = [d.strip() for d in t[6].split(",") if d.strip() and ".." not in d]
            if all(d in SW or d not in by_id for d in deps):
                lower = WEEK[t[7]] if t[7] in ("H1", "H2", "DEF") else DEV_START
                ready.append((t, max([lower] + [SW[d] for d in deps if d in SW])))
        if not ready:
            errs.append("dependency cycle among: " + ", ".join(t[0] for t in pending))
            break
        ready.sort(key=lambda x: (x[1], prio(x[0]), x[0]))
        t, earliest = ready[0]
        w = earliest
        while load[(t[4], w)] + t[5] > DEV_CAP and w <= LAST_WEEK:
            w += 1
        if w > LAST_WEEK:
            errs.append(f"{t[0]}: cannot be scheduled by W{LAST_WEEK} within {DEV_CAP}h/week for {t[4]}")
            w = LAST_WEEK
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
    dev_refs = set().union(*(tokens(t[8]) for t in tasks if t[4] != "SA"))
    for a in ALL_API_IDS:
        if a not in dev_refs:
            errs.append(f"API {a} is not implemented by any developer task")
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
    out = ["# User story", "", "> Trạng thái: **Approved — G2 qua ngày 2026-10-26**.", "",
           "> Sinh tự động từ `tools/plan/stories.py` và `tools/plan/tasks_*.py` bằng `python3 -I tools/plan/build.py`. "
           "Không sửa tay file này: sửa nguồn rồi chạy lại.",
           "> Mỗi story truy về use case ([FR](functional-requirements.md)), API ([thiết kế chi tiết](../03-architecture/detailed-design/README.md)) "
           "và các task cụ thể của từng người ([backlog](../05-plan/backlog.md)).", ""]
    cnt = defaultdict(int)
    for s in STORIES:
        cnt[s[5]] += 1
    out += [f"Tổng: **{len(STORIES)} story** — Must {cnt['M']}, Should {cnt['S']}, Could {cnt['C']}.", "",
            "| ID | Story | Vai trò | MoSCoW | UC | Task | Giờ |", "|---|---|---|---|---|---|---|"]
    for s in STORIES:
        ts = tasks_by_story[s[0]]
        out.append(f"| [{s[0]}](#{s[0].lower()}) | {s[1]} | {s[2]} | {prio[s[5]]} | {', '.join(s[6])} | {len(ts)} | {sum(t[5] for t in ts)} |")
    out += ["", "## Độ phủ tiêu chí nghiệm thu của yêu cầu chức năng", "",
            "Mỗi AC trong [functional-requirements.md](functional-requirements.md) phải thuộc ít nhất một story (script kiểm tra).", "",
            "| AC | Story |", "|---|---|"]
    for ac in ALL_FR_ACS:
        out.append(f"| {ac} | {', '.join(s[0] for s in STORIES if ac in s[8])} |")
    for s in STORIES:
        sid, title, role, want, so, pr, ucs, apis, acs, crit = s
        out += ["", f"## {sid}", f"### {title} — {prio[pr]}", "",
                f"**Là** {role}, **tôi muốn** {want}, **để** {so}.", "",
                f"- Use case: {', '.join(ucs) or '—'} · API: {', '.join(apis) or '—'} · AC của FR: {', '.join(acs) or '—'}", "",
                "**Tiêu chí nghiệm thu**", ""]
        out += [f"{n}. {c}" for n, c in enumerate(crit, 1)]
        ts = sorted(tasks_by_story[sid], key=lambda t: (wk(t), t[0]))
        out += ["", "**Task**", "", "| Task | Việc | Người | Giờ | Tuần |", "|---|---|---|---|---|"]
        out += [f"| {t[0]} | {t[2]} | {t[4]} | {t[5]} | W{wk(t)} |" for t in ts]
    return "\n".join(out) + "\n"


def load_table(tasks: list[Task]):
    load = defaultdict(lambda: defaultdict(int))
    for t in tasks:
        if STATUS.get(t[0], ("todo",))[0] != "done":  # task đã xong không còn chiếm giờ của tuần
            load[t[4]][wk(t)] += t[5]
    return load


def render_backlog(tasks: list[Task]) -> str:
    load = load_table(tasks)
    owners = ["SA", "D1", "D2", "D3"]
    out = ["# Backlog chi tiết", "",
           "> Sinh tự động bằng `python3 -I tools/plan/build.py` từ `tools/plan/tasks_sa.py`, `tasks_dev.py`, `stories.py`. "
           "Không sửa tay. Import vào GitHub Projects bằng [backlog.csv](backlog.csv). Việc theo từng người: [by-person](by-person/).", "",
           f"Tổng: **{len(tasks)} task**, **{sum(t[5] for t in tasks)} giờ** · dev: mỗi task ≤ 4 giờ, SA: ≤ 8 giờ.", "",
           "## Tải còn lại theo người và theo tuần (giờ; task đã xong không tính)", "", "| Tuần | " + " | ".join(owners) + " |", "|---|" + "---|" * len(owners)]
    for week in range(1, 13):
        cells = []
        for o in owners:
            v = load[o][week]
            cells.append("" if not v else (f"**{v}**" if v > LOAD_WARN[o] else str(v)))
        out.append(f"| W{week} | " + " | ".join(cells) + " |")
    out.append("| **Tổng** | " + " | ".join(f"**{sum(load[o].values())}**" for o in owners) + " |")
    out += ["", f"In đậm = vượt ngưỡng cảnh báo (dev > {LOAD_WARN['D1']}h/tuần, SA > {LOAD_WARN['SA']}h/tuần).", "",
            "## Lịch sprint", "", "| Mã | Thời gian |", "|---|---|"] + [f"| {k} | {v} |" for k, v in SPRINT_LABEL.items()]
    for code, name in EPICS.items():
        rows = [t for t in tasks if epic_of(t[0]) == code]
        if not rows:
            continue
        out += ["", f"## {code} — {name}", "",
                "| ID | Story | Task | Xong khi | Người | Giờ | Phụ thuộc | Sprint | Truy vết |", "|---|---|---|---|---|---|---|---|---|"]
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
    return f" **[{'xong' if state == 'done' else 'làm một phần'}, commit {commit}: {note}]**"


def render_person(owner: str, tasks: list[Task]) -> str:
    mine = [t for t in tasks if t[4] == owner]
    done_n = sum(1 for t in mine if STATUS.get(t[0], ("todo",))[0] == "done")
    part_n = sum(1 for t in mine if STATUS.get(t[0], ("todo",))[0] == "partial")
    out = [f"# Việc của {PERSON[owner]}", "",
           "> Sinh tự động bằng `python3 -I tools/plan/build.py`. Mỗi task là một PR (tên nhánh `<ID>-mo-ta-ngan`). "
           "Làm đúng thứ tự tuần; task có phụ thuộc chưa xong thì không bắt đầu.", "",
           "**Definition of Done mọi task:** test đơn vị xanh, lint sạch, không secret, PR được review và merge qua pipeline.", "",
           f"Tổng: **{len(mine)} task, {sum(t[5] for t in mine)} giờ**. Đã xong: **{done_n}**, làm một phần: **{part_n}** "
           "(☑ xong, ◐ một phần, ☐ chưa; ghi trong `tools/plan/status.py`).", ""]
    weeks = sorted({wk(t) for t in mine})
    for week in weeks:
        ws = sorted((t for t in mine if wk(t) == week), key=lambda t: t[0])
        out += [f"## Tuần {week} — {sum(t[5] for t in ws)} giờ", "", "| ✓ | ID | Việc | Xong khi | Giờ | Phụ thuộc | Story |", "|---|---|---|---|---|---|---|"]
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
    errs += [f"status.py: task không tồn tại: {i}" for i in STATUS if i not in {t[0] for t in tasks}]
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
