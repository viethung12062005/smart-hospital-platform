# ml

Mô hình no-show: phục vụ dự đoán, drift, huấn luyện lại tự động. Chủ sở hữu: **SA** (từ 2026-11-01).

| Lệnh | Việc |
|---|---|
| `python -m venv .venv && . .venv/bin/activate && pip install -e ".[dev]"` | cài môi trường |
| `ruff check . && ruff format --check . && mypy . && pytest --cov` | đúng các bước CI chạy |
| `uvicorn shp_ml.main:app --reload --port 8090` | chạy local |
| `make -C ../../dev deploy SVC=ml` | build image và deploy lên cụm kind |

## Dữ liệu huấn luyện giả và baseline (ML-01, ML-02, ML-14, ML-15, ML-21, ML-03)

Hệ thống mới chưa có lịch sử vận hành, nên mô hình đầu tiên học từ **dữ liệu giả** do `shp_ml.synth` sinh theo
[16-mlops §1](../../docs/03-architecture/detailed-design/16-mlops.md). Khi chạy thật, consumer (ML-06) ghi
`appointment_facts` từ event của scheduling; pipeline huấn luyện lại (ML-22) dùng chính dữ liệu đó, cùng gói đặc trưng.

| Lệnh | Việc |
|---|---|
| `python -m shp_ml.synth --out data/noshow.parquet` | 80.000 lịch hẹn, 24 tháng, ~50.000 bệnh nhân giả, seed 42; kèm `noshow.json` (hash, tỉ lệ nhãn) |
| `python -m shp_ml.synth --out data/shifted.parquet --shifted` | bộ dịch chuyển cho test drift: lead time +40%, vắng +6 điểm, nhiều chatbot |
| `python -m shp_ml.train --data data/noshow.parquet --out runs/baseline` | kiểm dữ liệu, dựng đặc trưng, chia theo thời gian (6 tháng cuối), baseline Logistic Regression |

Quy luật sinh (hệ số trong `synth.Coef`): logit = β0 + 0,45·lead_time/7 + 1,1·logit(tỉ lệ vắng làm mượt)
+ 0,35·lần đầu + 0,4·khám trước 8 giờ + kênh (staff −0,45, chatbot +0,3) + chuyên khoa (da liễu +0,35, nhi −0,25)
+ mùa (cao tháng 1) + thiên hướng ẩn của bệnh nhân (độ lệch 0,9, không có trong đặc trưng). β0 được hiệu chỉnh cho tỉ lệ ≈ 18%.

Kết quả seed 42 (máy SA, 2026-11-01): no-show 17,99%; baseline LR AUC validation **0,774**.
**Giới hạn:** mô hình chỉ học lại quy luật nhóm tự viết; con số chứng minh quy trình MLOps, không chứng minh khả năng dự đoán thực tế.

`shp_platform` trong `src/` là bản chép của `libs/python`: không sửa ở đây, sửa ở nguồn rồi chạy `python tools/pylib/sync.py`.
