"""Báo cáo token và chi phí AI theo tác vụ, model, ngày (AI-33; NFR-C2). Đọc collection `usage` (không có nội dung).

python -m shp_ai.report_usage --from 2026-11-01 --to 2026-11-30 --out reports/ai-usage.md
"""

import argparse
from datetime import date
from pathlib import Path
from typing import Any

from shp_ai.usage import default_range, summary


def render(db: Any, start: date, end: date, daily_limit: int) -> str:
    out = [
        f"# Chi phí AI {start.isoformat()} → {end.isoformat()}",
        "",
        f"Hạn mức: {daily_limit:,} token/ngày (cảnh báo ở 80%).",
        "",
    ]
    for group, title in (("task", "Theo tác vụ"), ("model", "Theo model"), ("day", "Theo ngày")):
        rows = summary(db, start, end, group)
        out += [
            f"## {title}",
            "",
            "| Khóa | Lượt | Token vào | Token ra | Chi phí (USD) | Tỉ lệ fallback |",
            "|---|---|---|---|---|---|",
        ]
        out += [
            f"| {r['key']} | {r['requests']} | {r['inputTokens']} | {r['outputTokens']} | {r['costUsd']} | "
            f"{r['fallbackRate']} |"
            for r in rows
        ]
        if group == "day":
            over = [r["key"] for r in rows if (r["inputTokens"] + r["outputTokens"]) >= 0.8 * daily_limit]
            out += ["", f"Ngày chạm 80% hạn mức: {', '.join(over) if over else 'không có'}."]
        out.append("")
    return "\n".join(out)


def main() -> None:  # pragma: no cover - cần MongoDB
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
