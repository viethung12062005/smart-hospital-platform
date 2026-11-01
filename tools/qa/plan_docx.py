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
    model = _Model({"system": d["system"], "team": d["team"], "version": d["version"], "prepared_by": "Kiến trúc sư / DevOps – Nhóm 12"})
    w = Writer(model, {})
    w.header_footer()

    for _ in range(7):
        w.para()
    w.para(d["system"].upper(), bold=True, size=24, color=NAVY, align=WD_ALIGN_PARAGRAPH.CENTER)
    w.para(d["title"].upper(), bold=True, size=18, color=NAVY, align=WD_ALIGN_PARAGRAPH.CENTER)
    w.para("(Master Test Plan)", italic=True, size=12, color=GREY, align=WD_ALIGN_PARAGRAPH.CENTER)
    w.para()
    w.para(f"Dựa trên Đặc tả yêu cầu phần mềm v{d['srs_version']}", size=12, align=WD_ALIGN_PARAGRAPH.CENTER)
    for _ in range(8):
        w.para()
    w.para(f"Phiên bản: {d['version']}", bold=True, align=WD_ALIGN_PARAGRAPH.CENTER)
    w.para(d["team"], align=WD_ALIGN_PARAGRAPH.CENTER)
    w.para(d["date_text"], align=WD_ALIGN_PARAGRAPH.CENTER)
    w.page_break()
    w.para("Mục lục", bold=True, size=14, color=NAVY)
    w.field(w.doc.add_paragraph(), 'TOC \\o "1-3" \\h \\z \\u')
    w.note("Nhấn chuột phải vào mục lục → Update Field nếu số trang chưa đúng.")
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
    w.heading("Phụ lục A: Hiện trạng độ phủ kiểm thử", 1)
    w.para(f"Số liệu sinh tự động từ danh mục test case (SRS v{d['srs_version']}). Chi tiết từng test case và ma trận truy vết: "
           "Test_Cases.xlsx và docs/06-testing/traceability.md.")
    w.table(["Chỉ số", "Giá trị"], [
        ["Tổng số test case", str(stats["total"])],
        ["Test case P0", str(stats["p0_total"])],
        ["Đã tự động hóa hoặc một phần", f"{stats['automated_or_partial']} ({stats['automated_or_partial'] * 100 // stats['total']}%)"],
        ["Tự động hóa đầy đủ", str(stats["automated"])],
        ["P0 đã tự động hóa ít nhất một phần", f"{stats['p0_automated_or_partial']} / {stats['p0_total']}"],
    ], widths=(8.0, 8.5), font=9.5)
    mods = sorted({r["module"] for r in rows})
    table_rows = []
    for mod in mods:
        c = Counter(r["status"] for r in rows if r["module"] == mod)
        table_rows.append([mod, str(sum(c.values())), str(c["automated"]), str(c["partial"]), str(c["planned"]), str(c["manual"])])
    w.table(["Module", "Tổng", "Tự động", "Một phần", "Chờ viết", "Thủ công"], table_rows, widths=(4.5, 2.2, 2.4, 2.4, 2.5, 2.5), font=9.5)
    w.doc.core_properties.title = f"{d['system']} - {d['title']}"
    w.doc.core_properties.author = d["team"]
    w.doc.save(path)
