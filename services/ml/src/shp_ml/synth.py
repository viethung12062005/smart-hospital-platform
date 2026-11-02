"""Bộ sinh dữ liệu lịch hẹn giả có quy luật no-show (ML-01, ML-21, 16-mlops §1). Không có dữ liệu thật.

Quy luật (hệ số ghi ở COEF, mô tả trong services/ml/README.md):
  logit(p) = β0 + f(lead_time) + β2·logit_rate(prior) + β3·is_first_visit + g(giờ khám, tuổi) + β5·channel
             + h(kênh, tuổi)
             + β6·specialty + mùa(tháng) + u_bệnh_nhân
u_bệnh_nhân là thiên hướng ẩn (không có trong đặc trưng) để AUC dừng ở khoảng 0,78-0,82.
β0 được hiệu chỉnh để tỉ lệ no-show ≈ 18%.

  python -m shp_ml.synth --out data/noshow.parquet [--rows 80000 --seed 42 --shifted]
"""

import argparse
import hashlib
import json
import math
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
from pathlib import Path

import numpy as np
import pandas as pd

from shp_ml.features import VN

SPECIALTIES = [
    "internal-medicine",
    "cardiology",
    "pediatrics",
    "obstetrics-gynecology",
    "dermatology",
    "ent",
    "ophthalmology",
    "orthopedics",
    "neurology",
]
SPECIALTY_WEIGHTS = [0.24, 0.12, 0.14, 0.1, 0.1, 0.1, 0.08, 0.07, 0.05]
CHANNELS = ["web", "staff", "chatbot"]
AGE_BANDS = ["0-9", "10-19", "20-29", "30-39", "40-49", "50-59", "60-69", "70-79", "80-120"]
AGE_WEIGHTS = [0.1, 0.08, 0.15, 0.17, 0.15, 0.13, 0.11, 0.07, 0.04]


@dataclass(frozen=True)
class Coef:
    lead_short: float = -1.3  # đặt trong vòng 1 ngày: ít vắng
    lead_long: float = 1.4  # đặt trước > 14 ngày: dễ quên (ngưỡng, không tuyến tính)
    lead_per_week: float = 0.1
    prior_rate: float = 1.1  # nhân với logit của tỉ lệ vắng làm mượt
    first_visit: float = 0.35
    early_morning: float = 0.15  # khám trước 8 giờ, mọi tuổi
    early_morning_elderly: float = 1.6  # cộng thêm với người từ 60 tuổi (tương tác)
    chatbot_young: float = 1.4  # kênh chatbot với người 10-29 tuổi (tương tác)
    channel: tuple[float, float, float] = (0.0, -0.45, 0.3)  # web, staff, chatbot
    specialty_dermatology: float = 0.35
    specialty_pediatrics: float = -0.25
    season_amplitude: float = 0.2  # ≈ ±3 điểm phần trăm theo tháng
    patient_sd: float = 0.9


@dataclass(frozen=True)
class Shift:
    """Bộ dữ liệu dịch chuyển (ML-21): lead time +40%, tỉ lệ vắng +6 điểm, nhiều chatbot, nhiều ca sáng sớm."""

    lead_factor: float = 1.0
    extra_rate_points: float = 0.0
    channel_weights: tuple[float, float, float] = (0.55, 0.35, 0.10)
    early_share: float = 0.1  # tỉ lệ lịch 07:00-07:45 (4 trong 40 khung)


SHIFTED = Shift(lead_factor=1.4, extra_rate_points=0.06, channel_weights=(0.4, 0.25, 0.35), early_share=0.35)
COEF = Coef()
TARGET_RATE = 0.18
CANCEL_RATE = 0.08


def _sigmoid(x: np.ndarray) -> np.ndarray:
    out: np.ndarray = 1.0 / (1.0 + np.exp(-x))
    return out


def generate(
    rows: int = 80_000,
    patients: int = 50_000,
    months: int = 24,
    seed: int = 42,
    shift: Shift | None = None,
    start: datetime = datetime(2024, 11, 1, tzinfo=UTC),
) -> pd.DataFrame:
    shift = shift or Shift()
    rng = np.random.default_rng(seed)
    n_p = min(patients, rows)
    pid_pool = np.array([f"{i:08x}-0000-4000-8000-{rng.integers(0, 2**48):012x}" for i in range(n_p)])
    latent = rng.normal(0.0, COEF.patient_sd, n_p)
    activity = rng.gamma(0.6, 1.0, n_p)
    patient_idx = rng.choice(n_p, size=rows, p=activity / activity.sum())
    age_band = rng.choice(AGE_BANDS, size=n_p, p=AGE_WEIGHTS)[patient_idx]

    span_days = int(months * 30.4)
    day = rng.integers(0, span_days, rows)
    # 07:00 → 16:45 giờ VN, mỗi 15 phút; `early_share` lịch rơi vào 4 khung đầu (07:00-07:45)
    early_pick = rng.random(rows) < shift.early_share
    slot = np.where(early_pick, rng.integers(0, 4, rows), rng.integers(4, 40, rows))
    local_start = [
        (
            start.astimezone(VN).replace(hour=7, minute=0, second=0, microsecond=0)
            + timedelta(days=int(d), minutes=15 * int(s))
        )
        for d, s in zip(day, slot, strict=True)
    ]
    # Chủ nhật dời sang thứ hai
    local_start = [t + timedelta(days=1) if t.isoweekday() == 7 else t for t in local_start]
    start_at = pd.to_datetime([t.astimezone(UTC) for t in local_start], utc=True)
    lead_days = np.minimum(rng.exponential(6.0 * shift.lead_factor, rows) + rng.uniform(0.05, 1, rows), 30.0)
    booked_at = (start_at - pd.to_timedelta(lead_days, unit="D")).floor("s")
    channel = rng.choice(CHANNELS, size=rows, p=shift.channel_weights)
    specialty = rng.choice(SPECIALTIES, size=rows, p=SPECIALTY_WEIGHTS)

    order = np.argsort(booked_at.tz_convert(None).to_numpy(), kind="stable")
    base = np.zeros(rows)
    early = np.array([t.hour < 8 for t in local_start])
    month = np.array([t.month for t in local_start])
    elderly = np.isin(age_band, ["60-69", "70-79", "80-120"])
    young = np.isin(age_band, ["10-19", "20-29"])
    base += COEF.lead_per_week * lead_days / 7
    base += COEF.lead_short * (lead_days < 1) + COEF.lead_long * (lead_days > 14)
    base += COEF.early_morning * early + COEF.early_morning_elderly * (early & elderly)
    base += COEF.chatbot_young * ((channel == "chatbot") & young)
    base += np.select([channel == c for c in CHANNELS], COEF.channel)
    base += COEF.specialty_dermatology * (specialty == "dermatology")
    base += COEF.specialty_pediatrics * (specialty == "pediatrics")
    base += COEF.season_amplitude * np.cos(2 * math.pi * (month - 1) / 12)  # cao vào tháng 1 (Tết)
    base += latent[patient_idx]
    cancelled = rng.random(rows) < CANCEL_RATE * (0.6 + lead_days / 10)
    u = rng.random(rows)

    def simulate(b0: float) -> np.ndarray:
        booked = np.zeros(n_p, dtype=np.int64)
        noshow = np.zeros(n_p, dtype=np.int64)
        # thời điểm biết kết quả (start + 1h) để tính prior đúng thời điểm
        known_at = (start_at + pd.Timedelta(hours=1)).tz_convert(None).to_numpy()
        pending: dict[int, list[int]] = {}
        out = np.zeros(rows, dtype=np.int8)
        bk = booked_at.tz_convert(None).to_numpy()
        for i in order:
            p = int(patient_idx[i])
            q = pending.get(p)
            if q:
                rest = []
                for j in q:
                    if known_at[j] <= bk[i]:
                        booked[p] += 1
                        noshow[p] += out[j]
                    else:
                        rest.append(j)
                pending[p] = rest
            rate = (noshow[p] + 1) / (booked[p] + 5)
            logit = b0 + base[i] + COEF.prior_rate * math.log(rate / (1 - rate)) + COEF.first_visit * (booked[p] == 0)
            prob = 1 / (1 + math.exp(-logit)) + shift.extra_rate_points
            out[i] = 1 if u[i] < prob else 0
            if not cancelled[i]:
                pending.setdefault(p, []).append(int(i))
        return out

    lo, hi = -8.0, 2.0
    for _ in range(14):  # hiệu chỉnh β0 cho tỉ lệ ≈ 18% (trên lịch không hủy, trước dịch chuyển)
        mid = (lo + hi) / 2
        rate = simulate(mid)[~cancelled].mean() - shift.extra_rate_points
        lo, hi = (mid, hi) if rate < TARGET_RATE else (lo, mid)
    label = simulate((lo + hi) / 2)

    outcome = np.where(cancelled, "cancelled", np.where(label == 1, "noshow", "fulfilled"))
    cancel_frac = rng.uniform(0.0, 1.0, rows)
    cancelled_at = (booked_at + pd.to_timedelta(lead_days * cancel_frac, unit="D")).floor("s")
    return pd.DataFrame(
        {
            "appointment_id": [f"{i:08x}-1111-4000-8000-{seed:012x}" for i in range(rows)],
            "patient_id": pid_pool[patient_idx],
            "practitioner_id": [f"0000{int(k):04x}-2222-4000-8000-000000000000" for k in rng.integers(0, 60, rows)],
            "specialty": specialty,
            "channel": channel,
            "age_band": age_band,
            "booked_at": booked_at,
            "start_at": start_at,
            "outcome": outcome,
            "cancelled_at": pd.Series(cancelled_at).mask(~cancelled),
        }
    ).sort_values("booked_at", kind="stable", ignore_index=True)


def describe(df: pd.DataFrame, path: Path, seed: int, shifted: bool) -> dict[str, object]:
    digest = hashlib.sha256(path.read_bytes()).hexdigest()
    attended = df[df["outcome"] != "cancelled"]
    return {
        "file": path.name,
        "sha256": digest,
        "seed": seed,
        "shifted": shifted,
        "rows": len(df),
        "patients": int(df["patient_id"].nunique()),
        "from": df["start_at"].min().isoformat(),
        "to": df["start_at"].max().isoformat(),
        "noshowRate": round(float((attended["outcome"] == "noshow").mean()), 4),
        "cancelledRate": round(float((df["outcome"] == "cancelled").mean()), 4),
        "columns": {c: str(t) for c, t in df.dtypes.items()},
        "warning": (
            "Dữ liệu giả do bộ sinh tạo; mô hình chỉ học lại quy luật này, không chứng minh khả năng dự đoán thực tế."
        ),
    }


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--out", type=Path, required=True)
    ap.add_argument("--rows", type=int, default=80_000)
    ap.add_argument("--patients", type=int, default=50_000)
    ap.add_argument("--seed", type=int, default=42)
    ap.add_argument("--shifted", action="store_true", help="bộ dữ liệu dịch chuyển cho test drift (ML-21)")
    a = ap.parse_args()
    df = generate(a.rows, a.patients, seed=a.seed, shift=SHIFTED if a.shifted else None)
    a.out.parent.mkdir(parents=True, exist_ok=True)
    df.to_parquet(a.out, index=False)
    meta = describe(df, a.out, a.seed, a.shifted)
    a.out.with_suffix(".json").write_text(json.dumps(meta, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({k: meta[k] for k in ("rows", "noshowRate", "sha256")}))


if __name__ == "__main__":
    main()
