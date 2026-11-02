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

## 2. Theo dõi việc theo User Story

Việc được theo dõi theo **User Story (US)**, nhưng code vẫn đi theo **task**: một US gồm nhiều task, mỗi task do một người làm và
có PR riêng. Không có nhánh chung hay PR chung cho cả US.

### 2.1 Nguồn dữ liệu

| Nơi | Giữ gì | Ai cập nhật |
|---|---|---|
| [backlog.csv](05-plan/backlog.csv) | Định nghĩa task: ID, story, "Xong khi", ước lượng, phụ thuộc | SA (qua `tools/plan`) |
| Bảng kế hoạch nhóm (Google Sheet, tab *Công việc* và *Theo User Story*) | Người phụ trách, người review, tuần, trạng thái, % | Người làm task, SA đối chiếu cuối tuần |
| GitHub Issues | Việc hằng ngày: issue US, issue task, liên kết PR, phụ thuộc | Người làm task |

Lệch nhau thì sửa theo thứ tự: backlog (định nghĩa) → sheet (phân công) → issue.

### 2.2 Cấu trúc issue

| Cấp | Trên GitHub | Tiêu đề | Label | Assignee | Milestone |
|---|---|---|---|---|---|
| User Story | Issue cha | `US-015: Bệnh nhân đặt lịch khám` | `user-story`, `priority:must` / `should` / `could` | SA (người nghiệm thu) | Tuần kết thúc dự kiến |
| Nhóm việc nền | Issue cha | `ENABLER: Hạ tầng Azure (Terraform)` | `enabler` | SA | Tuần kết thúc dự kiến |
| Task | **Sub-issue** của US hoặc của nhóm việc nền | `SCH-12: <tên task>` | `task` và label loại (`enhancement`, `documentation`...) | **Một người**, đúng cột *Phụ trách* của sheet | Tuần của task |

- Task thuộc nhiều US: là sub-issue của US chính (cột *User Story* của sheet); các US còn lại ghi trong mô tả.
- Phụ thuộc giữa task: dùng quan hệ **Blocked by** của GitHub theo cột *Phụ thuộc*. Label `blocked` chỉ dùng khi chờ việc ngoài
  backlog (quyền Azure, quyết định của nhóm...), ghi rõ chờ gì.
- Milestone đặt tên theo tuần của kế hoạch: `Tuần 6 (09/11–15/11)`.
- Mô tả issue US: câu chuyện người dùng, tiêu chí nghiệm thu (AC) và test case liên quan trong `docs/06-testing/catalog`.
  GitHub tự hiện danh sách sub-issue và tiến độ.

### 2.3 Vòng đời một task
1. Chọn issue task của mình trong milestone tuần này, đủ **Definition of Ready** (mục 3) và không còn *Blocked by* nào mở.
2. `git fetch origin && git switch -c <ID>-mo-ta-ngan origin/main` (ví dụ `SCH-12-book-appointment`). Luôn tách từ `main`, không tách từ nhánh task khác.
3. Code + test, chạy kiểm tra giống CI ([CONTRIBUTING](../CONTRIBUTING.md)).
4. `git push -u origin HEAD`, mở PR theo template, tiêu đề `<story> (<task>): <mô tả>`, phần Task ghi `Closes #<issue task>` và
   `Part of #<issue US>`. Chi tiết nhánh, commit, PR, merge: [00-git-github.md](00-git-github.md).
5. Chờ CI xanh và 1 review; sửa theo góp ý bằng commit mới (không force-push sau khi đã có review).
6. SA hoặc reviewer **squash merge**; nhánh tự xóa, issue task tự đóng. Người làm cập nhật trạng thái và % ở sheet.

Task lớn hơn 8 giờ thì tách thành nhiều task (nhiều issue, nhiều PR), không giữ một PR mở nhiều ngày.

### 2.4 US chưa xong khi đã merge một phần
Task của một US merge vào `main` dần dần. Phần đã merge không được làm hỏng luồng đang chạy:
- Tính năng dở dang nằm sau cờ `SHP_FEATURE_<TÊN>` ([00-conventions §4](00-conventions.md#4-cấu-hình)), mặc định tắt ở uat và prod;
  hoặc chưa mở route ở gateway hay menu ở web.
- API mới thêm vào hợp đồng trước; client chỉ gọi khi phía server đã merge.
- Cờ được gỡ trong task cuối của US, sau khi nghiệm thu.

### 2.5 Đóng một US
SA đóng issue US khi:
- Mọi sub-issue đã đóng (task xong hoặc chuyển sang US khác, có ghi lý do).
- Mọi AC có test case trong catalog và test tự động đã xanh trên `main`.
- Đã chạy thử luồng trên cụm (local hoặc uat) với cờ tính năng bật, chỉ dữ liệu giả; ghi kết quả trong comment của issue US.

### 2.6 Bảo mật khi dùng issue
- Repo này là **public**: issue, comment, ảnh chụp chỉ dùng dữ liệu giả. Không dán dữ liệu bệnh nhân thật, token, mật khẩu, connection
  string hay log có PII.
- Lỗ hổng bảo mật không mở issue thường: báo SA qua *Security → Report a vulnerability* (private) và chỉ ghi task sửa lỗi ở mức chung.
- Task chạm dữ liệu bệnh nhân, phân quyền, audit hoặc AI thì issue ghi rõ kiểm soát cần có (phân quyền, audit, che PII) để reviewer kiểm.

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
