# Kiểm thử

Kế hoạch kiểm thử, danh mục test case, truy vết yêu cầu và test tự động của Smart Hospital Platform. Bắt đầu từ
[Kế hoạch kiểm thử tổng thể](dist/SHP_Master_Test_Plan_v0.9.docx) và [báo cáo truy vết](traceability.md).

| Thư mục / file | Nội dung |
|---|---|
| `content/10-test-plan.yaml` | Nội dung Kế hoạch kiểm thử tổng thể (sinh ra `dist/SHP_Master_Test_Plan_*.docx`) |
| `catalog/<module>.yaml` | Test case: mỗi tiêu chí nghiệm thu (AC) và NFR của SRS ít nhất một test case; `security.yaml` là test bảo mật bổ sung |
| `catalog/automation.yaml` | Test case nào đã có test tự động, ở đâu, còn thiếu gì, ai viết |
| `baseline.json` | Mức tự động hóa tối thiểu; PR làm giảm số này bị chặn |
| `traceability.md` | Báo cáo truy vết và độ phủ (sinh ra, không sửa tay) |
| `dist/Test_Cases.xlsx` | Danh sách test case để theo dõi (sinh ra) |

Test tự động nằm ở: `libs/java/*/src/test`, `services/*/src/test`, `web/src/**/*.test.ts(x)` (đơn vị), `web/e2e` (E2E,
trợ năng, tiêu đề bảo mật), `tests/performance/k6` (hiệu năng), `tools/contracts` (hợp đồng). Workflow: `.github/workflows/qa.yml`.

## Việc hằng ngày

```bash
# Thêm yêu cầu/AC vào SRS → tạo test case nháp cho phần còn thiếu (không đổi test case có sẵn)
python -I tools/qa/seed_catalog.py --write

# Kiểm tra truy vết như CI
python -I tools/qa/trace.py --check

# Sinh lại báo cáo, Excel, Kế hoạch kiểm thử (docx)
python -I tools/qa/trace.py

# Sau khi viết test tự động cho một test case: cập nhật automation.yaml rồi nâng baseline
python -I tools/qa/trace.py --update-baseline
```

E2E trên máy dev (không cần backend): `cd web && npm ci && npx playwright install chromium && npm run e2e`.
Đã cài Edge/Chrome thì dùng `PW_CHANNEL=msedge npm run e2e` để khỏi tải trình duyệt.

## Cách gắn test tự động vào test case

- **Java, Vitest, k6, workflow:** thêm vào `catalog/automation.yaml`: `refs: ["đường/dẫn/file::tên-test"]` (công cụ kiểm file có thật và tên có trong file).
- **E2E Playwright:** đặt mã test case trong tiêu đề, ví dụ `test('TC-AC-03.4 ...')`; công cụ tự nhận ra. `test.fixme` = chưa hiện thực, được tính là chờ viết.
- **Trạng thái:** `automated` (phủ đủ), `partial` (ghi `gap` còn thiếu gì), `planned` (ghi `blocked_by` và `owner`), `manual`.

## Quy ước test

- Chỉ dữ liệu giả. Dữ liệu giả có tên, SĐT, email dễ nhận diện để test quét log tìm được.
- Test P0 (dữ liệu bệnh nhân, phân quyền, audit, AI, an toàn người bệnh) kiểm cả trường hợp bị từ chối: bị chặn thì kiểm 404/403 đúng, và có audit.
- Mỗi test tự dựng và dọn dữ liệu của mình; không phụ thuộc thứ tự chạy; E2E cố định đồng hồ trình duyệt.
- Sửa lỗi kèm test hồi quy. Phát hiện lỗ hổng: báo qua kênh riêng tư của repo, không mở issue công khai.
