# ADR-0009: CI/CD, môi trường ephemeral và cổng phê duyệt

## Trạng thái
> Cập nhật: số môi trường cloud và bảng phê duyệt được thay bởi [ADR-0011](0011-two-environments-local-kubernetes.md) (chỉ UAT và prod). Phần CI/CD, GitOps, Helm, OIDC giữ nguyên.

Accepted (2026-10-26) — đã được Solution Architect/PM duyệt cùng cổng G3.

## Bối cảnh
- Nhóm không duy trì cluster chạy liên tục (chi phí); hạ tầng chỉ dựng khi cần kiểm thử/demo rồi hủy.
- Yêu cầu: mỗi service một pipeline riêng dùng template chung; triển khai bằng Helm; kiểm tra đầy đủ
  trước khi build/deploy; **mọi thay đổi lên môi trường đều phải có phê duyệt rõ ràng**.
- NFR liên quan: D1–D4, A7, SEC4, SEC6, C1.

## Quyết định

### 1. Môi trường ephemeral
- `uat` và `prod` là **2 stack Terraform độc lập, mỗi stack một cluster AKS riêng** (xem ADR-0011), được dựng
  bằng workflow `env-provision` và hủy bằng `env-destroy`. Không môi trường nào chạy thường trực.
- Tài nguyên **thường trực** (rẻ, không chứa dữ liệu bệnh nhân): Terraform state, Azure Container Registry,
  managed identity cho GitHub OIDC. Nằm trong stack `bootstrap`.
- Mỗi stack được gắn tag `shp-env`, `created-at`, `ttl-hours`. Workflow `cost-guard` báo cáo môi trường
  sống quá TTL (chỉ cảnh báo; việc hủy vẫn phải qua phê duyệt).
- Phát triển hằng ngày chạy **local** (docker compose / kind) với cùng Helm chart.

### 2. CI bằng GitHub Actions
- **Reusable workflow** (template chung) cho: Java CI (Maven), Python CI (ai, ml), container (build → scan → SBOM → ký → push),
  Helm validate, Terraform checks, GitOps update/promote.
- **Mỗi service một workflow caller** (`svc-<name>.yml`) lọc theo đường dẫn thư mục service, chỉ chạy khi
  service đó thay đổi.
- Kiểm tra bắt buộc trước khi build image (fail là dừng):
  format/lint → build (warning = error) → unit + integration test có ngưỡng coverage → quét dependency có lỗ hổng →
  SAST (CodeQL) → lint Dockerfile → quét secret (gitleaks).
- Kiểm tra bắt buộc với image: quét lỗ hổng (chặn High/Critical có bản vá), SBOM, ký bằng cosign keyless (OIDC).
- Hạ tầng: `terraform fmt` → `validate` → `tflint` → quét cấu hình bảo mật (Trivy) → `plan` + ước tính chi phí.
- Manifest: `helm lint` → render theo từng môi trường → `kubeconform` (schema) → quét cấu hình bảo mật.

### 3. CD bằng Argo CD (GitOps) và Helm
- Mọi workload (platform add-on và service) được mô tả bằng Helm chart; một **chart chung `microservice`**
  cho tất cả service, mỗi service chỉ có file values theo môi trường.
- Argo CD là cách duy nhất để thay đổi trạng thái cluster; không ai `kubectl apply` hay `helm install`
  tay (trừ bước bootstrap Argo CD trong workflow `env-provision`).
- Deploy = thay đổi tag/digest image trong repo GitOps. Rollback = revert commit.
- `prod` dùng Argo Rollouts canary và **dừng chờ promote thủ công**.

### 4. Cổng phê duyệt (approval gates)
| Hành động | Cơ chế | Người duyệt |
|---|---|---|
| Merge code | PR + CI xanh + CODEOWNERS review | 1 reviewer (service owner hoặc PM) |
| Deploy `uat` | GitHub Environment `deploy-uat` **và** PR vào repo GitOps | DevOps |
| Promote `prod` | GitHub Environment `deploy-prod` (wait timer) **và** PR GitOps **và** promote canary | DevOps + Architect |
| Dựng môi trường | `env-provision`: plan → Environment `infra-<env>` → apply | DevOps |
| Hủy môi trường | `env-destroy`: gõ đúng tên môi trường + Environment `infra-<env>-destroy` | DevOps |

GitHub đăng nhập Azure bằng **OIDC federated credential**, mỗi môi trường một identity riêng.
Không lưu client secret trong GitHub.

## Phương án đã cân nhắc
- **Cluster thường trực cho non-prod**: tiện cho dev nhưng tốn chi phí cố định; nhóm chọn local + ephemeral.
- **Push-based CD (GitHub Actions `helm upgrade`)**: đơn giản hơn, nhưng CI giữ quyền ghi vào cluster,
  không có drift detection, rollback kém minh bạch hơn GitOps.
- **Azure DevOps Pipelines**: tích hợp Azure tốt, nhưng repo đã ở GitHub.

## Hệ quả
- Mỗi lần dựng môi trường tốn khoảng 20–30 phút (AKS + dữ liệu managed); bài test/demo phải lên lịch trước.
- Dữ liệu trong môi trường mất khi hủy, nên cần script seed dữ liệu giả chạy tự động sau bootstrap.
- Key Vault, Cosmos DB và các tài nguyên có soft-delete phải đặt tên có hậu tố ngẫu nhiên, và tắt purge protection
  ở non-prod để dựng lại được.
