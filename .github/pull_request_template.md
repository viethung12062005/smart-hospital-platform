## Task
- ID: <!-- vd. NTF-02 (một PR = một task; tên nhánh `<ID>-mo-ta-ngan`) -->
- Story: <!-- vd. US-041 -->
- Tài liệu thiết kế đã theo: <!-- vd. detailed-design/04-db-notification-ml.md §1.3 -->

## Thay đổi
<!-- Làm gì, vì sao; nêu điểm reviewer cần chú ý -->

## Ảnh hưởng hợp đồng
- [ ] Không đổi `contracts/` (API, event, matrix phân quyền, mã danh mục)
- [ ] Có đổi — đã cập nhật `contracts/` và tài liệu thiết kế, `tools/contracts/check_all.sh` xanh, SA đã đồng ý trước

## Definition of Done
- [ ] Test viết kèm và chạy xanh: `mvn -B -pl services/<svc> -am verify` (Java) hoặc `ruff`/`mypy`/`pytest` (Python)
- [ ] Endpoint mới có `@RequireShpAuthz`; lỗi nghiệp vụ dùng mã trong danh mục (07 §5)
- [ ] Event phát qua `outbox.add(...)` trong cùng transaction; consumer bọc `inbox.handleOnce(...)`
- [ ] Chỉ dữ liệu giả; không có secret/key/mật khẩu trong code, cấu hình hay log; log không chứa PII
- [ ] Không sửa Dockerfile, pipeline, Helm, `config/PlatformConfig` (cần đổi → mở issue cho SA)
- [ ] Đã chạy thử trên cụm local nếu đổi hành vi runtime: `make -C dev deploy SVC=<svc>`
