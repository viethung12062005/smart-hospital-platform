# Pha 4 — Platform & DevOps (kế hoạch)

> Trạng thái: **Chưa bắt đầu**, chỉ khởi động sau G3 (kiến trúc được duyệt). Task chi tiết: epic GOV, IAC, CIT, GOP, ENV, OBS trong
> [backlog](../05-plan/backlog.md). Quyết định nền: [ADR-0009](../03-architecture/adr/0009-cicd-ephemeral-environments.md), [ADR-0010](../03-architecture/adr/0010-repository-strategy.md).
> Mọi môi trường cloud là **ephemeral**: dựng → kiểm thử → hủy.
> Cài đặt GitHub từng bước (repo, ruleset, environment, OIDC, bot): [github-setup.md](github-setup.md).
> Mục tiêu: dựng xong **toàn bộ pipeline và môi trường trước** khi đội dev bắt đầu viết service.

## Đầu ra của pha này

| # | Hạng mục | Nội dung | NFR |
|---|---|---|---|
| 1 | Chiến lược repo & nhánh | Monorepo; trunk-based (`main` + nhánh ngắn), PR bắt buộc review + CI xanh; CODEOWNERS theo thư mục | NFR-D1 |
| 2 | Landing zone Azure (Terraform) | Subscription/resource group theo môi trường, VNet hub-spoke, Private Endpoint cho mọi kho dữ liệu, Key Vault, ACR, Log Analytics; remote state trong Storage Account có khóa | NFR-D3, D4, SEC7 |
| 3 | Cluster (Terraform) | Mỗi môi trường một AKS ephemeral: 3 zone, node pool system/apps/ai, Workload Identity, Azure CNI overlay + Cilium | ADR-0004 |
| 4 | Platform add-on (GitOps) | Argo CD, Argo Rollouts, KEDA, cert-manager, ingress, Kyverno, Key Vault CSI, OpenTelemetry Collector, managed Prometheus/Grafana | NFR-A7, SEC6, O1 |
| 5 | CI (GitHub Actions) | Workflow tái sử dụng: build → unit test → SAST → build image → quét lỗ hổng → SBOM → ký cosign → push ACR; chỉ build service có thay đổi | NFR-SEC6, D1 |
| 6 | CD (GitOps) | Repo/thư mục `deploy/` chứa Helm values theo môi trường; CI cập nhật tag image; Argo CD đồng bộ; `uat` và `prod` qua PR có duyệt; canary + rollback tự động theo metric | NFR-A7, D2 |
| 7 | Bảo mật pipeline | Đăng nhập Azure bằng OIDC federated credential (không lưu secret trong GitHub); quyền tối thiểu theo môi trường; GitHub Environments có người duyệt | NFR-SEC4 |
| 8 | Observability nền | Dashboard chuẩn cho mọi service (RED metrics), cảnh báo burn-rate SLO, log không PII | NFR-O1–O4 |
| 9 | Service template ("walking skeleton") | Một service Java mẫu đi trọn pipeline: commit → local K8s → uat → prod (dựng tạm) → rollback → hủy | Gate G4 |
| 10 | **Môi trường dev local** | Bộ công cụ `dev/` (kind + Helm + Makefile): toàn bộ phụ thuộc và service chạy trên cụm K8s nhỏ của máy dev + dữ liệu giả seed tự động. Xem [ADR-0011](../03-architecture/adr/0011-two-environments-local-kubernetes.md) | NFR-SEC9 |
| 11 | FinOps | Tag chi phí + TTL, budget alert, cost-guard báo môi trường sống quá hạn | NFR-C1, C3 |

## Gate G4
Service mẫu đi được từ commit đến `prod` chỉ qua pipeline; rollback trong ≤ 5 phút; image chưa ký bị
cluster từ chối; dashboard hiển thị trace xuyên service. Đạt G4 thì đội dev mới bắt đầu Pha 5.

> Lưu ý vận hành: Terraform chỉ chạy `fmt`/`validate`/`plan` trong CI; `apply` lên Azure thật do
> DevOps chạy có kiểm soát (environment có người duyệt).
