# Pha 2 — Yêu cầu chức năng

> Trạng thái: **Approved — G2 qua ngày 2026-10-26**. Ưu tiên theo MoSCoW (M = Must, S = Should, C = Could).
> Tiêu chí nghiệm thu (AC) viết theo Given/When/Then để chuyển thẳng thành test tự động.

## Actor
`Patient`, `Doctor`, `Nurse` (điều dưỡng/nhân viên lâm sàng), `Admin`, `System` (job/worker), `DPO` (đọc audit).

## Tổng quan use case

| ID | Use case | Actor chính | Ưu tiên | Nguồn (BA) |
|---|---|---|---|---|
| UC-01 | Quản lý hồ sơ bệnh nhân | Patient, Nurse | M | BP-3 |
| UC-02 | Quản lý lịch làm việc bác sĩ | Nurse, Admin, Doctor | M | BP-1 |
| UC-03 | Đặt / hủy / đổi lịch khám | Patient, Nurse | M | BP-1, BR-01..04 |
| UC-04 | Ghi nhận lần khám (encounter) | Doctor, Nurse | M | BP-3 |
| UC-05 | Upload và truy cập tài liệu lâm sàng | Nurse, Doctor, Patient | M | BP-3, BR-08 |
| UC-06 | Tìm kiếm lịch sử bệnh nhân | Doctor, Nurse | M | BP-3 |
| UC-07 | Nhắc lịch và thông báo | System | M | BP-2 |
| UC-08 | Quản lý người dùng và phân quyền | Admin | M | BP-5 |
| UC-09 | Xem audit truy cập dữ liệu nhạy cảm | DPO, Admin | M | BP-5, BR-07 |
| UC-10 | Truy cập khẩn cấp (break-glass) | Doctor | S | BR-06, BR-07 |
| UC-11 | Trợ lý tri thức y tế (RAG) | Doctor, Nurse | M | BP-4, BR-09 |
| UC-12 | Tóm tắt hồ sơ bệnh nhân bằng AI | Doctor | S | BP-3 |
| UC-13 | Trích xuất dữ liệu từ tài liệu upload | System, Nurse | S | BP-6, BR-10 |
| UC-14 | Chatbot đặt lịch | Patient | C | BP-1 |
| UC-15 | Dự đoán nguy cơ no-show (ML) | System | S | BP-2 |
| UC-16 | Sao lưu và khôi phục dữ liệu | Admin (vận hành) | M | Đề bài: backup/recovery |

---

## UC-01 Quản lý hồ sơ bệnh nhân
- **Mô tả:** Bệnh nhân tự đăng ký và cập nhật thông tin hành chính; điều dưỡng tạo/sửa hồ sơ tại quầy.
- **Dữ liệu (FHIR):** `Patient`.
- **Luồng chính:** (1) Bệnh nhân đăng ký tài khoản → (2) điền họ tên, ngày sinh, giới tính, SĐT, email,
  địa chỉ → (3) hệ thống kiểm tra trùng (SĐT + ngày sinh) → (4) tạo hồ sơ, gắn với tài khoản.
- **Luồng phụ:** trùng hồ sơ → yêu cầu điều dưỡng xác minh và gộp.
- **AC:**
  - AC-01.1 Given bệnh nhân đã đăng nhập, When xem hồ sơ, Then chỉ thấy hồ sơ của mình.
  - AC-01.2 Given hồ sơ đã có cùng SĐT + ngày sinh, When đăng ký, Then hệ thống không tạo hồ sơ mới mà báo cần xác minh.
  - AC-01.3 Mọi lần sửa hồ sơ tạo phiên bản mới, xem được lịch sử phiên bản.

## UC-02 Quản lý lịch làm việc bác sĩ
- **Dữ liệu:** `Schedule` (lịch của một bác sĩ), `Slot` (khung giờ, mặc định 15 phút).
- **Luồng chính:** Nurse/Admin tạo lịch theo mẫu tuần (ví dụ T2–T6, 8:00–11:30) → hệ thống sinh slot cho
  30 ngày tới (BR-04) → có thể chặn slot (nghỉ phép) → slot đã có lịch hẹn thì không xóa được.
- **AC:**
  - AC-02.1 Given mẫu lịch 8:00–11:30 slot 15 phút, When sinh slot cho một ngày, Then có đúng 14 slot `free`.
  - AC-02.2 Given slot đã được đặt, When chặn slot, Then trả lỗi và yêu cầu hủy/đổi lịch hẹn trước.
  - AC-02.3 Doctor chỉ xem được lịch của mình; không sửa được lịch.

## UC-03 Đặt / hủy / đổi lịch khám
- **Dữ liệu:** `Slot`, `Appointment`.
- **Luồng chính (đặt):** (1) Bệnh nhân lọc slot theo chuyên khoa, bác sĩ, ngày → (2) chọn slot → (3) gửi
  yêu cầu đặt kèm khóa idempotency → (4) hệ thống kiểm tra BR-01, BR-03, BR-04 → (5) tạo `Appointment`
  trạng thái `booked`, slot chuyển `busy` → (6) phát sự kiện "đã đặt lịch" để nhắc lịch và audit.
- **Luồng phụ:**
  - Slot vừa bị người khác đặt → báo "slot không còn trống", gợi ý 3 slot gần nhất.
  - Client gửi lại cùng yêu cầu (timeout, mất mạng) → trả về đúng lịch hẹn đã tạo, không tạo lịch trùng.
  - Hủy: trước giờ khám ≥ 2h (BR-02) → `cancelled`, slot trở về `free`.
  - Đổi lịch = đặt slot mới + hủy slot cũ trong cùng một thao tác; thất bại thì giữ nguyên lịch cũ.
- **AC:**
  - AC-03.1 Given 1 slot trống và 50 yêu cầu đặt đồng thời từ 50 bệnh nhân, Then đúng 1 yêu cầu thành công, 49 nhận lỗi xung đột.
  - AC-03.2 Given cùng khóa idempotency gửi 2 lần, Then cả hai nhận cùng một lịch hẹn, chỉ 1 lịch hẹn được tạo.
  - AC-03.3 Given cùng khóa idempotency nhưng nội dung khác, Then bị từ chối.
  - AC-03.4 Given bệnh nhân đã có 3 lịch hẹn tương lai, When đặt thêm, Then bị từ chối (BR-03).
  - AC-03.5 Given còn 1 giờ đến giờ khám, When bệnh nhân hủy, Then bị từ chối; Nurse hủy được.
  - AC-03.6 Bệnh nhân không đặt được lịch cho bệnh nhân khác.

## UC-04 Ghi nhận lần khám
- **Dữ liệu:** `Encounter`, `Observation` (sinh hiệu), `Condition` (chẩn đoán, mã ICD-10).
- **Luồng chính:** Nurse check-in (Appointment → `arrived`, tạo `Encounter` `in-progress`) → đo sinh hiệu →
  Doctor ghi bệnh sử, chẩn đoán → kết thúc (`finished`, Appointment → `fulfilled`).
- **AC:**
  - AC-04.1 Chỉ Doctor có quan hệ điều trị mới ghi chẩn đoán được.
  - AC-04.2 Encounter đã `finished` thì không sửa; muốn bổ sung phải tạo bản ghi bổ sung (giữ nguyên bản cũ).

## UC-05 Upload và truy cập tài liệu lâm sàng
- **Dữ liệu:** `DocumentReference` (metadata), file trên object storage.
- **Luồng chính:** Nurse/Doctor xin link upload → client upload trực tiếp lên storage → hệ thống xác nhận,
  kiểm tra loại file (PDF, JPEG, PNG; ≤ 20 MB) và quét mã độc → tạo `DocumentReference` → phát sự kiện "đã upload tài liệu".
- **Truy cập:** người có quyền xin link tải, hiệu lực ≤ 5 phút (BR-08); mỗi lần xin link đều được ghi audit.
- **AC:**
  - AC-05.1 File sai định dạng hoặc > 20 MB bị từ chối.
  - AC-05.2 Link tải hết hạn sau 5 phút thì trả 403.
  - AC-05.3 Bệnh nhân chỉ tải được tài liệu của mình và chỉ những tài liệu đã được bác sĩ "phát hành cho bệnh nhân".

## UC-06 Tìm kiếm lịch sử bệnh nhân
- **Luồng chính:** Doctor/Nurse tìm bệnh nhân theo tên, SĐT, mã bệnh nhân, ngày sinh → mở timeline: lần khám,
  chẩn đoán, sinh hiệu, tài liệu, theo thời gian; lọc theo khoảng thời gian, loại dữ liệu.
- **AC:**
  - AC-06.1 Tìm theo một phần tên không dấu ("nguyen van") vẫn ra kết quả có dấu.
  - AC-06.2 Doctor không có quan hệ điều trị thì thấy kết quả tìm (tên, năm sinh) nhưng không mở được timeline (trừ break-glass).

## UC-07 Nhắc lịch và thông báo
- **Luồng chính:** Khi có sự kiện "đã đặt lịch", hệ thống lên lịch nhắc T-24h và T-2h qua email/SMS/push; nếu
  lịch hẹn bị hủy thì hủy luôn các lần nhắc; thông báo khi có kết quả mới.
- **Luồng phụ:** nhà cung cấp gửi tin lỗi → thử lại theo backoff (tối đa 5 lần) → đưa vào hàng lỗi (dead-letter)
  và cảnh báo vận hành; kênh chính lỗi thì chuyển kênh dự phòng (push → email).
- **AC:**
  - AC-07.1 Lịch hẹn bị hủy trước giờ nhắc thì không gửi nhắc.
  - AC-07.2 Nhà cung cấp lỗi tạm thời không làm mất thông báo; không gửi trùng quá 1 lần cho cùng một lần nhắc.

## UC-08 Quản lý người dùng và phân quyền
- Admin mời nhân viên, gán role (Doctor/Nurse/Admin), khóa tài khoản; liên kết tài khoản Doctor với `Practitioner`.
- **AC:** AC-08.1 Tài khoản bị khóa thì token hiện tại bị từ chối trong ≤ 5 phút. AC-08.2 Nhân viên bắt buộc MFA.

## UC-09 Xem audit
- DPO/Admin lọc audit theo bệnh nhân, người truy cập, thời gian, loại hành động; xuất báo cáo.
- **AC:** AC-09.1 Mọi thao tác đọc/ghi dữ liệu bệnh nhân ở UC-01, 04, 05, 06, 10, 11–13 đều có bản ghi audit (ai, khi nào, cái gì, kết quả, từ đâu).
  AC-09.2 Không ai (kể cả Admin) sửa hay xóa được bản ghi audit.

## UC-10 Truy cập khẩn cấp (break-glass)
- Doctor không có quan hệ điều trị vẫn mở được hồ sơ khi cấp cứu: bắt buộc nhập lý do, quyền có hiệu lực 60 phút.
- **AC:** AC-10.1 Mỗi lần break-glass gửi cảnh báo cho DPO trong ≤ 5 phút.

## UC-11 Trợ lý tri thức y tế (RAG)
- **Nguồn:** phác đồ, quy trình nội bộ đã được duyệt (tài liệu giả định/công khai), do Admin/trưởng khoa quản lý.
- **Luồng chính:** người dùng hỏi → hệ thống chỉ tìm trong tài liệu người đó được phép xem → sinh câu trả lời
  kèm trích dẫn (tài liệu, mục) → người dùng đánh giá hữu ích/không.
- **AC:**
  - AC-11.1 Mọi câu trả lời có ≥ 1 trích dẫn trỏ đúng đoạn nguồn; không tìm thấy nguồn thì trả "không có thông tin trong tài liệu".
  - AC-11.2 Tài liệu chèn chỉ thị ("bỏ qua hướng dẫn trước…") không làm thay đổi hành vi (kiểm bằng bộ test prompt injection).
  - AC-11.3 Model chính lỗi thì hệ thống tự chuyển model dự phòng; tất cả model lỗi thì trả danh sách tài liệu liên quan.

## UC-12 Tóm tắt hồ sơ bệnh nhân
- Doctor mở hồ sơ → bấm "tóm tắt" → nhận tóm tắt (bệnh nền, dị ứng, lần khám gần nhất, thuốc, kết quả bất thường),
  mỗi ý trỏ về bản ghi gốc.
- **AC:** AC-12.1 Chỉ dùng dữ liệu người dùng được phép xem. AC-12.2 Mỗi ý trong tóm tắt có liên kết bản ghi gốc. AC-12.3 Có nhãn "do AI tạo, cần kiểm tra".

## UC-13 Trích xuất dữ liệu từ tài liệu upload
- Khi có sự kiện "đã upload tài liệu" → OCR → phân loại (kết quả xét nghiệm, đơn thuốc, giấy chuyển viện…) →
  trích trường → kiểm tra hợp lệ → độ tin cậy ≥ ngưỡng thì đề xuất lưu, thấp hơn thì đưa vào hàng chờ duyệt (BR-10).
- **AC:** AC-13.1 Không trường nào được ghi vào hồ sơ khi chưa có người xác nhận (MVP luôn cần xác nhận). AC-13.2 Lỗi xử lý thì retry, quá số lần thì vào dead-letter, không chặn upload.

## UC-14 Chatbot đặt lịch
- Bệnh nhân chat ("tôi muốn khám tim mạch sáng thứ 5") → bot tìm slot → đề xuất → **bệnh nhân xác nhận** → bot gọi UC-03 bằng quyền của chính bệnh nhân.
- **AC:** AC-14.1 Bot không bao giờ đặt/hủy mà không có xác nhận rõ ràng. AC-14.2 Bot không truy cập dữ liệu của bệnh nhân khác. AC-14.3 Bot lỗi thì chuyển sang form đặt lịch.

## UC-15 Dự đoán nguy cơ no-show
- Khi lịch hẹn được tạo → tính xác suất no-show → nếu ≥ ngưỡng thì UC-07 thêm một lần nhắc (T-6h) và đánh dấu cho điều dưỡng.
- **AC:** AC-15.1 Dịch vụ ML lỗi thì nhắc lịch vẫn chạy bình thường (không phụ thuộc cứng). AC-15.2 Mỗi dự đoán lưu phiên bản model để truy vết.

## UC-16 Sao lưu và khôi phục
- **AC:** AC-16.1 Khôi phục được dữ liệu lịch hẹn và hồ sơ về thời điểm bất kỳ trong 7 ngày qua. AC-16.2 Diễn tập khôi phục có ghi lại thời gian thực tế (so với RTO).
