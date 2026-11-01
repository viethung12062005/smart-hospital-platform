"""Model card tự sinh khi đăng ký (ML-18, 16-mlops §3)."""

from typing import Any


def _fmt(v: Any) -> str:
    return "—" if v is None else (f"{v:.4f}" if isinstance(v, float) else str(v))


def model_card(meta: dict[str, Any]) -> str:
    m = meta["metrics"]
    groups = "\n".join(
        f"| {col} | {g} | {_fmt(v)} |" for col, gs in m.get("subgroupAuc", {}).items() for g, v in gs.items()
    )
    checks = "\n".join(
        f"| {c['name']} | {_fmt(c['value'])} | {_fmt(c['limit'])} | {'đạt' if c['passed'] else 'KHÔNG ĐẠT'} |"
        for c in meta["gate"]["checks"]
    )
    return f"""# Model card: shp-no-show

> **Cảnh báo:** huấn luyện trên **dữ liệu giả** (services/ml/src/shp_ml/synth.py). Kết quả chứng minh quy trình MLOps,
> không chứng minh khả năng dự đoán trên bệnh nhân thật.

## Mục đích và giới hạn sử dụng
- Ước lượng khả năng bệnh nhân vắng mặt (no-show) cho một lịch hẹn, để gửi thêm nhắc lịch (US-046).
- **Không** dùng để từ chối, hạ ưu tiên hay tính phí bệnh nhân; không dùng cho quyết định lâm sàng.
- Đặc trưng không có PII: chỉ thời gian, chuyên khoa, kênh, nhóm tuổi 10 năm, thống kê đi khám trước đó.

## Dữ liệu
| Mục | Giá trị |
|---|---|
| Hash dataset (sha256) | `{meta["datasetSha256"]}` |
| Train | {m["train"]["n"]} lịch hẹn, {m["train"]["from"]} → {m["train"]["to"]} |
| Validation (theo thời gian) | {m["validation"]["n"]} lịch hẹn, {m["validation"]["from"]} → {m["validation"]["to"]} |
| Tỉ lệ no-show (validation) | {_fmt(m["positiveRate"])} |

## Kết quả
| Chỉ số | Giá trị |
|---|---|
| AUC | {_fmt(m["auc"])} |
| AUC baseline (Logistic Regression) | {_fmt(m["baselineAuc"])} |
| AUC model production lúc đăng ký | {_fmt(m.get("productionAuc"))} |
| ECE (calibration) | {_fmt(m["ece"])} |
| p95 dự đoán một lịch hẹn (ms) | {_fmt(m["latencyP95Ms"])} |
| Ngưỡng nguy cơ cao | {_fmt(m["threshold"])} (recall lớn nhất với precision ≥ 0,5 trên tập tinh chỉnh) |
| Precision / recall tại ngưỡng | {_fmt(m["precisionAtThreshold"])} / {_fmt(m["recallAtThreshold"])} |

### AUC theo nhóm con (nhóm ≥ 300 mẫu)
| Nhóm | Giá trị | AUC |
|---|---|---|
{groups}

## Cổng chất lượng
| Kiểm tra | Giá trị | Giới hạn | Kết quả |
|---|---|---|---|
{checks}

## Nguồn gốc
- Tham số: `{meta["params"]}`, seed {meta["seed"]}
- Run: `{meta["runId"]}` · kích hoạt bởi: {meta.get("trigger", "manual")} · đăng ký lúc {meta["registeredAt"]}
- Quy trình: services/ml/src/shp_ml/pipeline.py (16-mlops §2-3)
"""
