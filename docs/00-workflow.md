# Quy trình làm việc: nhánh, PR, Definition of Ready/Done (ARC-10)

## 1. Nhánh `main` được bảo vệ
Không ai ghi thẳng vào `main` (ruleset `protect-main` ở cả 4 repo):

| Quy tắc | Giá trị |
|---|---|
| Phải qua Pull Request | có |
| Số review tối thiểu | 1, và review của Code Owner (xem `.github/CODEOWNERS`) |
| Commit mới sau khi approve | hủy approval cũ |
| Thread review | phải resolve hết trước khi merge |
| Lịch sử | tuyến tính; merge kiểu **squash** (đã tắt merge commit và rebase) |
| Force-push, xóa `main` | cấm |
| Ngoại lệ | chỉ chủ repo (SA), và vẫn phải qua PR |

> Giới hạn gói GitHub hiện tại: repo private không có required reviewers cho Environment và không có secret scanning của GitHub. Cổng duyệt deploy được thay bằng review PR trên `shp-gitops` (đường dẫn `envs/prod/` do SA duyệt) và `gitleaks` trong CI. Khi nâng gói hoặc chuyển repo public thì bật lại theo `docs/04-devops/github-setup.md`.

## 2. Vòng đời một task
1. Lấy task trong `docs/05-plan/by-person/<bạn>.md`, đủ **Definition of Ready** (mục 3).
2. `git switch main && git pull`, rồi `git switch -c <ID>-mo-ta-ngan` (ví dụ `NTF-02-consumer-booked`). Một PR cho một task.
3. Code + test, chạy kiểm tra giống CI ([CONTRIBUTING](../CONTRIBUTING.md)).
4. `git push -u origin HEAD`, mở PR theo template, ghi `Closes #<issue>`.
5. Chờ CI xanh và 1 review; sửa theo góp ý bằng commit mới (không force-push sau khi đã có review).
6. SA hoặc reviewer **squash merge**; nhánh tự xóa. Cập nhật trạng thái task ở board.

## 3. Definition of Ready (được bắt đầu task)
- Có story/FR liên quan, tiêu chí "Xong khi" rõ, ước lượng ≤ 8 giờ (to hơn thì tách).
- Phụ thuộc đã xong hoặc đã có giao diện thống nhất (hợp đồng API/event trong `contracts/`).
- Tài liệu thiết kế cần đọc đã chỉ rõ (`detailed-design/...`).
- Dữ liệu test biết lấy ở đâu (bộ sinh dữ liệu giả hoặc seed).

## 4. Definition of Done (được merge)
- Test đơn vị + tích hợp xanh; coverage dòng ≥ 70%; lint/format sạch; không cảnh báo biên dịch.
- Hợp đồng (`contracts/`) khớp code; `tools/contracts/check_all.sh` xanh nếu có đổi.
- Endpoint có `@RequireShpAuthz` và dòng trong matrix phân quyền; audit được phát cho mọi quyết định.
- Không secret, không PII trong code/log/test; chỉ dữ liệu giả.
- Quét image/dependency không có High/Critical đã có bản vá.
- Chạy thử trên cụm local (`make -C dev deploy SVC=<svc>`) nếu đổi hành vi runtime.
- Kiểm thử theo yêu cầu: mỗi tiêu chí nghiệm thu (AC) của tính năng có test case trong `docs/06-testing/catalog` và test tự động tương ứng; cập nhật `catalog/automation.yaml` và chạy `python -I tools/qa/trace.py --update-baseline` để mức tự động hóa chỉ tăng. Test case thuộc dữ liệu bệnh nhân, phân quyền, audit hoặc AI (P0) bắt buộc có test tự động trước khi merge. Sửa lỗi thì kèm test hồi quy.
- Tài liệu liên quan cập nhật; PR được review và squash merge qua pipeline.

## 5. Ai sửa gì
| Khu vực | Sửa bởi | Ghi chú |
|---|---|---|
| `services/<svc>/src`, `tests`, `deploy/values-local.yaml` | Dev của service | Code Owner là chính dev đó, SA review chéo |
| `contracts/`, `libs/`, `dev/`, `.github/`, `Dockerfile`, Helm, `config/` | SA | Dev cần đổi thì mở issue |
| `shp-infra`, `shp-gitops`, `shp-platform-templates` | SA / DevOps | `envs/prod/` cần SA duyệt |

## 6. Hotfix
Vẫn qua PR: nhánh `hotfix-<mo-ta>`, review nhanh bởi SA, gắn nhãn `hotfix`; không có đường tắt vào `main`.
