# Đặc tả dữ liệu giả (synthetic) tiếng Việt (ARC-11)

Mục đích: mọi người (test, demo, load test) dùng cùng một cách sinh dữ liệu, nhìn giống thật nhưng **không bao giờ là dữ liệu thật**. Bộ sinh do D3 làm (DAT-01, DAT-02); tài liệu này là đặc tả đầu vào.

## 1. Nguyên tắc
1. Tất cả dữ liệu đều giả. Không lấy mẫu, che hay "xáo trộn" dữ liệu người thật.
2. **Tái lập được:** bộ sinh nhận `seed` (số nguyên) và `scale`; cùng seed + scale cho cùng kết quả byte-by-byte.
3. Định danh có dấu hiệu nhận biết là giả để không thể lẫn với thật (mục 3).
4. Toàn vẹn tham chiếu: lịch hẹn trỏ tới bệnh nhân/bác sĩ/slot tồn tại; lần khám trỏ tới lịch hẹn đã `fulfilled`.
5. Phân bố gần thực tế nhưng không suy ra từ dữ liệu thật (mục 5).

## 2. Thực thể và số lượng theo scale
| Thực thể (FHIR) | `small` (test, local) | `medium` (UAT) | `large` (UAT cỡ prod, load test) |
|---|---|---|---|
| Patient | 200 | 20 000 | 500 000 |
| Practitioner | 12 | 120 | 600 |
| Schedule/Slot | 30 ngày × bác sĩ | 90 ngày | 365 ngày |
| Appointment | 600 | 100 000 | 2 000 000 |
| Encounter + Observation | theo lịch `fulfilled` | idem | idem |
| DocumentReference | 100 (tệp mẫu vài KB) | 5 000 | 50 000 |
Chuyên khoa, lý do hủy, loại tài liệu, LOINC, ICD-10 lấy từ `contracts/terminology` (không tự bịa mã).

## 3. Quy tắc từng trường
| Trường | Quy tắc | Dấu hiệu "giả" |
|---|---|---|
| Họ tên | Họ phổ biến (Nguyễn, Trần, Lê, Phạm, Hoàng, Vũ, Đặng, Bùi...) + tên đệm + tên theo giới; có dấu tiếng Việt đầy đủ (NFC) | Thêm hậu tố `(test)` ở `Patient.name.text` khi `scale` = `small` |
| Giới, ngày sinh | Giới theo tỉ lệ 49/51; tuổi theo phân bố mục 5; ngày sinh hợp lệ | — |
| Số điện thoại | Dạng `0xxxxxxxxx` thuộc dải **không cấp phát** `09000xxxxx` | Dải dành riêng cho test |
| Email | `<slug>@example.test` | Tên miền `.test` (RFC 2606) |
| CCCD/CMND | 12 số bắt đầu `000`; không hợp lệ theo mã tỉnh thật | Tiền tố `000` |
| Mã BHYT | `TEST` + 11 số | Tiền tố `TEST` |
| Địa chỉ | Chọn tỉnh/quận/phường có thật, số nhà và tên đường ngẫu nhiên | Số nhà `0` hoặc ghi `(giả)` ở `address.text` |
| ID nội bộ | UUIDv7 sinh từ seed | — |
| Bác sĩ | Chức danh + tên; chuyên khoa từ danh mục; lịch làm việc 07:00 đến 17:00, nghỉ trưa 12:00 đến 13:00 | Email `.test` |

## 4. Dữ liệu lâm sàng
- Chẩn đoán lấy từ danh mục ICD-10 trong `contracts/terminology`; sinh hiệu theo LOINC (mạch, huyết áp, nhiệt độ, SpO₂...), giá trị trong khoảng sinh lý, có tỉ lệ nhỏ bất thường (≈ 8%) để test cảnh báo.
- Ghi chú lâm sàng tự do **do bộ sinh tạo từ mẫu câu** (không dùng nguồn bên ngoài); không chứa tên người thật hay cơ sở y tế thật.
- Tệp tài liệu: PDF/ảnh sinh trống có chữ "DỮ LIỆU GIẢ" in trên tệp; một số tệp "nhiễm" dùng chuỗi EICAR để test ClamAV.

## 5. Phân bố
- Tuổi bệnh nhân: 0-14 (18%), 15-44 (35%), 45-64 (29%), ≥ 65 (18%).
- Lịch hẹn: đỉnh 07:00-09:00 và 13:30-15:00; thứ Hai nhiều nhất; no-show nền ≈ 12%, cao hơn với bệnh nhân đã no-show trước đó, đặt trước > 14 ngày, khung giờ sớm. (Quan hệ này là mô phỏng giả định cho ML, không phải kết luận y khoa.)
- Hủy lịch ≈ 10%; lý do theo danh mục `cancel-reason`.
- Load test 1x/5x/10x: nhân tốc độ đặt lịch theo `docs/02-requirements/workload-analysis.md`.

## 6. Cách dùng
- Local: `make -C dev seed-users` tạo người dùng đăng nhập (patient/doctor/nurse/admin/dpo); dữ liệu nghiệp vụ nạp bằng bộ sinh của D3 với `--scale small --seed 42`.
- Test: dùng seed cố định trong test để kết quả ổn định; không commit tệp dữ liệu lớn, chỉ commit seed + tham số.
- CI/UAT: `scale` chọn theo môi trường (bảng mục 2); seed ghi vào log job để tái lập.

## 7. Kiểm tra tự động (D3 thêm vào test của bộ sinh)
- Không giá trị nào khớp mẫu số điện thoại/CCCD thật (chỉ dải giả).
- Toàn vẹn tham chiếu 100%; cùng seed cho cùng hash đầu ra.
- Không có tên miền email khác `.test`/`example.*`.
