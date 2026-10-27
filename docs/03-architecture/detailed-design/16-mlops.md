# 16 — MLOps cho mô hình dự đoán no-show

Mô hình, đặc trưng và bảng dữ liệu: [04](04-db-notification-ml.md) §2. Quyết định gốc: [ADR-0007](../adr/0007-no-show-ml-mlops.md).
Story: US-063..065, US-071..074 · Task: `ML-*` (D3), `MLO-*` (SA).

## 1. Dữ liệu huấn luyện (toàn bộ là dữ liệu giả)

Không dùng dataset no-show công khai vì chúng bắt nguồn từ dữ liệu bệnh nhân thật. D3 viết bộ sinh dữ liệu theo đặc tả sau (`ML-01`, `ML-21`).

| Hạng mục | Giá trị |
|---|---|
| Quy mô | ~80.000 lịch hẹn, 24 tháng, ~50.000 bệnh nhân giả (ID ngẫu nhiên, không liên quan người thật) |
| Nhãn | `noshow` (1) / `fulfilled` (0); lịch hẹn `cancelled` loại khỏi tập huấn luyện |
| Tỉ lệ dương | ~18% tổng, biến thiên theo tháng (±3 điểm phần trăm) |
| Quy luật | `logit(p) = β0 + β1·lead_time + β2·prior_noshow_rate + β3·is_first_visit + β4·early_morning + β5·channel + β6·specialty + ε`; hệ số do D3 chọn và ghi vào tài liệu, **ε đủ lớn để AUC đạt được khoảng 0,78–0,82** (nếu không, cổng AUC ≥ 0,75 vô nghĩa) |
| Cấu trúc thời gian | Có xu hướng theo mùa và mùa bệnh; chia train/validation **theo thời gian** (18 tháng đầu / 6 tháng cuối), không xáo trộn |
| Tái lập | Seed cố định; sinh ra Parquet + file mô tả (schema, tỉ lệ nhãn, hash) |
| Bộ dữ liệu dịch chuyển (`ML-21`) | Cùng schema; lead time trung bình +40%, tỉ lệ vắng +6 điểm, đổi tỉ trọng kênh đặt. Dùng để test detector drift và demo cảnh báo |
| Giới hạn phải ghi trong báo cáo | Mô hình chỉ học lại quy luật do nhóm tự viết; kết quả chứng minh quy trình MLOps, **không** chứng minh khả năng dự đoán thực tế |

## 2. Huấn luyện có kiểm soát và tái lập

```
dataset Parquet (hash) ─► kiểm tra dữ liệu (pandera) ─► feature package dùng chung ─► train (baseline LR, LightGBM)
        ─► MLflow run (tham số, metric, hash dataset, git SHA, image digest) ─► cổng chất lượng ─► đăng ký candidate
```

- **Phiên bản dữ liệu:** `ML-13`. Mỗi run MLflow ghi hash dataset; cùng hash + seed + image phải cho cùng model.
- **Kiểm tra dữ liệu (`ML-14`):** schema, kiểu, khoảng giá trị, tỉ lệ thiếu < 2%, nhãn không rò rỉ thời gian (đặc trưng chỉ dùng thông tin có trước `booked_at`), tỉ lệ dương trong [10%, 30%]. Sai → dừng pipeline, không train.
- **Đặc trưng dùng chung (`ML-15`, `ML-16`):** một package cho cả huấn luyện và phục vụ; test so đầu ra trên bộ mẫu chuẩn phải khớp tuyệt đối.
- **Môi trường (`MLO-02`):** image huấn luyện ghim phiên bản, ký, quét lỗ hổng.

## 3. Cổng chất lượng và model card

Model ứng viên chỉ được đăng ký (alias `candidate`) khi **tất cả** điều kiện sau đúng:

| Cổng | Điều kiện |
|---|---|
| Hiệu năng tổng | AUC (validation theo thời gian) ≥ 0,75 và hơn baseline LR |
| Nhóm con | AUC của từng nhóm chuyên khoa, nhóm tuổi, kênh đặt (nhóm ≥ 300 mẫu) không thấp hơn AUC tổng quá 0,07 |
| Calibration | Sai số hiệu chuẩn kỳ vọng (ECE) ≤ 0,05 |
| Độ trễ | p95 dự đoán ≤ 100 ms trên cấu hình triển khai |
| Không thoái lui | Không kém model `production` hiện tại quá 0,01 AUC trên cùng tập kiểm tra |

**Model card** (`ML-18`) tự sinh mỗi lần đăng ký: mục đích và giới hạn sử dụng, dữ liệu (hash, khoảng thời gian, tỉ lệ nhãn), metric tổng và theo nhóm, ngưỡng và cách chọn, phiên bản code/image, người/quy trình tạo, cảnh báo "huấn luyện trên dữ liệu giả".

## 4. Giám sát: drift và hiệu năng thực tế

| Tín hiệu | Cách tính | Ngưỡng cảnh báo |
|---|---|---|
| Drift đặc trưng (`ML-19`) | PSI từng đặc trưng, cửa sổ 7 ngày gần nhất so với tập huấn luyện | PSI > 0,2 ở ≥ 2 đặc trưng chính (`lead_time_days`, `prior_noshow`, `start_hour`, `channel`) |
| Drift điểm dự đoán | PSI phân phối `score` so với lúc đăng ký | PSI > 0,2 |
| Hiệu năng thực tế (`ML-20`) | Ghép `prediction` với kết quả thật từ `appointment-status-changed`; AUC cuộn 14 ngày | AUC giảm > 0,05 so với lúc đăng ký **và** có ≥ 500 mẫu nhãn (nhãn đến muộn vì phải chờ lịch hẹn diễn ra) |
| Vận hành | Độ trễ, tỉ lệ lỗi, tỉ lệ `unknown` của ageBand | theo SLO chung |

Job chạy hằng ngày (`ML-10`), xuất metric Prometheus; cảnh báo và dashboard ở `MLO-05`.

## 5. Huấn luyện lại tự động

```
cảnh báo drift / AUC giảm ─► ML-22 kiểm điều kiện ─► MLO-04 chạy job ML-23 trên cluster
     ─► kiểm tra dữ liệu ─► train ─► cổng chất lượng (§3) ─► đăng ký alias candidate ─► (§6) shadow + canary
```

| Quy tắc | Giá trị |
|---|---|
| Điều kiện chạy | Có cảnh báo drift **hoặc** AUC thực tế giảm; ≥ 500 mẫu có nhãn mới kể từ lần train trước; ngoài thời gian cooldown **7 ngày** |
| Chống chạy chồng | Tối đa một lần huấn luyện lại tại một thời điểm (khóa) |
| Dữ liệu train | Cửa sổ trượt: 24 tháng gần nhất gồm dữ liệu mới có nhãn |
| Không đạt cổng | Dừng, **giữ nguyên model production**, cảnh báo kèm lý do (cổng nào, giá trị) |
| Đạt cổng | Đăng ký `candidate`, đi tiếp §6; mọi bước ghi audit và liên kết run MLflow |
| Giới hạn an toàn | Tối đa 2 lần huấn luyện lại tự động mỗi 14 ngày; vượt thì dừng và báo người xử lý |

## 6. Triển khai bằng shadow và canary tự động

| Bước | Hành vi | Điều kiện sang bước sau |
|---|---|---|
| Shadow (`ML-24`) | Model `candidate` chấm điểm song song với `production`, kết quả chỉ ghi nhận, người dùng không bị ảnh hưởng | ≥ 1.000 dự đoán hoặc 24 giờ; PSI điểm số giữa hai model < 0,1; đồng thuận phân loại (`highRisk`) ≥ 90% |
| Canary 10% | Argo Rollouts chuyển 10% lưu lượng dự đoán sang `candidate` | AnalysisTemplate đạt trong 30 phút |
| Canary 50% | Tăng lên 50% | AnalysisTemplate đạt trong 30 phút |
| 100% | Chuyển alias `production` sang model mới; giữ model cũ làm điểm rollback | — |

**AnalysisTemplate (`MLO-03`)** đọc metric Prometheus theo `model_version` (`ML-25`): tỉ lệ lỗi ≤ bản cũ + 0,5%, p95 độ trễ ≤ 100 ms, PSI điểm số < 0,1, tỉ lệ `highRisk` trong ±5 điểm phần trăm so với bản cũ. **Vi phạm bất kỳ chỉ số → tự rollback** về alias `production` cũ và cảnh báo.

`autoPromote` là cấu hình theo môi trường: `true` ở dev/staging (tự đi hết các bước); ở prod mặc định `true` vì dữ liệu giả và rủi ro thấp, đặt `false` nếu muốn dừng chờ phê duyệt (GitHub Environment) trước bước 100%.

## 7. Đối chiếu với đề bài

Phần AI Engineering (P5) yêu cầu evaluation dataset/metrics, guardrails, latency/cost: đáp ứng bởi §1–§4 (evaluation, chất lượng theo nhóm), §6 (độ trễ, rollback) và model card (§3). Đo lường và báo cáo AUC, drift, lịch sử huấn luyện lại nằm trong báo cáo PRD-07.
