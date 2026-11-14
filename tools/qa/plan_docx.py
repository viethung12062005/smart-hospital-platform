"""Render the Master Test Plan (docs/06-testing/content/10-test-plan.yaml) as .docx, reusing the SRS document writer."""
from __future__ import annotations

import os
from collections import Counter

from docx.enum.text import WD_ALIGN_PARAGRAPH

from common import CONTENT, load_yaml
from docx_writer import NAVY, GREY, Writer  # tools/srs


class _Model:
    """The writer only needs reference resolution and the document block."""

    def __init__(self, doc):
        self.data = {"document": doc}

    def resolve(self, text):
        return str(text)

    def __getitem__(self, key):
        return self.data[key]


def write(rows, stats, path):
    content = load_yaml(os.path.join(CONTENT, "10-test-plan.yaml"))
    d = content["document"]
    model = _Model({"system": d["system"], "team": d["team"], "version": d["version"], "prepared_by": "Architect / DevOps – Team 12"})
    w = Writer(model, {})
    w.header_footer()

    for _ in range(7):
        w.para()
    w.para(d["system"].upper(), bold=True, size=24, color=NAVY, align=WD_ALIGN_PARAGRAPH.CENTER)
    w.para(d["title"].upper(), bold=True, size=18, color=NAVY, align=WD_ALIGN_PARAGRAPH.CENTER)
    w.para("(Master Test Plan)", italic=True, size=12, color=GREY, align=WD_ALIGN_PARAGRAPH.CENTER)
    w.para()
    w.para(f"Based on Software Requirements Specification v{d['srs_version']}", size=12, align=WD_ALIGN_PARAGRAPH.CENTER)
    for _ in range(8):
        w.para()
    w.para(f"Version: {d['version']}", bold=True, align=WD_ALIGN_PARAGRAPH.CENTER)
    w.para(d["team"], align=WD_ALIGN_PARAGRAPH.CENTER)
    w.para(d["date_text"], align=WD_ALIGN_PARAGRAPH.CENTER)
    w.page_break()
    w.para("Table of Contents", bold=True, size=14, color=NAVY)
    w.field(w.doc.add_paragraph(), 'TOC \\o "1-3" \\h \\z \\u')
    w.note("Right-click the table of contents → Update Field if page numbers are incorrect.")
    w.page_break()

    for sec in content["sections"]:
        w.heading(sec["name"], 1)
        for p in sec.get("paragraphs", []):
            w.para(p)
        if sec.get("bullets"):
            w.bullets(sec["bullets"])
        if sec.get("table"):
            t = sec["table"]
            w.table(t["header"], t["rows"], widths=t.get("widths"), font=9)
        for p in sec.get("paragraphs_after", []):
            w.para(p)

    # Appendix: live coverage numbers from the catalog (regenerated with the catalog).
    w.heading("Appendix A: Current test coverage", 1)
    w.para(f"Numbers generated automatically from the test case catalog (SRS v{d['srs_version']}). Details per test case and the traceability matrix: "
           "Test_Cases.xlsx and docs/06-testing/traceability.md.")
    w.table(["Metric", "Value"], [
        ["Total test cases", str(stats["total"])],
        ["P0 test cases", str(stats["p0_total"])],
        ["Automated or partially automated", f"{stats['automated_or_partial']} ({stats['automated_or_partial'] * 100 // stats['total']}%)"],
        ["Fully automated", str(stats["automated"])],
        ["P0 automated at least partially", f"{stats['p0_automated_or_partial']} / {stats['p0_total']}"],
    ], widths=(8.0, 8.5), font=9.5)
    mods = sorted({r["module"] for r in rows})
    table_rows = []
    for mod in mods:
        c = Counter(r["status"] for r in rows if r["module"] == mod)
        table_rows.append([mod, str(sum(c.values())), str(c["automated"]), str(c["partial"]), str(c["planned"]), str(c["manual"])])
    w.table(["Module", "Total", "Automated", "Partial", "Planned", "Manual"], table_rows, widths=(4.5, 2.2, 2.4, 2.4, 2.5, 2.5), font=9.5)
    w.doc.core_properties.title = f"{d['system']} - {d['title']}"
    w.doc.core_properties.author = d["team"]
    w.doc.save(path)
