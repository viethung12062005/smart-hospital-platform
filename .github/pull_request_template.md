<!-- Tiêu đề PR = commit trên main khi squash: `<story> (<task>): <mô tả>`, vd. `US-041 (NTF-02): create reminders on appointment-booked`
     hoặc `ENABLER (ARC-04): add architecture diagrams`. Base luôn là main. Cách viết từng mục: docs/00-git-github.md §4.1 -->

## Task
- ID: <!-- vd. NTF-02 (một PR = một task; tên nhánh `<ID>-mo-ta-ngan`, tách từ main) -->
- Story: <!-- vd. US-041, hoặc ENABLER -->
- Tài liệu thiết kế đã theo: <!-- vd. detailed-design/04-db-notification-ml.md §1.3 -->

## Thay đổi
<!-- 2–5 gạch đầu dòng: vì sao cần, làm gì ở mức hành vi, điểm reviewer cần soi kỹ. Không liệt kê lại từng file. -->

## Ảnh hưởng hợp đồng
- [ ] Không đổi `contracts/` (API, event, matrix phân quyền, mã danh mục)
- [ ] Có đổi — đã cập nhật `contracts/` và tài liệu thiết kế, `tools/contracts/check_all.sh` xanh, SA đã đồng ý trước

## Bảo mật và dữ liệu
<!-- Có chạm dữ liệu bệnh nhân, phân quyền, audit, PII, secret, egress ra ngoài cụm không? Nếu có: kiểm soát nào đã thêm.
     Nếu không: ghi "Không ảnh hưởng" và lý do ngắn. Không để trống. -->

## Kiểm tra
<!-- Lệnh đã chạy và kết quả thật (vd. `pytest`: 45 passed, có Docker). Việc chưa kiểm được và vì sao. -->

## Việc theo sau
<!-- Việc cố ý để PR khác, kèm task/issue. Không có thì xóa mục này. -->

## Definition of Done
- [ ] Đã tự review diff trên GitHub: không còn debug, code chết, thay đổi lạc đề
- [ ] Test viết kèm và chạy xanh: `mvn -B -pl services/<svc> -am verify` (Java) hoặc `ruff`/`mypy`/`pytest` có Docker (Python)
- [ ] Endpoint mới có `@RequireShpAuthz`; lỗi nghiệp vụ dùng mã trong danh mục (07 §5)
- [ ] Event phát qua `outbox.add(...)` trong cùng transaction; consumer bọc `inbox.handleOnce(...)`
- [ ] Chỉ dữ liệu giả; không có secret/key/mật khẩu trong code, cấu hình hay log; log không chứa PII
- [ ] Không sửa Dockerfile, pipeline, Helm, `config/PlatformConfig` (cần đổi → mở issue cho SA)
- [ ] Đã chạy thử trên cụm local nếu đổi hành vi runtime: `make -C dev deploy SVC=<svc>`
