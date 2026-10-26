# ADR-0004: Kubernetes (AKS) làm nền tảng chạy, Azure-first nhưng portable

## Trạng thái
Proposed — chờ chốt sau khi requirements (docs/02-requirements) được duyệt.

## Bối cảnh
- Nhóm đã có hạ tầng Azure; yêu cầu hệ thống production-like và chạy được trên nền tảng khác (NFR-I3).
- Tải có đợt tăng đột biến lúc 07:00 (NFR-S1), AI và document cần scale độc lập (NFR-S4).
- Cần deploy không downtime, rollback ≤ 5 phút (NFR-A7, D2), cô lập mạng giữa service (NFR-SEC7),
  chỉ chạy image đã ký (NFR-SEC6).

## Quyết định
**Azure Kubernetes Service (AKS)** cho mọi workload, vận hành theo GitOps:

| Hạng mục | Lựa chọn | Đáp ứng |
|---|---|---|
| Cluster | 2 cluster: `aks-nonprod` (namespace `dev`, `staging`) và `aks-prod`; node pool `system` / `apps` / `ai` (taint riêng); trải 3 availability zone | NFR-D4, A3, S4 |
| Triển khai | Helm chart chung cho service + **Argo CD** (GitOps); **Argo Rollouts** cho canary có phân tích metric | NFR-A7, D2 |
| Autoscale | HPA (CPU/RPS) + **KEDA** (độ dài hàng đợi, lịch cron scale trước 07:00) + Cluster Autoscaler | NFR-S1–S3 |
| Ingress | Ingress controller (NGINX hoặc Application Gateway for Containers) + cert-manager; WAF ở rìa (Front Door) | NFR-SEC3 |
| Bảo mật | Workload Identity + Key Vault CSI driver; NetworkPolicy mặc định chặn; Pod Security "restricted"; Kyverno chỉ cho chạy image đã ký (cosign) | NFR-SEC4, SEC6, SEC7 |
| mTLS nội bộ | Service mesh nhẹ (Istio ambient hoặc Linkerd); quyết định cuối ở Pha 4 dựa trên chi phí tài nguyên | NFR-SEC3 |
| Độ bền | PodDisruptionBudget, topology spread theo zone, readiness/liveness probe, resource requests/limits | NFR-A1, A3 |
| Observability | OpenTelemetry Collector → Azure Monitor (managed Prometheus + Grafana, App Insights) | NFR-O1–O4 |
| Dữ liệu stateful | **Ngoài cluster**, dùng dịch vụ managed (PostgreSQL Flexible, Cosmos DB for MongoDB vCore, Redis, Blob); local/portable dùng chart tương ứng | NFR-A4, giảm công vận hành |

Mọi phụ thuộc hạ tầng đi qua giao thức chuẩn hoặc abstraction (OCI, PostgreSQL, MongoDB API, OIDC, OTLP,
MassTransit Service Bus ↔ RabbitMQ, `IBlobStore`), nên cùng Helm chart chạy được trên AKS, cluster
K8s khác và `kind` trên máy dev.

## Phương án đã cân nhắc
- **Azure Container Apps**: rẻ, có scale-to-zero, ít vận hành; nhưng hạn chế kiểm soát mạng, policy,
  service mesh và GitOps, khó gọi là production-like, và không portable bằng manifest K8s chuẩn.
- **Một cluster cho mọi môi trường**: rẻ hơn, nhưng sự cố hay thay đổi ở non-prod có thể ảnh hưởng prod (vi phạm NFR-D4).
- **Chạy DB trong cluster (operator)**: portable hơn, nhưng tự gánh backup/HA, không đáng với nhóm 12 tuần.

## Hệ quả
- Chi phí cố định cao hơn Container Apps: cần node pool nhỏ, tắt `aks-nonprod` ngoài giờ, spot node cho
  `dev` (NFR-C1). Ước tính chi tiết ở báo cáo FinOps.
- Cần năng lực vận hành K8s: platform (Argo CD, KEDA, Kyverno, OTel, cert-manager) được dựng ở Pha 4 **trước** khi dev vào làm.
- Số service vẫn giữ theo ADR-0001: production-like nằm ở cách vận hành, không ở số lượng microservice.
