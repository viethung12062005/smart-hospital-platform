# Quy ước Git và GitHub (ARC-10)

Áp dụng cho cả 4 repo: `smart-hospital-platform`, `shp-platform-templates`, `shp-infra`, `shp-gitops`, cho mọi người và mọi công cụ
(kể cả trợ lý AI) tạo commit hay PR. Quy trình task, Definition of Ready/Done: [00-workflow.md](00-workflow.md). Chuẩn code: [00-conventions.md](00-conventions.md).

Tóm tắt: **mỗi task một nhánh tách từ `main` → commit nhỏ đúng định dạng → một PR theo template → review → squash merge.**

Mục lục: [1. Định danh](#1-định-danh-story-và-task) · [2. Commit](#2-commit) · [3. Nhánh](#3-nhánh) · [4. Pull request](#4-pull-request) ·
[5. Review](#5-review) · [6. Cập nhật nhánh và conflict](#6-cập-nhật-nhánh-và-conflict) · [7. Merge và sau merge](#7-merge-và-sau-merge) ·
[8. Thay đổi nhiều repo](#8-thay-đổi-nhiều-repo) · [9. Tag và phát hành](#9-tag-và-phát-hành) · [10. Bảo vệ nhánh và lịch sử](#10-bảo-vệ-nhánh-và-lịch-sử) ·
[11. Bảo mật](#11-bảo-mật) · [12. Issue và label](#12-issue-và-label) · [13. Cấu hình Git trên máy](#13-cấu-hình-git-trên-máy) ·
[14. Ngoại lệ do máy tạo](#14-ngoại-lệ-do-máy-tạo) · [15. Kiểm tra tự động](#15-kiểm-tra-tự-động)

---

## 1. Định danh: story và task

Mọi thay đổi gắn với một task trong [backlog](05-plan/backlog.csv). Cột `Stories` của task cho biết phần "story":

| Cột `Stories` của task | Phần story | Ví dụ task |
|---|---|---|
| Một hoặc nhiều `US-xxx` | các `US-xxx` đó | `ML-06` thuộc `US-064` |
| `ENABLER` (việc nền tảng, không có user story) | `ENABLER` | `ARC-04`, `SHR-05`, `IAC-03` |

Việc chưa có task (sửa CI, đổi cấu hình, dọn dẹp...) thì **thêm task vào backlog trước** (`tools/plan/tasks_*.py`, chạy
`python tools/plan/build.py`) — có thể trong cùng PR. Không dùng tiền tố tự đặt.

## 2. Commit

### 2.1 Tiêu đề

```
<story> (<task>[, <task>...]): <mô tả>
```

| Phần | Quy tắc | Ví dụ |
|---|---|---|
| `<story>` | `US-xxx`; nhiều story cách nhau `, `; dải liên tiếp `US-053..US-056`. Không có story thì `ENABLER` | `US-064` · `US-053..US-056` · `ENABLER` |
| `<task>` | ID trong backlog, chữ hoa; nhiều task cách nhau `, `; dải `AI-06..AI-13`. Không dùng ký tự đại diện (`IAM-*`) | `(ML-06, ML-07)` |
| `<mô tả>` | Tiếng Anh, thì hiện tại, mệnh lệnh, bắt đầu bằng động từ chữ thường (`add`, `fix`, `remove`, `document`...), không chấm cuối | `add no-show scoring consumer` |
| Độ dài | Cả tiêu đề ≤ 100 ký tự; chi tiết đưa vào thân | |

Đúng:
```
US-064 (ML-06, ML-07): add appointment-booked consumer and no-show scoring
US-053..US-056 (AI-06..AI-13): add RAG assistant with verified citations
ENABLER (ARC-04): add detailed architecture diagrams
ENABLER (SHR-12): keep mongo:8 in Testcontainers on kernel 6.19+
```

Sai (đều đã xuất hiện trên `main`):

| Tiêu đề | Lỗi |
|---|---|
| `AI: knowledge base ingestion ...` · `ML: ...` · `Plan: ...` | Tiền tố tự đặt, không có task |
| `fix(libs/python): ...` · `test(...): ...` · `ci: ...` | Kiểu Conventional Commits, không dùng trong dự án này |
| `CIT: keep mongo:8 in CI ...` | Có nhóm task nhưng không có số task |
| `ARC-04: add ...` | Thiếu phần story (`ENABLER (ARC-04): ...`) |
| `Create reminders on appointment-booked` | Không có story/task |
| `ENABLER (IAM-*): ...` | Ký tự đại diện thay vì ID cụ thể |

### 2.2 Thân

- Cách tiêu đề một dòng trống; mỗi dòng ≤ 100 ký tự; tiếng Việt hoặc tiếng Anh.
- Viết **vì sao**: vấn đề, nguyên nhân, lựa chọn đã cân nhắc. "Làm gì" đã có trong diff.
- Chạm dữ liệu bệnh nhân, phân quyền, audit, secret hoặc egress: ghi rõ ảnh hưởng bảo mật.
- Commit nhỏ, tự nó đã đọc được, không có nội dung vô nghĩa (`wip`, `fix`, `update`).
- **Không** thêm dòng ghi công của công cụ (`Co-Authored-By` của bot/AI, `Generated with ...`). Đồng tác giả là **người** thì được:
  `Co-Authored-By: Tên <email>`.

### 2.3 Một commit chứa gì

- **Nguyên tử**: một ý thay đổi; build và test vẫn xanh sau commit đó. Không trộn refactor với thay đổi hành vi, không trộn định dạng lại toàn file với sửa logic.
- File **sinh tự động** (`docs/05-plan/*`, `docs/02-requirements/user-stories.md`, `docs/*/dist/*`) commit cùng với file nguồn đã sinh ra nó,
  sinh bằng công cụ trong `tools/`, không sửa tay.
- `web/package-lock.json` chỉ đổi khi `web/package.json` đổi, bằng `npm install`; không ghép tay.

Không bao giờ commit:

| Loại | Ví dụ |
|---|---|
| Secret, khóa, token, mật khẩu, chuỗi kết nối | `.env`, `*.pem`, `*.key`, `dev/.local/` (đã có trong `.gitignore`) |
| Dữ liệu bệnh nhân thật hoặc ảnh chụp chứa dữ liệu thật | Chỉ dữ liệu giả từ bộ sinh dữ liệu ([00-synthetic-data.md](00-synthetic-data.md)) |
| File tạm, file khóa của trình soạn thảo/Office | `~$*.docx`, `~$*.xlsx`, `.~lock.*`, `*.swp`, `.DS_Store` |
| Sản phẩm build, cache, môi trường ảo | `target/`, `node_modules/`, `__pycache__/`, `.venv/`, `dist/` của code |
| Dữ liệu ML và lần chạy | `services/ml/data/`, `services/ml/runs/` (đã có trong `.gitignore`); chỉ commit bộ sinh và mô tả, không commit dữ liệu |
| File lớn > 5 MB | Ảnh/tài liệu lớn đưa lên nơi lưu ngoài repo hoặc nén lại |

### 2.4 Sửa commit và lịch sử

| Tình huống | Được làm |
|---|---|
| Commit cuối, **chưa push** | `git commit --amend`, `git rebase -i` thoải mái |
| Đã push, **chưa có review** | Được viết lại lịch sử nhánh của mình, push bằng `--force-with-lease` |
| Đã có review | Chỉ thêm commit mới (`<story> (<task>): address review: ...`), không force-push |
| Hook pre-commit/CI báo lỗi | Sửa nguyên nhân; **không** dùng `--no-verify`, không tắt kiểm tra |
| `main` | Không ai viết lại lịch sử (xem §10) |

### 2.5 Revert

Hoàn tác một thay đổi đã merge bằng PR mới, tạo bằng nút **Revert** trên GitHub hoặc `git revert <sha>`. Tiêu đề theo §2.1 với task gốc
(hoặc task sửa lỗi): `US-064 (ML-06): revert no-show scoring consumer (breaks E1 replay)`; thân ghi lý do và commit bị hoàn tác.

### 2.6 Tác giả

- `user.name` là tên thật, `user.email` là email gắn với tài khoản GitHub (để commit hiện đúng người). Không commit bằng tài khoản dùng chung.
- Ký commit (GPG/SSH) **khuyến khích**, chưa bắt buộc. Nếu repo bật ký thì không bỏ qua bằng `--no-gpg-sign`.

## 3. Nhánh

| Quy tắc | Giá trị |
|---|---|
| Tên | `<TASK-ID>-mo-ta-ngan` (chữ thường, gạch nối, ≤ 50 ký tự), vd. `ML-06-booked-consumer`. Nhiều task: lấy ID đầu |
| Gốc | **Luôn tách từ `origin/main` mới nhất**: `git fetch origin && git switch -c <nhánh> origin/main` |
| Không xếp chồng | Không tạo nhánh từ nhánh task khác. Task B cần code của task A thì đợi PR của A merge rồi mới tách B từ `main` |
| Tuổi thọ | Nhánh sống ≤ 5 ngày làm việc; lâu hơn thì tách nhỏ task. Không có nhánh dài hạn (`develop`, `release`) |
| Hotfix | `hotfix-<mo-ta>`, vẫn qua PR, gắn label `hotfix` ([00-workflow §6](00-workflow.md#6-hotfix)) |
| Nhánh sao lưu cá nhân | Chỉ ở máy (`backup/...`), không push |
| Sau merge | Nhánh tự xóa trên GitHub; xóa nhánh local: `git branch -d <nhánh>`; `git fetch --prune` |

Vì sao không xếp chồng: squash merge tạo commit mới trên `main`; nhánh phía trên vẫn mang commit gốc nên Git không nhận ra là cùng nội dung,
và mọi thay đổi sau đó trên `main` ở cùng file đều thành conflict (chuỗi PR #45–#52 đã gặp ở mọi bước).

## 4. Pull request

### 4.1 Mở PR

| Hạng mục | Quy tắc |
|---|---|
| Phạm vi | Một PR cho một task (hoặc một lát của một story); không gộp việc không liên quan |
| Base | Luôn là `main` |
| Tiêu đề | Đúng §2.1. **Tiêu đề PR trở thành commit trên `main`** khi squash |
| Mô tả | Theo [template](../.github/pull_request_template.md), cách viết ở §4.3 |
| Trước khi mở | Tài liệu thiết kế đã review/cập nhật trước khi code ([00-workflow §3](00-workflow.md#3-definition-of-ready-được-bắt-đầu-task)); tự review diff; chạy đủ kiểm tra như CI tại máy (Java: `mvn verify`; Python: `ruff`, `mypy`, `pytest` **có Docker** để test Testcontainers chạy thật) |
| Draft | Mở dạng **Draft** khi chưa đủ Definition of Done; chuyển *Ready for review* khi đủ |
| Người gán | Assignee là tác giả; Reviewer theo CODEOWNERS (GitHub tự gán) |
| Label | Ít nhất một label loại: `enhancement`, `bug`, `documentation`, `dependencies`, `hotfix` (xem §12) |
| Liên kết | `Closes #<issue>` nếu có issue; PR liên quan ở repo khác: link đầy đủ (§8) |

### 4.2 Kích thước

Nhắm ≤ 400 dòng thay đổi (không tính file sinh tự động, lockfile, ảnh). Lớn hơn thì tách theo lát dọc (API → logic → UI) hoặc tách phần chuẩn bị
(refactor, migration) thành PR riêng đi trước. Các PR tách ra **không** xếp chồng: PR sau mở khi PR trước đã merge.

### 4.3 Cách viết mô tả

Viết theo đúng các mục của template, không tự đặt mục khác:

| Mục | Viết gì | Không viết |
|---|---|---|
| Task | ID task, story, mục tài liệu thiết kế đã theo (đường dẫn + §) | |
| Thay đổi | 2–5 gạch đầu dòng: **vì sao** cần, làm gì ở mức hành vi, điểm reviewer cần soi kỹ | Liệt kê lại từng file (diff đã có) |
| Ảnh hưởng hợp đồng | Có/không đổi `contracts/`; có thì nêu API/event/matrix nào | |
| Bảo mật và dữ liệu | Có chạm dữ liệu bệnh nhân, phân quyền, audit, PII, secret, egress không; có thì kiểm soát gì đã thêm | Bỏ trống: ghi "Không ảnh hưởng" kèm lý do |
| Kiểm tra | Lệnh đã chạy và kết quả thật (số test pass, có Docker hay không); việc **chưa** kiểm được và vì sao | "Đã test" chung chung |
| Việc theo sau | Việc cố ý để PR khác, kèm task/issue | |

- Tiếng Việt cho mô tả; tên kỹ thuật, lệnh, đường dẫn giữ nguyên trong `code`. Câu ngắn, gạch đầu dòng.
- Viết cho người chưa theo dõi cuộc trao đổi: không viết tắt nội bộ, không "như đã nói".
- Thay đổi giao diện: ảnh chụp hoặc GIF trước/sau (desktop và mobile), **chỉ dữ liệu giả**.
- Trung thực: test nào bị bỏ qua, phần nào chỉ kiểm trên CI, rủi ro còn lại.
- Không chèn dòng ghi công của công cụ (`Generated with ...`).

### 4.4 Trong lúc PR mở

- CI phải xanh trước khi xin review. CI đỏ do môi trường (runner, mạng) thì ghi rõ trong PR và chạy lại; không bỏ qua check bắt buộc.
- Đổi phạm vi đáng kể thì sửa lại tiêu đề và mô tả cho đúng.
- Sau khi push sửa theo góp ý, bấm *Re-request review*.
- PR không hoạt động 7 ngày: tác giả cập nhật hoặc chuyển về Draft; 14 ngày: đóng, ghi lý do, giữ nhánh nếu còn dùng.
- Đóng PR không merge: luôn ghi lý do và PR/issue thay thế nếu có.
- Không đổi base của PR sang nhánh khác ngoài `main`.

## 5. Review

| Quy tắc | Giá trị |
|---|---|
| Số review | Tối thiểu 1, của Code Owner (CODEOWNERS); `contracts/`, `libs/`, `.github/`, hạ tầng do SA duyệt |
| Thời gian | Reviewer phản hồi trong 1 ngày làm việc |
| Mức góp ý | `blocking:` (phải sửa trước merge) · `suggestion:` (nên sửa, tác giả quyết) · `nit:` (chi tiết nhỏ) · `question:` (hỏi để hiểu) |
| Nội dung | Về code, không về người; kèm lý do hoặc tài liệu tham chiếu; ưu tiên đúng/bảo mật trước phong cách |
| Trả lời | Tác giả trả lời **mọi** thread: sửa thì nêu commit; không sửa thì nêu lý do |
| Resolve | Người mở thread resolve. Tác giả không tự resolve góp ý `blocking:` |
| Approve | Commit mới sau approve làm mất approval cũ (ruleset); cần approve lại |
| Tự merge | Không tự approve PR của mình; SA là ngoại lệ duy nhất theo ruleset và vẫn phải qua PR, CI xanh |

Reviewer kiểm theo thứ tự: đúng thiết kế đã duyệt → phân quyền, dữ liệu, audit → test đủ và có nghĩa → hợp đồng → dễ đọc.

## 6. Cập nhật nhánh và conflict

| Tình huống | Cách làm |
|---|---|
| Chưa có review | Rebase: `git fetch origin && git rebase origin/main`, rồi `git push --force-with-lease` |
| Đã có review | Không force-push. `git merge origin/main`, giải quyết conflict, push thường |
| Conflict ở file sinh tự động (`docs/05-plan/*`, `user-stories.md`) | Giải quyết file nguồn (`tools/plan/tasks_*.py`), chạy lại `python tools/plan/build.py`; không sửa tay file sinh |
| Conflict ở `web/package-lock.json` | Không ghép tay; lấy bản của `main`, chạy `npm install` trong `web/` nếu `package.json` thật sự đổi |
| Conflict ở file nhị phân (`.docx`, `.xlsx`, `.png`) | Sinh lại từ nguồn bằng công cụ trong `tools/`; không chọn bừa một phía |
| Mọi conflict | Sau khi giải quyết: chạy lại toàn bộ kiểm tra (`tools/plan/build.py --check`, `tools/qa/trace.py --check`, `tools/pylib/sync.py --check`, test) |

Luôn dùng `--force-with-lease`, không dùng `--force`. Trước lệnh có thể làm mất việc (`reset`, `checkout --`, `rebase`) chạy `git status` và
`git stash -u` nếu có thay đổi chưa commit.

## 7. Merge và sau merge

- Chỉ **squash merge** (merge commit và rebase merge đã tắt); lịch sử `main` tuyến tính.
- Ai bấm: SA hoặc reviewer đã approve, khi CI xanh, đủ review, mọi thread đã resolve.
- Trước khi bấm: kiểm tiêu đề squash đúng §2.1 (sửa được trong hộp thoại) và rút gọn thân commit còn phần "vì sao".
- Cấu hình repo đề xuất (SA bật ở Settings → General → Pull Requests): *Default commit message* cho squash = **Pull request title and description**.
  Hiện đang là *Pull request title and commit details*: PR một commit lấy tiêu đề commit, thân gộp mọi commit — nguồn gốc của tiêu đề lệch trên `main`.
- Sau merge: nhánh tự xóa; tác giả cập nhật trạng thái task (`tools/plan/status.py` hoặc board); PR thay đổi workflow/template thì phát hành tag (§9).

## 8. Thay đổi nhiều repo

- Mỗi repo một PR, cùng task, cùng tiêu đề phần story/task.
- Mô tả mỗi PR có mục *PR liên quan* với link đầy đủ và **thứ tự merge** (vd. `shp-platform-templates` trước, phát hành tag, rồi PR nâng pin ở repo ứng dụng).
- Không merge PR phụ thuộc trước PR nó cần.

## 9. Tag và phát hành

- Hiện chỉ `shp-platform-templates` phát hành tag (cả 4 repo có ruleset `protect-tags`): `git tag -a vX.Y.Z -m "vX.Y.Z: <tóm tắt>"` trên commit của `main`.
- SemVer: sửa lỗi tương thích → `Z`; thêm input/tính năng tương thích → `Y`; đổi phá vỡ (đổi tên input, bỏ job) → `X`.
- Tag không bao giờ bị di chuyển hay xóa; phát hành sai thì ra tag mới.
- Repo tiêu thụ ghim theo tag (`...python-ci.yml@vX.Y.Z`); nâng pin bằng PR riêng theo §2.1.

## 10. Bảo vệ nhánh và lịch sử

- Ruleset `protect-main` (mọi repo) **luôn ở trạng thái Active**: bắt buộc PR, review, status check `pr-gate`, lịch sử tuyến tính, cấm force-push và xóa `main`.
- Tắt ruleset hoặc viết lại lịch sử `main` chỉ khi có lý do bắt buộc, do SA thực hiện, theo trình tự:
  báo trước cả nhóm → sao lưu (`git branch backup/main-<ngày>`) → thực hiện → **bật lại ruleset ngay** → báo lại cả nhóm cách cập nhật nhánh local
  (`git fetch && git reset --keep origin/main`) và các PR bị GitHub đóng phải mở lại.
- Lịch sử trước khi có quy ước task được đánh dấu `BOOTSTRAP:` (đợt chuẩn hóa ngày 2026-11-02); không dùng tiền tố này cho commit mới.

## 11. Bảo mật

- Lộ secret trong commit (kể cả trên nhánh chưa merge, kể cả đã xóa ở commit sau): coi như đã lộ. **Thu hồi và tạo secret mới ngay**, báo SA,
  sau đó mới dọn lịch sử. Xóa khỏi lịch sử không thay cho việc thu hồi.
- `gitleaks` chạy trong CI; không thêm ngoại lệ để vượt qua nếu chưa được SA duyệt.
- Không dán dữ liệu bệnh nhân thật, token, log có PII vào commit, PR, comment, issue, ảnh chụp.
- Quyền ghi repo: theo vai trò; GitHub App/bot chỉ có quyền tối thiểu cần dùng.

## 12. Issue và label

| Label | Dùng cho |
|---|---|
| `enhancement` | Tính năng, task theo story |
| `bug` | Sửa lỗi (kèm test hồi quy) |
| `documentation` | Chỉ đổi tài liệu |
| `dependencies` | Nâng thư viện (Dependabot tự gắn) |
| `hotfix` | Sửa gấp theo [00-workflow §6](00-workflow.md#6-hotfix) |
| `security` | Thay đổi/sự cố liên quan bảo mật, dữ liệu sức khỏe |
| `blocked` | Đang chờ việc khác; ghi chờ gì trong mô tả |

Issue: tiêu đề nêu vấn đề (không theo định dạng commit), mô tả gồm hiện trạng, mong đợi, cách tái hiện (lỗi) hoặc tiêu chí xong (việc), task/story liên quan.

## 13. Cấu hình Git trên máy

```bash
git config --global user.name  "Họ Tên"
git config --global user.email "<email gắn với GitHub>"
git config --global pull.ff only          # không tạo merge commit ngầm khi pull
git config --global fetch.prune true      # tự dọn nhánh remote đã xóa
git config --global rerere.enabled true   # nhớ cách giải quyết conflict đã làm
git config --global core.autocrlf false   # .gitattributes đã ép LF
```

Windows: chạy công cụ trong `tools/` có thể ghi file với CRLF; trước khi commit kiểm `git diff --ignore-cr-at-eol` — chỉ khác dòng thì `git checkout -- <file>`.

## 14. Ngoại lệ do máy tạo

| Nguồn | Định dạng giữ nguyên | Ghi chú |
|---|---|---|
| Dependabot | `Bump <gói> from <a> to <b> in <thư mục>`, `Update <gói> requirement from <a> to <b> in <thư mục>` | Không sửa tiêu đề; vẫn qua review và CI |
| Workflow `gitops-promote`, `promote-prod`, `bump-consumers` | Định dạng cố định trong workflow | Đổi định dạng thì sửa workflow ở `shp-platform-templates` |
| Nút *Revert* của GitHub | `Revert "<tiêu đề cũ>"` | Sửa lại tiêu đề theo §2.5 trước khi merge |

## 15. Kiểm tra tự động

Biểu thức kiểm tiêu đề commit/PR (dùng cho check trong CI):

```
^(?=.{1,100}$)(ENABLER|US-\d{3}(\.\.US-\d{3})?(, US-\d{3}(\.\.US-\d{3})?)*) \([A-Z]{2,4}-\d{2,3}(\.\.[A-Z]{2,4}-\d{2,3})?(, [A-Z]{2,4}-\d{2,3}(\.\.[A-Z]{2,4}-\d{2,3})?)*\): \S.*$
```

| Đã có | Chưa có (việc theo sau) |
|---|---|
| Ruleset `protect-main`, `protect-tags`; `pr-gate`; `gitleaks`; CODEOWNERS | Check tiêu đề PR theo biểu thức trên (`pr-checks` ở `shp-platform-templates`) |
| `.gitattributes` ép LF | Mẫu `~$*`, `.~lock.*` trong `.gitignore`; gỡ file khóa Office đang nằm trên `main` |
| Template PR | Template issue; label `hotfix`, `security`, `blocked` |
