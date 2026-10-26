# Ma trận truy vết (Requirements Traceability Matrix)

Truy vết hai chiều: vấn đề nghiệp vụ → yêu cầu → quyết định kiến trúc → kiểm chứng.
Cột "Kiểm chứng" được điền dần ở Pha 4–6 (tên test, kịch bản load test, dashboard).

| Vấn đề (BA) | Yêu cầu | Quyết định kiến trúc (ADR) | Kiểm chứng |
|---|---|---|---|
| BP-1 Đặt lịch quá tải | UC-03, NFR-P1, P2, S1–S3 | ADR-0001 (tách scheduling), ADR-0005 (concurrency + idempotency), ADR-0008 (PostgreSQL + Redis), ADR-0004 (K8s autoscale) | Test AC-03.1/03.2; load test 1×/5×/10× |
| BP-2 No-show cao | UC-07, UC-15, NFR-P8, AI6, A6 | ADR-0007 (ML no-show), event-driven notification (outbox) | Test AC-07.x; báo cáo AUC; failure test provider SMS |
| BP-3 Hồ sơ rời rạc | UC-01, 04, 05, 06, 12, NFR-P3, P4 | ADR-0003 (FHIR store), ADR-0008 (MongoDB cho clinical, Blob cho tài liệu) | Test AC-05.x, 06.x; load test đọc hồ sơ |
| BP-4 Tra cứu phác đồ chậm | UC-11, NFR-P5, AI1–AI3, AI7 | ADR-0006 (LLM router + fallback), ADR-0008 (AI Search) | Báo cáo eval RAG; test prompt injection |
| BP-5 Không có audit | UC-09, UC-10, NFR-SEC2, SEC5 | ADR-0008 (audit WORM + KQL), [ma trận phân quyền](access-control-matrix.md) | Test ma trận phân quyền; demo "ai đã xem hồ sơ X" |
| BP-6 Nhập tay giấy tờ | UC-13, NFR-P7, AI4 | ADR-0006, xử lý bất đồng bộ + dead-letter | Báo cáo F1; failure test OCR |
| Đề bài: backup/recovery | UC-16, NFR-A4, A5 | ADR-0008 (PITR, Blob GZRS/WORM) | Biên bản diễn tập khôi phục |
| Đề bài: cloud ops | NFR-D1–D4, O1–O4, SEC6, SEC7 | ADR-0004 (AKS, GitOps), ADR-0009 (sẽ viết ở Pha 4: CI/CD và môi trường) | Pipeline chạy thật; demo rollback |
| Đề bài: cost awareness | NFR-C1–C3 | ADR-0008 (hệ quả chi phí), ADR-0004 | Báo cáo FinOps |
