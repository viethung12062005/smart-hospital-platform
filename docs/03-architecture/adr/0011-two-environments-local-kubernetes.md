# ADR-0011: Hai môi trường cloud (UAT, prod) và phát triển trên Kubernetes cục bộ

## Trạng thái
Accepted (2026-10-27) — thay thế phần "3 môi trường" của [ADR-0009](0009-cicd-ephemeral-environments.md) §1 và các bảng phê duyệt liên quan.

## Bối cảnh
Nhóm chỉ có 4 người và ngân sách cloud hạn chế. Ba cluster cloud (dev, staging, prod) là quá nhiều cho quy mô này: dev đã có thể chạy hoàn toàn
trên máy cá nhân nếu cung cấp đủ công cụ, và staging chỉ khác UAT ở tên gọi.

## Quyết định

| Môi trường | Chạy ở đâu | Mục đích | Vòng đời |
|---|---|---|---|
| **local** | Máy cá nhân của dev, cụm Kubernetes nhỏ (kind) | Viết code, chạy và test từng service cùng toàn bộ phụ thuộc, bằng **cùng Helm chart** với cloud | Dev tự bật/tắt bất cứ lúc nào |
| **uat** | AKS ephemeral trên Azure | Kiểm thử tích hợp thật trên cloud, nghiệm thu, **load test và failure test**, diễn tập deploy/rollback/backup | Dựng khi cần thử, **hủy ngay khi xong** |
| **prod** | AKS ephemeral trên Azure | **Chỉ khởi động khi demo/bảo vệ** (và buổi diễn tập trước đó) | Dựng trước demo ~1 giờ, hủy ngay sau demo |

Hệ quả cho thiết kế:

1. **Chỉ còn 2 stack Terraform** (`envs/uat`, `envs/prod`). Cả hai đều ephemeral (dựng bằng `env-provision`, hủy bằng `env-destroy`). Tài nguyên thường trực
   chỉ gồm state, ACR và identity (stack `bootstrap`).
2. **Dev không cần cluster cloud.** Bộ công cụ `dev/` trong repo ứng dụng (kind + Helm + Makefile) dựng một cụm nhỏ gồm PostgreSQL, MongoDB (replica set),
   Redis, RabbitMQ, MinIO, Keycloak, OpenSearch (tuỳ chọn), ClamAV, Ollama (tuỳ chọn), MLflow, và chạy service bằng chart `microservice` chung.
   Dev test trên cụm này trước khi tạo PR; CI dùng Testcontainers như đã thiết kế.
3. **UAT là nơi đo NFR.** Load test (NFR-P/S) và failure test (NFR-A) chạy trên UAT. Khi đo hiệu năng, UAT dùng **cùng kích cỡ với prod** (biến `sizing = "prod"`);
   khi chỉ thử tích hợp dùng cỡ nhỏ.
4. **Prod ephemeral có kế hoạch:** vì chỉ dựng cho demo nên mọi trạng thái cần có sẵn trong GitOps và script seed: dựng xong là chạy được, không phụ thuộc dữ liệu cũ.
   Phải **diễn tập dựng prod ít nhất một lần** (W10 hoặc W11) để biết thời gian thực tế, và có sẵn bản dự phòng (ảnh chụp màn hình, video, hoặc UAT) nếu dựng prod trễ.
5. **Luồng phát hành:** merge vào `main` → CI build, quét, ký image → (sau phê duyệt `deploy-uat`) cập nhật `envs/uat` trong GitOps → UAT đạt → (sau phê duyệt `deploy-prod`) cập nhật `envs/prod`.
   Argo CD chỉ đồng bộ cụm nào đang tồn tại.
6. **GitHub Environments** thu gọn: `deploy-uat`, `deploy-prod`, `infra-uat`, `infra-prod`, `infra-uat-destroy`, `infra-prod-destroy`.
7. **Cô lập (NFR-D4):** prod và UAT dùng resource group/subscription riêng và identity riêng.
8. **MLOps:** canary/huấn luyện lại tự động được kiểm chứng ở UAT; ở prod chỉ cần bản đã qua UAT.

## Phương án đã cân nhắc
- **Giữ dev cloud:** tiện cho dev yếu máy, nhưng tốn tiền cố định và vẫn cần local để lặp nhanh.
- **Docker Compose thay vì K8s cục bộ:** nhẹ hơn nhưng khác môi trường thật (không kiểm được chart, probe, NetworkPolicy, KEDA); K8s nhỏ giữ nguyên hình dạng triển khai.
- **minikube / k3d:** tương đương; chọn kind vì phổ biến, khởi động nhanh, dễ dùng trong CI.

## Hệ quả
- Máy dev cần tối thiểu 4 CPU, 16 GB RAM, Docker; có hai profile: `core` (~6 GB, đủ cho phần lớn task) và `full` (thêm OpenSearch, Ollama, ClamAV, MLflow, observability).
- Mỗi dev chạy local nên các bug phụ thuộc cloud (Entra ID, Azure Blob, AI Search thật, Service Bus) chỉ lộ ra ở UAT: SA lên lịch dựng UAT thường xuyên (đề xuất mỗi thứ Năm).
- Chi phí cloud giảm: chỉ trả tiền theo giờ UAT/prod tồn tại.
