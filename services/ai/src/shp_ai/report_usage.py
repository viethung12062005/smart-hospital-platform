"""Token and cost report for AI, by task, model and day (AI-33; NFR-C2). Reads the `usage` collection (no content).

python -m shp_ai.report_usage --from 2026-11-01 --to 2026-11-30 --out reports/ai-usage.md
"""

import argparse
from datetime import date
from pathlib import Path
from typing import Any

from shp_ai.usage import default_range, summary

# NFR-C2: a RAG request may cost at most 0.005 USD on average (DeepSeek; Gemini only within the free tier).
NFR_C2_USD_PER_RAG_REQUEST = 0.005
RAG_TASK = "rag-answer"


def render(db: Any, start: date, end: date, daily_limit: int) -> str:
    out = [
        f"# AI cost {start.isoformat()} → {end.isoformat()}",
        "",
        f"Budget: {daily_limit:,} tokens/day (warning at 80%).",
        "",
    ]
    for group, title in (("task", "By task"), ("model", "By model"), ("day", "By day")):
        rows = summary(db, start, end, group)
        out += [
            f"## {title}",
            "",
            "| Key | Calls | Input tokens | Output tokens | Cost (USD) | Fallback rate |",
            "|---|---|---|---|---|---|",
        ]
        out += [
            f"| {r['key']} | {r['requests']} | {r['inputTokens']} | {r['outputTokens']} | {r['costUsd']} | "
            f"{r['fallbackRate']} |"
            for r in rows
        ]
        if group == "day":
            over = [r["key"] for r in rows if (r["inputTokens"] + r["outputTokens"]) >= 0.8 * daily_limit]
            out += ["", f"Days hitting the 80% budget: {', '.join(over) if over else 'none'}."]
        if group == "task":
            out += ["", *nfr_c2_lines(rows)]
        out.append("")
    return "\n".join(out)


def nfr_c2_lines(rows: list[dict[str, Any]]) -> list[str]:
    """Compare the average cost of a RAG request with NFR-C2 (0.005 USD); other tasks are reported, not judged."""
    rag = next((r for r in rows if r["key"] == RAG_TASK), None)
    if rag is None or not rag["requests"]:
        return [f"NFR-C2: no {RAG_TASK} call in the period — cannot judge the cost target."]
    average = rag["costUsd"] / rag["requests"]
    verdict = "PASS" if average <= NFR_C2_USD_PER_RAG_REQUEST else "FAIL"
    above = [r["key"] for r in rows if r["requests"] and r["costUsd"] / r["requests"] > NFR_C2_USD_PER_RAG_REQUEST]
    detail = f"; over the limit: {', '.join(above)}" if above else ""
    return [
        f"NFR-C2: {RAG_TASK} average {average:.4f} USD/request "
        f"(limit {NFR_C2_USD_PER_RAG_REQUEST}) — {verdict}{detail}."
    ]


def main() -> None:  # pragma: no cover - needs MongoDB
    from pymongo import MongoClient

    from shp_ai.settings import Settings

    s = Settings()
    start, end = default_range()
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--from", dest="start", type=date.fromisoformat, default=start)
    ap.add_argument("--to", dest="end", type=date.fromisoformat, default=end)
    ap.add_argument("--out", type=Path, required=True)
    a = ap.parse_args()
    db: Any = MongoClient(s.mongodb_uri, tz_aware=True)[s.mongodb_db]
    a.out.parent.mkdir(parents=True, exist_ok=True)
    a.out.write_text(render(db, a.start, a.end, s.daily_token_limit), encoding="utf-8")


if __name__ == "__main__":  # pragma: no cover
    main()
