# Quy ước Git và GitHub (ARC-10)

Áp dụng cho cả 4 repo: `smart-hospital-platform`, `shp-platform-templates`, `shp-infra`, `shp-gitops`.
Quy trình làm task, Definition of Ready/Done: [00-workflow.md](00-workflow.md). Chuẩn code: [00-conventions.md](00-conventions.md).

Tóm tắt một dòng: **mỗi task một nhánh tách từ `main`, một PR, tiêu đề `<story> (<task>): <mô tả>`, squash merge.**

## 1. Định danh: story và task

Mọi thay đổi phải gắn với một task trong [backlog](05-plan/backlog.csv). Cột `Stories` của task cho biết phần "story":

| Cột `Stories` của task | Phần story trong tiêu đề | Ví dụ task |
|---|---|---|
| Một hoặc nhiều `US-xxx` | các `US-xxx` đó | `ML-06` thuộc `US-064` |
| `ENABLER` (việc nền tảng, không có user story) | `ENABLER` | `ARC-04`, `SHR-05`, `IAC-03` |

Việc chưa có task (sửa CI, đổi cấu hình, dọn dẹp...) thì **thêm task vào backlog trước** (`tools/plan/tasks_*.py`, chạy `python tools/plan/build.py`), không dùng tiền tố tự đặt.

## 2. Tiêu đề commit và PR

```
<story> (<task>[, <task>...]): <mô tả>
```

| Phần | Quy tắc | Ví dụ |
|---|---|---|
| `<story>` | `US-xxx`; nhiều story cách nhau `, `; dải liên tiếp viết `US-053..US-056`. Không có story thì `ENABLER` | `US-064` · `US-053..US-056` · `ENABLER` |
| `<task>` | ID trong backlog, đúng chữ hoa; nhiều task cách nhau `, `; dải `AI-06..AI-13` | `(ML-06, ML-07)` · `(AI-06..AI-13)` |
| `<mô tả>` | Tiếng Anh, thì hiện tại, bắt đầu bằng động từ thường (`add`, `fix`, `document`...), không chấm cuối | `add no-show scoring consumer` |
| Độ dài | Tiêu đề ≤ 100 ký tự. Chi tiết đưa vào phần thân | |

Đúng:
```
US-064 (ML-06, ML-07): add appointment-booked consumer and no-show scoring
US-053..US-056 (AI-06..AI-13): add RAG assistant with verified citations
ENABLER (ARC-04): add detailed architecture diagrams
ENABLER (SHR-12): keep mongo:8 in Testcontainers on kernel 6.19+
```

Sai (đã từng xuất hiện trên `main`):

| Tiêu đề | Lỗi |
|---|---|
| `AI: knowledge base ingestion ...` · `ML: ...` · `Plan: ...` | Tiền tố tự đặt, không có task |
| `fix(libs/python): ...` · `test(...): ...` · `ci: ...` | Kiểu Conventional Commits, không dùng trong dự án này |
| `CIT: keep mongo:8 in CI ...` | Có nhóm task nhưng không có số task |
| `ARC-04: add ...` | Thiếu phần story (`ENABLER (ARC-04): ...`) |
| `Create reminders on appointment-booked` | Không có story/task |

Kiểm tra bằng biểu thức (dùng cho công cụ kiểm tự động sau này):
```
^(?=.{1,100}$)(ENABLER|US-\d{3}(\.\.US-\d{3})?(, US-\d{3}(\.\.US-\d{3})?)*) \([A-Z]{2,4}-\d{2,3}(\.\.[A-Z]{2,4}-\d{2,3})?(, [A-Z]{2,4}-\d{2,3}(\.\.[A-Z]{2,4}-\d{2,3})?)*\): \S.*$
```

### Thân commit

- Cách tiêu đề một dòng trống. Viết **vì sao** thay đổi (vấn đề, nguyên nhân, lựa chọn); việc "làm gì" đã có trong diff.
- Tiếng Việt hoặc tiếng Anh đều được; mỗi dòng ≤ 100 ký tự.
- Thay đổi chạm dữ liệu sức khỏe, phân quyền hoặc audit: ghi rõ ảnh hưởng bảo mật.
- **Không** thêm dòng ghi công của công cụ (`Co-Authored-By` của bot/AI, `Generated with ...`).

### Commit trong nhánh

Commit trong nhánh dùng cùng định dạng. Sửa theo review thì thêm commit mới (vd. `US-064 (ML-06): address review: validate ageBand`), **không force-push sau khi đã có review** (xem §5).

## 3. Nhánh

| Quy tắc | Giá trị |
|---|---|
| Tên | `<TASK-ID>-mo-ta-ngan` (chữ thường, gạch nối), vd. `ML-06-booked-consumer`. Nhiều task: lấy ID đầu |
| Gốc | **Luôn tách từ `origin/main` mới nhất**: `git fetch origin && git switch -c <nhánh> origin/main` |
| Không xếp chồng | Không tạo nhánh từ nhánh task khác. Task B cần code của task A thì đợi PR của A merge rồi mới tách B từ `main` |
| Hotfix | `hotfix-<mo-ta>`, vẫn qua PR (xem [00-workflow §6](00-workflow.md#6-hotfix)) |
| Sau merge | Nhánh tự xóa trên GitHub; xóa nhánh local: `git branch -d <nhánh>` |

Vì sao không xếp chồng: squash merge tạo commit mới trên `main`; nhánh phía trên vẫn mang commit gốc nên Git không nhận ra là cùng nội dung,
mọi thay đổi sau đó trên `main` ở cùng file đều thành conflict (chuỗi PR #45–#52 đã gặp ở mọi bước).

## 4. Pull request

| Hạng mục | Quy tắc |
|---|---|
| Phạm vi | Một PR cho một task (hoặc một lát của một story); không gộp việc không liên quan |
| Base | Luôn là `main` |
| Tiêu đề | Đúng định dạng §2. **Tiêu đề PR trở thành commit trên `main`** khi squash |
| Mô tả | Theo [template](../.github/pull_request_template.md): task/story, tài liệu thiết kế đã theo, thay đổi, ảnh hưởng hợp đồng, bảo mật, Definition of Done |
| Liên kết | `Closes #<issue>` nếu có issue |
| Trước khi mở | Tài liệu thiết kế đã review/cập nhật trước khi code ([00-workflow §3](00-workflow.md#3-definition-of-ready-được-bắt-đầu-task)); chạy đủ kiểm tra như CI tại máy (Java: `mvn verify`; Python: `ruff`, `mypy`, `pytest` có Docker để chạy test Testcontainers) |
| Draft | Mở dạng Draft nếu chưa xong; chuyển Ready khi đủ Definition of Done |
| Review | 1 review của Code Owner; resolve hết thread; commit mới làm mất approval cũ |

## 5. Cập nhật nhánh theo `main`

| Tình huống | Cách làm |
|---|---|
| Chưa có review | Được rebase: `git fetch origin && git rebase origin/main`, rồi `git push --force-with-lease` |
| Đã có review | Không force-push. Merge `main` vào nhánh: `git merge origin/main`, giải quyết conflict, push thường |
| Conflict ở file sinh tự động (`docs/05-plan/*`, `docs/02-requirements/user-stories.md`) | Giải quyết file nguồn (`tools/plan/tasks_*.py`), rồi chạy lại `python tools/plan/build.py` thay vì sửa tay file sinh |
| Conflict ở `web/package-lock.json` | Không ghép tay; lấy bản của `main` rồi chạy `npm install` trong `web/` nếu `package.json` thật sự đổi |

Luôn dùng `--force-with-lease`, không dùng `--force`. Không bao giờ force-push `main`.

## 6. Merge

- Chỉ **squash merge** (merge commit và rebase merge đã tắt), do SA hoặc reviewer bấm; lịch sử `main` tuyến tính.
- Trước khi bấm: kiểm tra lại tiêu đề squash đúng §2 (GitHub cho sửa ở hộp thoại merge) và rút gọn thân commit còn phần "vì sao".
- Cấu hình repo đề xuất (SA bật trong Settings → General → Pull Requests): *Default commit message* cho squash = **Pull request title and description**,
  để tiêu đề trên `main` luôn là tiêu đề PR đã được review (hiện là "Default to pull request title and commit details": PR một commit lấy tiêu đề commit,
  thân gộp mọi commit).

## 7. Tag và phát hành (repo `shp-platform-templates`)

- Tag có chú thích theo SemVer: `git tag -a vX.Y.Z -m "vX.Y.Z: <tóm tắt>"` trên commit của `main`; tag đẩy lên kích hoạt `bump-consumers`.
- Repo tiêu thụ ghim workflow theo tag (`...python-ci.yml@vX.Y.Z`); nâng pin bằng một PR riêng, tiêu đề theo §2 (task `CIT-*`).

## 8. Ngoại lệ do máy tạo

| Nguồn | Định dạng giữ nguyên | Ghi chú |
|---|---|---|
| Dependabot | `Bump <gói> from <a> to <b> in <thư mục>` | Không sửa tiêu đề; vẫn qua review và CI |
| Workflow `gitops-promote`, `promote-prod`, `bump-consumers` | Định dạng cố định trong workflow | Thay đổi định dạng thì sửa workflow ở `shp-platform-templates` |

Lịch sử trước khi có quy ước task được đánh dấu `BOOTSTRAP:` (đợt chuẩn hóa lịch sử `main` ngày 2026-11-02); không dùng tiền tố này cho commit mới.
