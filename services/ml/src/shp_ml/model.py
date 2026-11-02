"""Model no-show dùng khi phục vụ: LightGBM Booster lưu dạng văn bản (không pickle, nạp không chạy mã lạ).

Danh mục giá trị của đặc trưng phân loại nằm trong chính văn bản model (pandas_categorical), nên dự đoán tại thời điểm
phục vụ dùng đúng mã hóa lúc huấn luyện; giá trị lạ thành thiếu (NaN) thay vì lệch mã.
"""

import hashlib
from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from typing import Any

import lightgbm as lgb
import numpy as np
import pandas as pd

from shp_ml.features import CATEGORICAL, FEATURES, NUMERIC

MODEL_NAME = "shp-no-show"


def to_frame(rows: Sequence[Mapping[str, Any]]) -> pd.DataFrame:
    df = pd.DataFrame([{f: r.get(f) for f in FEATURES} for r in rows], columns=FEATURES)
    for c in NUMERIC:
        df[c] = pd.to_numeric(df[c], errors="coerce").astype(float)
    for c in CATEGORICAL:
        df[c] = df[c].astype(str).astype("category")
    return df


@dataclass(frozen=True)
class NoShowModel:
    version: str
    threshold: float
    booster: lgb.Booster
    name: str = MODEL_NAME

    def predict_frame(self, frame: pd.DataFrame) -> np.ndarray:
        scores: np.ndarray = np.asarray(self.booster.predict(frame[FEATURES]), dtype=float)
        return scores

    def score(self, features: Mapping[str, Any]) -> float:
        return float(self.predict_frame(to_frame([features]))[0])

    def to_text(self) -> str:
        return str(self.booster.model_to_string())

    @staticmethod
    def from_text(text: str, version: str, threshold: float, sha256: str | None = None) -> "NoShowModel":
        if sha256 is not None and hashlib.sha256(text.encode()).hexdigest() != sha256:
            raise ValueError("model không khớp hash đã đăng ký")
        return NoShowModel(version=version, threshold=threshold, booster=lgb.Booster(model_str=text))
