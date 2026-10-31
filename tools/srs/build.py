"""Build the Software Requirements Specification from docs/02-requirements/srs/content/*.yaml.

Outputs (docs/02-requirements/srs/dist/):
  SHP_System_Requirement_Specification_v<version>.docx  - SRS following the company template structure
  SHP_System_Analysis_Result_v<version>.xlsx            - requirement/actor/object/function/screen lists
  diagrams/*.drawio + diagrams/png/*.png                 - editable diagrams and their renderings

Usage: python -I tools/srs/build.py [--check] [--no-png]
  --check   validate the content only (ids, cross references, traceability); used by CI
  --no-png  skip draw.io rendering (reuse existing PNGs)
  --only    re-render only figures whose key starts with one of the given prefixes, e.g. --only act-book,ord
"""
from __future__ import annotations

import argparse
import glob
import os
import re
import sys

import yaml

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
SRC = os.path.join(ROOT, "docs", "02-requirements", "srs", "content")
DIST = os.path.join(ROOT, "docs", "02-requirements", "srs", "dist")
MOCKUPS = os.path.join(ROOT, "docs", "02-requirements", "srs", "mockups")
sys.path.insert(0, HERE)

import drawio  # noqa: E402

REF = re.compile(r"\[\[(UC|BR|CBR|SCR|MSG|ET|FIG|CMUC):([a-z0-9-]+)\]\]")


class Model:
    """All content plus the numbering derived from document order."""

    def __init__(self) -> None:
        self.data: dict = {}
        for path in sorted(glob.glob(os.path.join(SRC, "*.yaml"))):
            with open(path, encoding="utf-8") as fh:
                part = yaml.safe_load(fh) or {}
            for k, v in part.items():
                if k in self.data and isinstance(v, list):
                    self.data[k].extend(v)
                elif k in self.data and isinstance(v, dict):
                    self.data[k].update(v)
                else:
                    self.data[k] = v
        self.errors: list[str] = []
        self.num: dict[tuple[str, str], str] = {}
        self.title: dict[tuple[str, str], str] = {}
        self._number()

    def __getitem__(self, k):
        return self.data.get(k, [])

    # ------------------------------------------------------------------ numbering
    def _assign(self, kind: str, key: str, label: str, title: str = "") -> None:
        if (kind, key) in self.num:
            self.errors.append(f"duplicate {kind} key: {key}")
        self.num[(kind, key)] = label
        self.title[(kind, key)] = title

    def use_cases(self):
        for module in self["modules"]:
            for section in module.get("sections", []):
                for uc in section.get("use_cases", []):
                    yield module, section, uc

    def _number(self) -> None:
        n_uc = n_br = 0
        for _, _, uc in self.use_cases():
            n_uc += 1
            self._assign("UC", uc["key"], f"UC {n_uc}", uc["name"])
            for br in uc.get("rules", []):
                n_br += 1
                br["_code"] = f"BR {n_br}"
                if br.get("key"):
                    self._assign("BR", br["key"], br["_code"], br["title"])
        for i, c in enumerate(self["common_use_cases"], 1):
            self._assign("CMUC", c["key"], f"CMUC {i}", c["name"])
            for br in c.get("rules", []):
                n_br += 1
                br["_code"] = f"BR {n_br}"
                if br.get("key"):
                    self._assign("BR", br["key"], br["_code"], br["title"])
        for i, r in enumerate(self["common_rules"], 1):
            self._assign("CBR", r["key"], f"CBR {i}", r["title"])
        i = 0
        for portal in self["screens"]:
            for s in portal["items"]:
                i += 1
                self._assign("SCR", s["key"], f"Màn hình {i}", s["name"])
        for group in self["messages"]:
            for j, m in enumerate(group["items"], 1):
                self._assign("MSG", m["key"], f"{group['prefix']} {j}", m["text"])
        for j, et in enumerate(self["email_templates"], 1):
            self._assign("ET", et["key"], f"ET {j}", et["name"])

    # ------------------------------------------------------------------ references
    def resolve(self, text: str) -> str:
        def sub(m):
            kind, key = m.group(1), m.group(2)
            lookup = (kind, key)
            if lookup not in self.num:
                self.errors.append(f"unresolved reference [[{kind}:{key}]]")
                return f"[[{kind}:{key}]]"
            label, title = self.num[lookup], self.title[lookup]
            if kind == "MSG":
                return f"{label} “{title}”"
            if kind in ("BR", "CBR"):
                return label
            return f"{label}: {title}"
        return REF.sub(sub, str(text))

    # ------------------------------------------------------------------ validation
    def validate(self) -> list[str]:
        roles = {r["key"] for r in self["actors"]}
        steps_seen = set()
        for module, section, uc in self.use_cases():
            where = f"{self.num[('UC', uc['key'])]} ({uc['key']})"
            for f in ("objective", "actors", "trigger", "pre", "post", "trace"):
                if not uc.get(f):
                    self.errors.append(f"{where}: missing '{f}'")
            for a in uc.get("actors", []):
                if a not in roles:
                    self.errors.append(f"{where}: unknown actor '{a}'")
            numbers = {s["n"] for s in uc.get("steps", [])}
            if uc.get("steps") and sorted(numbers) != list(range(1, len(numbers) + 1)):
                self.errors.append(f"{where}: steps must be numbered 1..n")
            for s in uc.get("steps", []):
                for t in ([b["to"] for b in s.get("branches", [])] + _as_list(s.get("next"))):
                    if t != "end" and t not in numbers:
                        self.errors.append(f"{where}: step ({s['n']}) points to missing step {t}")
                if s["lane"] >= len(uc.get("lanes", [])):
                    self.errors.append(f"{where}: step ({s['n']}) lane out of range")
            for br in uc.get("rules", []):
                for ref in re.findall(r"\((\d+)\)", br.get("step", "")):
                    if int(ref) not in numbers:
                        self.errors.append(f"{where}: {br['_code']} refers to missing step ({ref})")
            for text in _texts(uc):
                self.resolve(text)
            steps_seen.add(uc["key"])
        for c in self["common_use_cases"]:
            for text in _texts(c):
                self.resolve(text)
        for portal in self["screens"]:
            for s in portal["items"]:
                for text in _texts(s):
                    self.resolve(text)
        for r in self["common_rules"]:
            for text in _texts(r):
                self.resolve(text)
        keys = {uc["key"] for _, _, uc in self.use_cases()}
        for g in self["uc_diagrams"]:
            for k in g["cases"]:
                if k not in keys:
                    self.errors.append(f"use case diagram '{g['name']}' lists unknown use case '{k}'")
        listed = {k for g in self["uc_diagrams"] for k in g["cases"]}
        for k in keys - listed:
            self.errors.append(f"use case '{k}' is not on any use case diagram")
        for row in self["permissions"]["rows"]:
            for k in row.get("uc", []):
                if k not in keys:
                    self.errors.append(f"permission row '{row['function']}' refers to unknown use case '{k}'")
        screen_keys = {sc["key"] for portal in self["screens"] for sc in portal["items"]}
        wf = self.data.get("wireframes", {})
        shots = {os.path.splitext(f)[0] for f in os.listdir(MOCKUPS)} if os.path.isdir(MOCKUPS) else set()
        for k in wf:
            if k not in screen_keys:
                self.errors.append(f"wireframe '{k}' is not a screen key")
        for k in sorted(screen_keys - set(wf) - shots):
            self.errors.append(f"screen '{k}' has neither a screenshot (srs/mockups/{k}.png) nor a wireframe")
        return sorted(set(self.errors))


def _as_list(v):
    if v is None:
        return []
    return v if isinstance(v, list) else [v]


def _texts(obj):
    """Every string inside a dict/list (to resolve references)."""
    if isinstance(obj, str):
        yield obj
    elif isinstance(obj, dict):
        for k, v in obj.items():
            if not str(k).startswith("_"):
                yield from _texts(v)
    elif isinstance(obj, list):
        for v in obj:
            yield from _texts(v)


# ---------------------------------------------------------------------------------------------- diagrams
def build_diagrams(m: Model, png: bool, only: list[str] | None = None) -> dict[str, str]:
    """Write one .drawio per diagram family; return {figure key: png path}."""
    out = os.path.join(DIST, "diagrams")
    os.makedirs(os.path.join(out, "png"), exist_ok=True)
    families: dict[str, list[tuple[str, str, str]]] = {}   # file -> [(fig key, page name, xml)]

    hl = m["high_level"]
    roles = {r["key"]: r["name"] for r in m["actors"]}
    ord_ = hl["ord"]
    families.setdefault("01-object-relationship", []).append(
        ("ord", "Sơ đồ quan hệ đối tượng", drawio.objects(ord_["nodes"], ord_["links"])))
    for wf in hl["workflows"]:
        families.setdefault("02-workflows", []).append(
            (f"wf-{wf['key']}", wf["name"], drawio.activity(wf["lanes"], wf["steps"])))
    for st in hl["state_machines"]:
        families.setdefault("03-state-transitions", []).append(
            (f"st-{st['key']}", st["name"], drawio.states(st["states"], st["transitions"], st["initial"], st["finals"])))
    uc_names = {uc["key"]: uc["name"] for _, _, uc in m.use_cases()}
    for g in m["uc_diagrams"]:
        links = []
        for _, _, uc in m.use_cases():
            if uc["key"] in g["cases"]:
                links += [(roles[a], uc["key"]) for a in uc["actors"] if roles[a] in g["actors_left"] + g.get("actors_right", [])]
        cases = [(k, uc_names[k]) for k in g["cases"]]
        families.setdefault("04-use-case-diagrams", []).append(
            (f"ucd-{g['key']}", g["name"], drawio.use_case(g["name"], g["actors_left"], g.get("actors_right", []), cases, links)))
    for sm in hl["site_maps"]:
        families.setdefault("05-site-maps", []).append((f"sm-{sm['key']}", sm["name"], drawio.tree(sm["tree"])))
    for module, _, uc in m.use_cases():
        if uc.get("steps"):
            families.setdefault(f"10-activity-{module['key']}", []).append(
                (f"act-{uc['key']}", f"{m.num[('UC', uc['key'])]} {uc['name']}"[:60], drawio.activity(uc["lanes"], uc["steps"])))
    for c in m["common_use_cases"]:
        if c.get("steps"):
            families.setdefault("19-activity-common", []).append(
                (f"act-{c['key']}", f"{m.num[('CMUC', c['key'])]} {c['name']}"[:60], drawio.activity(c["lanes"], c["steps"])))

    import wireframe
    screen_name = {sc["key"]: sc["name"] for portal in m["screens"] for sc in portal["items"]}
    for key, spec in m.data.get("wireframes", {}).items():
        families.setdefault("06-wireframes", []).append(
            (f"mock-{key}", screen_name[key][:60], wireframe.wireframe(spec["user"], spec["nav"], spec["active"], spec["blocks"])))

    pngs = {}
    if os.path.isdir(MOCKUPS):                     # real screenshots of the running web app (mock data)
        for f in os.listdir(MOCKUPS):
            if f.endswith(".png"):
                pngs[f"mock-{os.path.splitext(f)[0]}"] = os.path.join(MOCKUPS, f)
    for fname, pages in families.items():
        path = os.path.join(out, f"{fname}.drawio")
        drawio.write_file(path, [(name, xml) for _, name, xml in pages])
        for i, (key, _, _) in enumerate(pages):
            target = os.path.join(out, "png", f"{key}.png")
            if png and (not only or any(key.startswith(o) for o in only)):
                drawio.export_png(path, i, target)
            if os.path.exists(target):
                pngs[key] = target
    return pngs


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--check", action="store_true")
    ap.add_argument("--no-png", action="store_true")
    ap.add_argument("--only", help="comma-separated figure key prefixes to re-render (others reuse existing PNGs)")
    args = ap.parse_args()
    m = Model()
    errors = m.validate()
    if errors:
        print("SRS content errors:")
        for e in errors:
            print("  -", e)
        return 1
    n_uc = sum(1 for _ in m.use_cases())
    n_br = sum(len(uc.get("rules", [])) for _, _, uc in m.use_cases())
    print(f"OK: {n_uc} use cases, {n_br} business rules, "
          f"{sum(len(p['items']) for p in m['screens'])} screens, {len(m['common_rules'])} common rules")
    if args.check:
        return 0
    os.makedirs(DIST, exist_ok=True)
    pngs = build_diagrams(m, png=not args.no_png, only=args.only.split(",") if args.only else None)
    import docx_writer
    import sar_writer
    version = m["document"]["version"]
    docx_path = os.path.join(DIST, f"SHP_System_Requirement_Specification_v{version}.docx")
    docx_writer.write(m, pngs, docx_path)
    xlsx_path = os.path.join(DIST, f"SHP_System_Analysis_Result_v{version}.xlsx")
    sar_writer.write(m, xlsx_path)
    print("wrote", os.path.relpath(docx_path, ROOT))
    print("wrote", os.path.relpath(xlsx_path, ROOT))
    return 0


if __name__ == "__main__":
    sys.exit(main())
