"""draw.io diagram builders for the SRS (same shapes and styles as the company activity-diagram template).

Every builder returns the XML of one <mxGraphModel>; write_file() bundles them as pages of one .drawio file
and export_png() renders each page with the draw.io desktop CLI.
"""
from __future__ import annotations

import html
import math
import os
import shutil
import subprocess
from xml.sax.saxutils import quoteattr

LANE_HEADER = ("verticalAlign=top;align=left;overflow=fill;fontSize=12;fontFamily=Helvetica;html=1;rounded=0;"
               "shadow=0;strokeColor=#000000;gradientColor=none;whiteSpace=wrap;")
STEP = ("html=1;dashed=0;whitespace=wrap;shape=mxgraph.dfd.start;rounded=0;shadow=0;strokeColor=#000000;"
        "gradientColor=none;whiteSpace=wrap;fontSize=12;")
DECISION = "rhombus;html=1;whiteSpace=wrap;strokeColor=#000000;fontSize=11;"
START = ("strokeWidth=2;html=1;shape=mxgraph.flowchart.start_2;whiteSpace=wrap;rounded=0;shadow=0;"
         "gradientColor=none;fillColor=#000000;")
END = "ellipse;html=1;shape=endState;fillColor=#000000;rounded=0;shadow=0;whiteSpace=wrap;"
EDGE = "edgeStyle=orthogonalEdgeStyle;rounded=0;orthogonalLoop=1;jettySize=auto;html=1;"
LABEL = "edgeLabel;html=1;align=center;verticalAlign=middle;resizable=0;points=[];fontSize=11;"


class Graph:
    def __init__(self) -> None:
        self.cells: list[str] = []
        self.n = 0

    def _id(self) -> str:
        self.n += 1
        return f"c{self.n}"

    def vertex(self, value: str, style: str, x: float, y: float, w: float, h: float) -> str:
        cid = self._id()
        self.cells.append(f'<mxCell id="{cid}" value={quoteattr(value)} style={quoteattr(style)} parent="1" vertex="1">'
                          f'<mxGeometry x="{x:.0f}" y="{y:.0f}" width="{w:.0f}" height="{h:.0f}" as="geometry"/></mxCell>')
        return cid

    def edge(self, src: str, dst: str, label: str = "", style: str = EDGE) -> str:
        cid = self._id()
        self.cells.append(f'<mxCell id="{cid}" value={quoteattr(label)} style={quoteattr(style)} parent="1" '
                          f'source="{src}" target="{dst}" edge="1"><mxGeometry relative="1" as="geometry"/></mxCell>')
        return cid

    def xml(self, width: int = 1100, height: int = 1400) -> str:
        return (f'<mxGraphModel dx="1024" dy="592" grid="1" gridSize="10" guides="1" tooltips="1" connect="1" arrows="1" '
                f'fold="1" page="1" pageScale="1" pageWidth="{width}" pageHeight="{height}" math="0" shadow="0">'
                f'<root><mxCell id="0"/><mxCell id="1" parent="0"/>{"".join(self.cells)}</root></mxGraphModel>')


def esc(text: str) -> str:
    """Plain text -> draw.io html label (line breaks kept)."""
    return "<br>".join(html.escape(line) for line in str(text).split("\n"))


# --------------------------------------------------------------------------------------------- activity diagram
GOTO = "ellipse;whiteSpace=wrap;html=1;fontSize=10;fillColor=#FFFFFF;strokeColor=#000000;dashed=1;"


def activity(lanes: list[str], steps: list[dict]) -> str:
    """Swim-lane activity diagram. steps: {n, lane, text, kind?: action|decision, next?, branches?, end?}.

    Layout: each lane has a main column (left) and an alternative column (right). Forward targets of a decision's
    non-first branch go to the alternative column one row below the decision. The main flow starts a new row whenever it
    does not move to a lane on the right (as in the template). Edges back to an earlier step are drawn as a small
    dashed "→ (n)" connector instead of a long line, and every terminal step gets its own end node below it.
    """
    g = Graph()
    col_w, lane_pad, top, header_h, row_h = 270, 15, 40, 30, 140
    x0 = 40
    by_n = {s["n"]: s for s in steps}
    order = [s["n"] for s in steps]

    alt: set[int] = set()
    for s in steps:
        if s.get("kind") == "decision":
            for b in s["branches"][1:]:
                t = b.get("to")
                if isinstance(t, int) and t > s["n"] and not _is_main_successor(t, steps):
                    alt.add(t)

    rows: dict[int, int] = {}
    cols: dict[int, int] = {}
    row, prev_lane = 0, None
    for s in steps:
        if s["n"] in alt:
            continue
        if prev_lane is not None and (s["lane"] <= prev_lane or s.get("kind") == "decision"):
            row += 1
        rows[s["n"]], cols[s["n"]] = row, 0
        prev_lane = s["lane"] if s.get("kind") != "decision" else 10 ** 6
    used = {(rows[n], by_n[n]["lane"], 0) for n in rows}
    for s in steps:                       # alternative steps, in the order of their decisions
        if s.get("kind") != "decision":
            continue
        for b in s["branches"][1:]:
            t = b.get("to")
            if t in alt and t not in rows:
                r = rows[s["n"]] + 1
                while (r, by_n[t]["lane"], 1) in used:
                    r += 1
                rows[t], cols[t] = r, 1
                used.add((r, by_n[t]["lane"], 1))
    for t in sorted(alt):                 # alternative steps reached only from other alternative steps
        if t not in rows:
            rows[t], cols[t] = max(rows.values()) + 1, 1
    last_row = max(rows.values())
    height = header_h + 70 + (last_row + 1) * row_h + 20
    n_cols = [2 if any(cols[n] == 1 and by_n[n]["lane"] == i for n in rows) else 1 for i in range(len(lanes))]
    lane_x, x = [], x0
    for i in range(len(lanes)):
        lane_x.append(x)
        x += n_cols[i] * col_w + 2 * lane_pad
    total_w = x - x0

    for i, name in enumerate(lanes):
        g.vertex(f'<p style="margin:0px;margin-top:4px;text-align:center"><b>{html.escape(name)}</b></p>',
                 LANE_HEADER, lane_x[i], top, n_cols[i] * col_w + 2 * lane_pad, height)

    def box(n):
        s = by_n[n]
        cx = lane_x[s["lane"]] + lane_pad + cols[n] * col_w + col_w / 2
        y = top + header_h + 70 + rows[n] * row_h
        return cx, y

    ids: dict[int, str] = {}
    for s in steps:
        cx, y = box(s["n"])
        if s.get("kind") == "decision":
            ids[s["n"]] = g.vertex(f'({s["n"]})<br>{esc(s["text"])}', DECISION, cx - 95, y - 12, 190, 84)
        else:
            ids[s["n"]] = g.vertex(f'({s["n"]})<br>{esc(s["text"])}', STEP, cx - 115, y, 230, 60)
    cx, _ = box(steps[0]["n"])
    start = g.vertex("", START, cx - 15, top + header_h + 18, 30, 30)
    g.edge(start, ids[steps[0]["n"]])

    def below(n, style, value="", w=30, h=30):
        cx, y = box(n)
        dy = 72 if by_n[n].get("kind") == "decision" else 78
        return g.vertex(value, style, cx - w / 2, y + dy, w, h)

    def right_of(n, style, value="", w=30, h=30):
        cx, y = box(n)
        return g.vertex(value, style, cx + col_w / 2 + 10 - w / 2 - 10, y + 30 - h / 2 + 2, w, h)

    def connect(src_n, tgt, label=""):
        src = ids[src_n]
        decision = by_n[src_n].get("kind") == "decision"
        if tgt == "end":
            node = right_of(src_n, END) if decision and label else below(src_n, END)
            g.edge(src, node, label)
            return
        backwards = rows[tgt] < rows[src_n] or (rows[tgt] == rows[src_n] and tgt < src_n)
        if backwards:
            style = GOTO
            node = (right_of(src_n, style, f"→ ({tgt})", 46, 30) if decision
                    else below(src_n, style, f"→ ({tgt})", 46, 30))
            g.edge(src, node, label)
            return
        style = EDGE
        if cols.get(tgt) == 1 and cols.get(src_n) == 0 and by_n[tgt]["lane"] == by_n[src_n]["lane"]:
            style += "exitX=1;exitY=0.5;exitDx=0;exitDy=0;entryX=0.5;entryY=0;entryDx=0;entryDy=0;"
        elif rows[tgt] > rows[src_n]:          # going down: leave from the bottom, enter from the top
            style += "exitX=0.5;exitY=1;exitDx=0;exitDy=0;entryX=0.5;entryY=0;entryDx=0;entryDy=0;"
        g.edge(src, ids[tgt], label, style)

    for i, s in enumerate(steps):
        if s.get("kind") == "decision":
            for b in s["branches"]:
                connect(s["n"], b.get("to"), b.get("label", ""))
            continue
        targets = s.get("next")
        if s.get("end") or targets == "end":
            connect(s["n"], "end")
            continue
        if targets is None:
            nxt = [n for n in order[i + 1:] if n not in alt]
            targets = nxt[:1]
        for t in ([targets] if isinstance(targets, int) else targets):
            connect(s["n"], t)
    return g.xml(width=x0 * 2 + total_w, height=top + height + 40)


def _is_main_successor(t: int, steps: list[dict]) -> bool:
    """True when step t is reached by plain sequence (the step before it in the list flows into it)."""
    idx = next(i for i, s in enumerate(steps) if s["n"] == t)
    if idx == 0:
        return True
    prev = steps[idx - 1]
    if prev.get("kind") == "decision":
        return prev["branches"][0].get("to") == t
    nxt = prev.get("next")
    if prev.get("end") or nxt == "end":
        return False
    return nxt is None or nxt == t or (isinstance(nxt, list) and t in nxt)


# --------------------------------------------------------------------------------------------- use case diagram
def use_case(title: str, actors_left: list[str], actors_right: list[str], cases: list[tuple[str, str]],
             links: list[tuple[str, str]]) -> str:
    """UML use case diagram. cases: (key, label); links: (actor, case key)."""
    g = Graph()
    cols = 1  # one column: lines from the actors never cross another use case
    per_col = math.ceil(len(cases) / cols)
    uc_w, uc_h, gap = 300, 50, 16
    box_x, box_y = 300, 40
    box_w = cols * (uc_w + 40) + 40
    box_h = per_col * (uc_h + gap) + 60
    g.vertex(f"<b>{html.escape(title)}</b>", "rounded=0;whiteSpace=wrap;html=1;verticalAlign=top;fillColor=none;",
             box_x, box_y, box_w, box_h)
    uc_ids = {}
    for i, (key, label) in enumerate(cases):
        c, r = divmod(i, per_col)
        uc_ids[key] = g.vertex(html.escape(label), "ellipse;whiteSpace=wrap;html=1;fontSize=11;",
                               box_x + 40 + c * (uc_w + 40), box_y + 45 + r * (uc_h + gap), uc_w, uc_h)
    act_ids = {}
    for side, names in (("L", actors_left), ("R", actors_right)):
        if not names:
            continue
        span = box_h / len(names)
        for i, name in enumerate(names):
            x = 40 if side == "L" else box_x + box_w + 160
            y = box_y + span * i + span / 2 - 40
            act_ids[name] = g.vertex(html.escape(name), "shape=umlActor;verticalLabelPosition=bottom;verticalAlign=top;"
                                     "html=1;outlineConnect=0;fontSize=11;", x, y, 34, 64)
    left = set(actors_left)
    for actor, key in links:
        side = "entryX=0;entryY=0.5;" if actor in left else "entryX=1;entryY=0.5;"
        g.edge(act_ids[actor], uc_ids[key], style="endArrow=none;html=1;rounded=0;strokeColor=#555555;"
               "entryDx=0;entryDy=0;" + side)
    return g.xml(width=box_x + box_w + 260, height=box_y + box_h + 80)


# --------------------------------------------------------------------------------------------- state transition
def states(nodes: list[dict], transitions: list[dict], initial: str, finals: list[str]) -> str:
    """nodes: {key, label, col, row}; transitions: {from, to, label}."""
    g = Graph()
    cw, rh, w, h, x0, y0 = 250, 150, 170, 56, 80, 110
    ids = {}
    for nd in nodes:
        ids[nd["key"]] = g.vertex(f"<b>{html.escape(nd['label'])}</b>",
                                  "rounded=1;whiteSpace=wrap;html=1;arcSize=40;fillColor=#F5F5F5;",
                                  x0 + nd["col"] * cw, y0 + nd["row"] * rh, w, h)
    first = next(n for n in nodes if n["key"] == initial)
    s = g.vertex("", START, x0 + first["col"] * cw + w / 2 - 15, y0 + first["row"] * rh - 70, 30, 30)
    g.edge(s, ids[initial])
    for t in transitions:
        g.edge(ids[t["from"]], ids[t["to"]], esc(t["label"]),
               EDGE + "fontSize=11;labelBackgroundColor=#FFFFFF;")
    for f in finals:
        nd = next(n for n in nodes if n["key"] == f)
        e = g.vertex("", END, x0 + nd["col"] * cw + w / 2 - 15, y0 + nd["row"] * rh + h + 45, 30, 30)
        g.edge(ids[f], e)
    maxc = max(n["col"] for n in nodes) + 1
    maxr = max(n["row"] for n in nodes) + 1
    return g.xml(width=x0 * 2 + maxc * cw, height=y0 + maxr * rh + 120)


# --------------------------------------------------------------------------------------------- tree (site map)
def tree(root: dict) -> str:
    """root: {label, children: [...]} laid out top-down, leaves spaced evenly."""
    g = Graph()
    w, h, xgap, ygap = 150, 44, 20, 80
    leaves: list[dict] = []

    def collect(node, depth):
        node["_d"] = depth
        kids = node.get("children") or []
        if not kids:
            leaves.append(node)
        for k in kids:
            collect(k, depth + 1)

    collect(root, 0)
    for i, leaf in enumerate(leaves):
        leaf["_x"] = 40 + i * (w + xgap)

    def place(node):
        kids = node.get("children") or []
        if kids:
            for k in kids:
                place(k)
            node["_x"] = (kids[0]["_x"] + kids[-1]["_x"]) / 2

    place(root)

    def draw(node, parent_id=None):
        style = ("rounded=1;whiteSpace=wrap;html=1;fontSize=11;"
                 + ("fillColor=#1F3A5F;fontColor=#FFFFFF;" if node["_d"] == 0 else
                    "fillColor=#DCE6F1;" if node["_d"] == 1 else "fillColor=#FFFFFF;"))
        cid = g.vertex(html.escape(node["label"]), style, node["_x"], 40 + node["_d"] * (h + ygap), w, h)
        if parent_id:
            g.edge(parent_id, cid, style="edgeStyle=elbowEdgeStyle;elbow=vertical;html=1;endArrow=none;rounded=0;")
        for k in node.get("children") or []:
            draw(k, cid)

    draw(root)
    depth = max(n["_d"] for n in leaves)
    return g.xml(width=80 + len(leaves) * (w + xgap), height=120 + (depth + 1) * (h + ygap))


# --------------------------------------------------------------------------------------------- object relationship
def objects(nodes: list[dict], links: list[dict]) -> str:
    """nodes: {key, label, kind: actor|object|external, col, row}; links: {from, to, label}."""
    g = Graph()
    cw, rh, w, h = 230, 120, 170, 50
    styles = {
        "object": "rounded=0;whiteSpace=wrap;html=1;fillColor=#DCE6F1;strokeColor=#1F3A5F;fontStyle=1;",
        "external": "shape=component;align=left;spacingLeft=36;whiteSpace=wrap;html=1;fillColor=#FFF2CC;",
    }
    ids = {}
    for nd in nodes:
        x, y = 40 + nd["col"] * cw, 40 + nd["row"] * rh
        if nd["kind"] == "actor":
            ids[nd["key"]] = g.vertex(html.escape(nd["label"]), "shape=umlActor;verticalLabelPosition=bottom;"
                                      "verticalAlign=top;html=1;outlineConnect=0;fontSize=11;", x + w / 2 - 15, y - 5, 30, 56)
        else:
            ids[nd["key"]] = g.vertex(html.escape(nd["label"]), styles[nd["kind"]], x, y, w, h)
    for ln in links:
        g.edge(ids[ln["from"]], ids[ln["to"]], esc(ln.get("label", "")),
               "endArrow=open;html=1;rounded=0;fontSize=10;labelBackgroundColor=#FFFFFF;strokeColor=#555555;")
    maxc = max(n["col"] for n in nodes) + 1
    maxr = max(n["row"] for n in nodes) + 1
    return g.xml(width=80 + maxc * cw, height=80 + maxr * rh)


# --------------------------------------------------------------------------------------------- files
def write_file(path: str, pages: list[tuple[str, str]]) -> None:
    """pages: (name, mxGraphModel xml). Written uncompressed so diffs stay readable."""
    body = "".join(f'<diagram id="p{i}" name={quoteattr(name)}>{xml}</diagram>' for i, (name, xml) in enumerate(pages))
    with open(path, "w", encoding="utf-8", newline="\n") as fh:
        fh.write(f'<mxfile host="shp-srs" type="device" pages="{len(pages)}">{body}</mxfile>\n')


def drawio_cli() -> str | None:
    for cand in (os.environ.get("DRAWIO_CLI"), r"C:\Program Files\draw.io\draw.io.exe", "/opt/drawio/drawio",
                 shutil.which("drawio"), shutil.which("draw.io")):
        if cand and os.path.exists(cand):
            return cand
    return None


def export_png(drawio_path: str, page_index: int, out_png: str, scale: float = 1.6) -> bool:
    cli = drawio_cli()
    if not cli:
        return False
    cmd = [cli, "-x", "-f", "png", "--scale", str(scale), "-b", "10", "-p", str(page_index + 1), "-o", out_png, drawio_path]
    for _attempt in range(2):
        proc = subprocess.Popen(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        try:
            proc.wait(timeout=90)
            if proc.returncode == 0:
                return os.path.exists(out_png)
        except subprocess.TimeoutExpired:
            pass
        # draw.io is an Electron app: kill the whole process tree, otherwise hung renderers slow down later exports
        if os.name == "nt":
            subprocess.run(["taskkill", "/F", "/T", "/PID", str(proc.pid)], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        else:
            proc.kill()
    return os.path.exists(out_png)
