# Runbook: cài đặt GitHub cho 4 repo (GOV-01..GOV-06, IAC-03)

> Người thực hiện: SA. Làm một lần ở W1, sau đó mọi thay đổi qua PR. Quyết định gốc: [ADR-0009](../03-architecture/adr/0009-cicd-ephemeral-environments.md), [ADR-0010](../03-architecture/adr/0010-repository-strategy.md).
> **Không đặt secret** (client secret, key, token) vào repo, variable hay workflow: đăng nhập Azure bằng OIDC.

## 1. Tạo repo (GOV-01)

| Repo | Visibility | Nội dung ban đầu |
|---|---|---|
| `smart-hospital-platform` (đã có dưới tên `smart-hospital-platform-scratch`; đổi tên nếu muốn, GitHub tự chuyển hướng) | private | tài liệu, `tools/plan/` hiện có |
| `shp-platform-templates` | private | `.github/workflows/` (reusable), `charts/microservice/`, `CHANGELOG.md` |
| `shp-infra` | private | `bootstrap/`, `modules/`, `envs/{uat,prod}/`, `.github/workflows/` |
| `shp-gitops` | private | `bootstrap/`, `platform/`, `policies/`, `envs/{uat,prod}/`, `services/` |

Nhánh mặc định `main`. Bật cho cả 4 repo: **Secret scanning + Push protection**, **Dependabot alerts**, **Private vulnerability reporting**.

## 2. Ruleset bảo vệ `main` (GOV-02)

| Quy tắc | Giá trị |
|---|---|
| Bắt buộc Pull Request | có; tối thiểu **1 review**; hủy approval khi có commit mới; yêu cầu review của Code Owners |
| Status check bắt buộc | app repo: `ci / <service>` của service bị thay đổi, `pr-checks` (gitleaks, actionlint, dependency review, CodeQL); infra: `terraform-checks`; gitops/templates: `helm-validate` |
| Cấm force-push và xóa nhánh `main` | có |
| Yêu cầu lịch sử tuyến tính | có (squash merge) |
| Bypass | chỉ **GitHub App bot GitOps** trên `shp-gitops` cho đường dẫn `envs/uat/**` |

## 3. CODEOWNERS và template (GOV-03)

```
# smart-hospital-platform
/services/scheduling/   @D1
/services/notification/ @D1
/services/clinical/     @D2
/services/document/     @D2
/services/audit/        @D2
/services/ai/ /services/ml/ @D3
/libs/ /contracts/ /.github/ /docs/03-architecture/ @SA
# shp-infra, shp-gitops, shp-platform-templates: toàn bộ @SA ; shp-gitops/envs/prod/ cần @SA (Architect)
```
(`@D1`, `@D2`, `@D3`, `@SA` thay bằng tài khoản GitHub thật của từng người; không đặt email hay thông tin cá nhân vào file.)

PR template (`.github/pull_request_template.md`): task ID, story, ảnh hưởng hợp đồng API/event (có/không), checklist Definition of Done (test, lint, không secret, tài liệu).

## 4. GitHub Environments (GOV-04)

| Repo | Environment | Reviewer | Wait timer |
|---|---|---|---|
| app / gitops | `deploy-uat` | SA | — |
| app / gitops | `deploy-prod` | SA (Architect) + SA (DevOps) | 10 phút |
| shp-infra | `infra-uat`, `infra-prod` | SA | — |
| shp-infra | `infra-uat-destroy`, `infra-prod-destroy` | SA | 5 phút |

> Khi nhóm chỉ có một người giữ vai trò SA, bật "Prevent self-review" sẽ làm environment không thể duyệt: chấp nhận rủi ro này một cách có chủ đích, hoặc thêm một dev làm người duyệt thứ hai cho `prod` (khuyến nghị cho `prod`).

## 5. Actions (GOV-06, CIT-09)

- `shp-platform-templates` → Settings → Actions → Access: **Accessible from repositories owned by the user** để repo khác gọi reusable workflow và kéo chart.
- Mọi repo: Actions permissions = chỉ cho phép action từ GitHub và danh sách đã ghim; **ghim theo commit SHA**; `GITHUB_TOKEN` mặc định chỉ đọc, workflow khai báo quyền tối thiểu.
- Dependabot cho: github-actions, maven, pip, docker, terraform (app repo: `.github/dependabot.yml`) (GOV-06).

## 6. Đăng nhập Azure bằng OIDC (IAC-03)

Không lưu client secret. Mỗi identity (User-Assigned Managed Identity) có federated credential theo `subject`:

| Identity | Quyền Azure | Federated credential `subject` |
|---|---|---|
| `id-shp-ci-plan` | Reader + đọc state | `repo:<owner>/shp-infra:pull_request` |
| `id-shp-infra-<env>` | Contributor trên resource group của môi trường + User Access Administrator có điều kiện | `repo:<owner>/shp-infra:environment:infra-<env>` (và `infra-<env>-destroy`) |
| `id-shp-acr-push` | AcrPush | `repo:<owner>/smart-hospital-platform:ref:refs/heads/main` |
| `id-shp-gitops-bot` | (không có quyền Azure) | — |

Variables (không phải secret) ở từng repo: `AZURE_TENANT_ID`, `AZURE_SUBSCRIPTION_ID`, `ACR_NAME`, `AZURE_CLIENT_ID_<identity>`.

## 7. Bot GitOps (GOV-05)

GitHub App riêng: quyền **Contents: read/write** và **Pull requests: read/write** chỉ trên `shp-gitops`; không quyền Actions, Secrets, Administration.
App ID và private key lưu ở **Environment secret** của app repo (một nơi duy nhất), CI dùng để mở PR hoặc commit digest image vào `shp-gitops`.
Xoay key mỗi 90 ngày (nhắc trong `cost-guard`/issue định kỳ).

## 8. Kiểm tra sau khi cài (đưa vào ENV-05)

- [ ] Push trực tiếp lên `main` bị chặn ở cả 4 repo
- [ ] PR thiếu status check hoặc thiếu review không merge được
- [ ] Workflow `env-provision` dừng chờ approval ở `infra-uat`
- [ ] Reusable workflow từ `shp-platform-templates` gọi được từ app repo
- [ ] Image không ký bị cluster chặn (GOP-07)
