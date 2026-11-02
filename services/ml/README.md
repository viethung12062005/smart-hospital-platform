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
| `python -m shp_ml.train --data data/noshow.parquet --out runs/baseline` | chỉ baseline Logistic Regression (so sánh nhanh) |

Quy luật sinh (hệ số trong `synth.Coef`): logit = β0 + 0,1·lead_time/7 − 1,3·(đặt trong 1 ngày) + 1,4·(đặt trước > 14 ngày)
+ 1,1·logit(tỉ lệ vắng làm mượt) + 0,35·lần đầu + 0,15·khám trước 8 giờ + 1,6·(trước 8 giờ và từ 60 tuổi)
+ kênh (staff −0,45, chatbot +0,3) + 1,4·(chatbot và 10–29 tuổi) + chuyên khoa (da liễu +0,35, nhi −0,25) + mùa (cao tháng 1)
+ thiên hướng ẩn của bệnh nhân (độ lệch 0,9, không có trong đặc trưng). β0 hiệu chỉnh cho tỉ lệ ≈ 18%.
Các hiệu ứng có ngưỡng và tương tác là chủ ý: hành vi thật không tuyến tính, và nếu dữ liệu chỉ tuyến tính thì LR luôn thắng,
khiến cổng "hơn baseline" vô nghĩa.

Kết quả seed 42 (máy SA, 2026-11-01): no-show 18,0%; baseline LR AUC **0,769**; LightGBM AUC **0,784**, ECE 0,019,
p95 dự đoán 6,7 ms, mọi nhóm con đạt; ngưỡng nguy cơ cao tự chọn 0,31.

### Huấn luyện, cổng chất lượng, đăng ký (ML-03..05, ML-13, ML-17, ML-18)

| Lệnh | Việc |
|---|---|
| `python -m shp_ml.pipeline --data data/noshow.parquet --registry runs/registry --promote-first` | toàn bộ pipeline với registry thư mục; `--promote-first` chỉ dùng lần khởi tạo |
| `pip install -e ".[train,mlflow]"` rồi `python -m shp_ml.pipeline --data ... --mlflow http://mlflow:5000` | cùng pipeline, ghi MLflow (cụm) |

Model lưu dạng văn bản LightGBM (không pickle) kèm sha256, kiểm lại khi nạp. Job huấn luyện chỉ đặt alias `candidate`;
`production` chỉ đổi qua shadow/canary (ML-24) hoặc người có quyền. Không đạt cổng: không đăng ký, run vẫn được ghi kèm lý do.

**Giới hạn:** mô hình chỉ học lại quy luật nhóm tự viết; con số chứng minh quy trình MLOps, không chứng minh khả năng dự đoán thực tế.

`shp_platform` trong `src/` là bản chép của `libs/python`: không sửa ở đây, sửa ở nguồn rồi chạy `python tools/pylib/sync.py`.
