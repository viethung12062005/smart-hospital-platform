# ADR-0010: Chiến lược repository

## Trạng thái
Accepted (2026-10-26) — đã được Solution Architect/PM duyệt cùng cổng G3.

## Bối cảnh
Nhóm 4 người (1 Solution Architect/PM/DevOps, 3 dev), 8 service, yêu cầu pipeline riêng cho từng service,
template dùng chung, GitOps, phân quyền rõ giữa code ứng dụng, hạ tầng và cấu hình triển khai.

## Quyết định: 4 repository

| Repo | Nội dung | Ai được merge | Lý do tách |
|---|---|---|---|
| `smart-hospital-platform` (repo hiện tại) | Monorepo ứng dụng: `services/*`, `libs/*`, `web/`, `docs/`, workflow caller của từng service | Dev (PR + review) | Ít service, ít người → monorepo giảm chi phí phối hợp; pipeline vẫn tách theo đường dẫn |
| `shp-platform-templates` | Reusable workflows (CI templates) + Helm chart chung `microservice`, phát hành có version (tag `vX.Y.Z`, chart OCI trên ACR) | DevOps | Template có version: thay đổi template không làm vỡ mọi service cùng lúc; service ghim version |
| `shp-infra` | Terraform: `bootstrap/`, `modules/`, `envs/{dev,staging,prod}`; workflow `env-provision`, `env-destroy`, `cost-guard` | DevOps | Quyền tạo/hủy hạ tầng tách khỏi dev; identity Azure chỉ trust repo này |
| `shp-gitops` | Argo CD bootstrap, platform add-on, policy, values từng service theo môi trường | Bot (dev) + DevOps (staging/prod) | Nguồn sự thật của cluster; CI ghi tag image vào đây mà không kích hoạt CI ứng dụng; CODEOWNERS khóa `envs/prod` |

## Phương án đã cân nhắc
- **Một monorepo cho tất cả**: đơn giản nhất, nhưng bot GitOps commit vào cùng repo gây vòng lặp CI, và không
  tách được quyền hạ tầng/prod.
- **Mỗi service một repo (polyrepo)**: đúng kiểu hệ thống lớn, nhưng 8 repo cho 3 dev tạo nhiều chi phí
  (đồng bộ thư viện chung, đổi nhiều repo cho một tính năng); lợi ích chỉ có khi có nhiều team độc lập.

## Hệ quả
- Reusable workflow ở repo private khác chỉ gọi được khi bật "Accessible from repositories owned by the user"
  trong cài đặt Actions của `shp-platform-templates`.
- Cần một GitHub App (bot) có quyền ghi vào `shp-gitops` để CI cập nhật tag image.
