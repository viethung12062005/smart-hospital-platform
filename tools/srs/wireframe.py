"""App-shell wireframes in the look of the web app (header, side navigation, content blocks stacked top-down).

Used for SRS screens that the web app does not implement yet. Output is one draw.io <mxGraphModel> per screen.
"""
from __future__ import annotations

import html

from drawio import Graph

TEAL, TEAL_SOFT, INK, MUTED, LINE = "#0F6E66", "#DDEBE8", "#1B2A29", "#667B78", "#C9D6D4"
SIDE_W, LEFT, TOP, CW = 250, 280, 80, 1020


def wireframe(user: str, nav: list, active: str, blocks: list[dict]) -> str:
    g = Graph()
    y = TOP + 12

    def text(value, x, yy, w, h=24, bold=False, color=INK, size=12, align="left"):
        g.vertex(html.escape(value), f"text;html=1;align={align};verticalAlign=middle;whiteSpace=wrap;fontSize={size};"
                 f"fontColor={color};{'fontStyle=1;' if bold else ''}", x, yy, w, h)

    def box(label, x, yy, w, h, fill="#FFFFFF", stroke=LINE, extra=""):
        g.vertex(html.escape(label), f"rounded=1;whiteSpace=wrap;html=1;arcSize=8;fillColor={fill};strokeColor={stroke};"
                 f"fontSize=12;fontColor={INK};{extra}", x, yy, w, h)

    for b in blocks:
        t = b["t"]
        if t == "h":
            text(b["text"], LEFT, y, CW, 36, bold=True, size=20)
            y += 38
        elif t == "p":
            text(b["text"], LEFT, y, CW, 26, color=MUTED)
            y += 30
        elif t == "tabs":
            x = LEFT
            for i, name in enumerate(b["items"]):
                w = 28 + 9 * len(name)
                on = i == b.get("active", 0)
                box(name, x, y, w, 34, fill=TEAL_SOFT if on else "#FFFFFF", extra="fontStyle=1;" if on else "")
                x += w + 8
            y += 46
        elif t == "banner":
            warn = b.get("kind") == "warn"
            box(b["text"], LEFT, y, CW, 46, fill="#FFF4DC" if warn else TEAL_SOFT, stroke="#E0B050" if warn else TEAL,
                extra="align=left;spacingLeft=12;")
            y += 58
        elif t == "form":
            cols = b.get("cols", 2)
            fw = (CW - 24 * (cols - 1)) / cols
            for i, f in enumerate(b["fields"]):
                label, kind = f[0], f[1]
                value = f[2] if len(f) > 2 else ""
                c, r = i % cols, i // cols
                fx, fy = LEFT + c * (fw + 24), y + r * 74
                text(label, fx, fy, fw, 20, color=MUTED, size=11)
                if kind == "check":
                    g.vertex("", "rounded=0;whiteSpace=wrap;html=1;fillColor=#FFFFFF;strokeColor=#555555;", fx, fy + 26, 20, 20)
                    text(value, fx + 28, fy + 24, fw - 28, 24)
                elif kind == "area":
                    box(value, fx, fy + 22, fw, 46, extra="align=left;spacingLeft=10;verticalAlign=top;")
                elif kind == "label":
                    text(value, fx, fy + 24, fw, 24, bold=True)
                else:
                    shown = value + ("   ▾" if kind == "select" else "")
                    box(shown, fx, fy + 22, fw, 34, extra="align=left;spacingLeft=10;")
            y += ((len(b["fields"]) + cols - 1) // cols) * 74 + 4
        elif t == "buttons":
            x = LEFT
            for name, kind in b["items"]:
                w = 36 + 9 * len(name)
                primary, danger = kind == "primary", kind == "danger"
                color = "#FFFFFF" if primary else ("#C0392B" if danger else INK)
                box(name, x, y, w, 38, fill=TEAL if primary else "#FFFFFF",
                    stroke=TEAL if primary else ("#C0392B" if danger else LINE), extra=f"fontStyle=1;fontColor={color};")
                x += w + 12
            y += 54
        elif t == "toggle":
            on = b.get("on")
            g.vertex("", f"rounded=1;arcSize=50;whiteSpace=wrap;html=1;fillColor={TEAL if on else '#C9D6D4'};strokeColor=none;",
                     LEFT, y + 4, 44, 24)
            g.vertex("", "ellipse;whiteSpace=wrap;html=1;fillColor=#FFFFFF;strokeColor=none;", LEFT + (22 if on else 2), y + 6, 20, 20)
            text(b["label"], LEFT + 56, y, CW - 56, 32)
            y += 40
        elif t == "table":
            cols = b["cols"]
            widths = b.get("widths") or [CW / len(cols)] * len(cols)
            x = LEFT
            for c, w in zip(cols, widths):
                g.vertex(html.escape(c), f"rounded=0;whiteSpace=wrap;html=1;fillColor={TEAL};strokeColor={TEAL};fontColor=#FFFFFF;"
                         "fontStyle=1;fontSize=11;align=left;spacingLeft=8;", x, y, w, 34)
                x += w
            y += 34
            for ri, row in enumerate(b["rows"]):
                x = LEFT
                for c, w in zip(row, widths):
                    badge = c.startswith("#")
                    extra = f"fontColor={TEAL};fontStyle=1;" if badge else ""
                    g.vertex(html.escape(c[1:] if badge else c),
                             f"rounded=0;whiteSpace=wrap;html=1;fillColor={'#F6F9F8' if ri % 2 else '#FFFFFF'};strokeColor={LINE};"
                             f"fontSize=11;align=left;spacingLeft=8;{extra}", x, y, w, 34)
                    x += w
                y += 34
            y += 14
        elif t == "chat":
            for who, msg in b["msgs"]:
                me = who == "me"
                w = min(640, 40 + 7 * len(msg))
                box(msg, LEFT + (CW - w if me else 0), y, w, 40, fill=TEAL if me else TEAL_SOFT, stroke=TEAL if me else LINE,
                    extra=f"fontColor={'#FFFFFF' if me else INK};align=left;spacingLeft=10;")
                y += 50
        elif t == "card":
            h = 36 + 24 * len(b["lines"])
            box("", LEFT, y, CW, h)
            text(b["title"], LEFT + 14, y + 6, CW - 28, 24, bold=True)
            for i, ln in enumerate(b["lines"]):
                text(ln, LEFT + 14, y + 34 + 24 * i, CW - 28, 22, size=11)
            y += h + 12
        elif t == "split":
            half = (CW - 16) / 2
            hmax = max(36 + 24 * len(c["lines"]) for c in b["cards"])
            for i, c in enumerate(b["cards"]):
                x = LEFT + i * (half + 16)
                box("", x, y, half, hmax)
                text(c["title"], x + 14, y + 6, half - 28, 24, bold=True)
                for j, ln in enumerate(c["lines"]):
                    text(ln, x + 14, y + 34 + 24 * j, half - 28, 22, size=11)
            y += hmax + 12
        elif t == "gap":
            y += b.get("h", 16)

    height = max(y + 40, 640)
    shell = Graph()
    shell.n = 5000
    shell.vertex("", "rounded=0;whiteSpace=wrap;html=1;fillColor=#F5F7F7;strokeColor=#555555;", 20, 20, LEFT + CW - 10, height)
    shell.vertex("", f"rounded=0;whiteSpace=wrap;html=1;fillColor={TEAL_SOFT};strokeColor=none;", 20, 20, LEFT + CW - 10, 52)
    shell.vertex("<b>SHP</b>", f"rounded=1;whiteSpace=wrap;html=1;fillColor={TEAL};fontColor=#FFFFFF;strokeColor=none;fontSize=11;",
                 40, 28, 40, 36)
    shell.vertex("<b>Bệnh viện thông minh</b>", f"text;html=1;align=left;verticalAlign=middle;fontSize=14;fontColor={TEAL};",
                 92, 28, 260, 36)
    shell.vertex(html.escape(user) + "   ▾", f"text;html=1;align=right;verticalAlign=middle;fontSize=12;fontColor={INK};",
                 LEFT + CW - 330, 28, 330, 36)
    shell.vertex("", f"rounded=0;whiteSpace=wrap;html=1;fillColor=#FFFFFF;strokeColor={LINE};", 20, 72, SIDE_W, height - 52)
    ny = 92
    for item in nav:
        if item.startswith("#"):
            shell.vertex(html.escape(item[1:].upper()),
                         f"text;html=1;align=left;verticalAlign=middle;fontSize=10;fontColor={MUTED};fontStyle=1;", 40, ny, 200, 22)
            ny += 26
            continue
        on = item == active
        shell.vertex(html.escape(item), "rounded=1;whiteSpace=wrap;html=1;arcSize=12;align=left;spacingLeft=14;fontSize=12;"
                     f"fillColor={TEAL_SOFT if on else '#FFFFFF'};strokeColor=none;fontColor={TEAL if on else INK};"
                     f"{'fontStyle=1;' if on else ''}", 32, ny, SIDE_W - 24, 34)
        ny += 40
    shell.vertex("Wireframe: bản phác thảo, chưa có trong giao diện web hiện tại",
                 f"text;html=1;align=right;fontSize=10;fontColor={MUTED};", LEFT + CW - 430, height + 24, 440, 20)
    g.cells = shell.cells + g.cells
    return g.xml(width=LEFT + CW + 50, height=height + 60)
