"""Kiểm tra dữ liệu trước khi huấn luyện (ML-14, 16-mlops §2). Sai bất kỳ điều kiện nào thì dừng pipeline, không train.

Thông điệp lỗi chỉ nêu tên cột và tỉ lệ, không in giá trị từng dòng.
"""

import pandas as pd

REQUIRED = {
    "appointment_id": "object",
    "patient_id": "object",
    "specialty": "object",
    "channel": "object",
    "age_band": "object",
    "booked_at": "datetime",
    "start_at": "datetime",
    "outcome": "object",
}
CHANNELS = {"web", "chatbot", "staff"}
OUTCOMES = {"fulfilled", "noshow", "cancelled"}
MAX_MISSING = 0.02
RATE_RANGE = (0.10, 0.30)


class DataCheckError(ValueError):
    def __init__(self, problems: list[str]) -> None:
        self.problems = problems
        super().__init__("; ".join(problems))


def check(df: pd.DataFrame) -> None:
    problems: list[str] = []
    for col, kind in REQUIRED.items():
        if col not in df.columns:
            problems.append(f"thiếu cột {col}")
            continue
        if kind == "datetime" and not isinstance(df[col].dtype, pd.DatetimeTZDtype):
            problems.append(f"{col} phải là thời gian có múi giờ")
        missing = float(df[col].isna().mean())
        if missing > MAX_MISSING:
            problems.append(f"{col} thiếu {missing:.1%} (> {MAX_MISSING:.0%})")
    if problems:
        raise DataCheckError(problems)
    if df["appointment_id"].duplicated().any():
        problems.append("appointment_id bị trùng")
    if not set(df["channel"].dropna()) <= CHANNELS:
        problems.append("channel có giá trị ngoài danh mục")
    if not set(df["outcome"].dropna()) <= OUTCOMES:
        problems.append("outcome có giá trị ngoài danh mục (pending không được vào tập huấn luyện)")
    bad_age = ~df["age_band"].astype(str).str.fullmatch(r"\d{1,3}-\d{1,3}|unknown")
    if bad_age.any():
        problems.append(f"age_band sai định dạng ở {float(bad_age.mean()):.2%} dòng")
    lead = (df["start_at"] - df["booked_at"]).dt.total_seconds()
    if (lead <= 0).any():
        problems.append("booked_at phải trước start_at (rò rỉ thời gian)")
    if (lead > 31 * 86400).any():
        problems.append("lead time vượt cửa sổ đặt lịch 30 ngày")
    attended = df[df["outcome"] != "cancelled"]
    rate = float((attended["outcome"] == "noshow").mean()) if len(attended) else 0.0
    if not RATE_RANGE[0] <= rate <= RATE_RANGE[1]:
        problems.append(f"tỉ lệ no-show {rate:.1%} ngoài khoảng [{RATE_RANGE[0]:.0%}, {RATE_RANGE[1]:.0%}]")
    if problems:
        raise DataCheckError(problems)
