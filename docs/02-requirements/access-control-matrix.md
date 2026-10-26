# Pha 2 — Ma trận phân quyền

> Đề bài yêu cầu demo "access-control matrix in action". Mỗi ô của bảng là một test tự động
> (NFR-SEC2). Kết hợp RBAC (theo role) với ABAC (theo quan hệ với bệnh nhân).

**Ký hiệu:** ✅ được phép · ❌ không · **Own** = chỉ dữ liệu của chính mình ·
**Care** = chỉ bệnh nhân có quan hệ điều trị (có lịch hẹn/encounter với bác sĩ trong 12 tháng hoặc lịch hẹn tương lai) ·
**BG** = được phép qua break-glass (UC-10) · **Rel** = chỉ tài liệu đã phát hành cho bệnh nhân.

| Tài nguyên / Hành động | Patient | Doctor | Nurse | Admin | DPO |
|---|---|---|---|---|---|
| Patient: đọc | Own | Care / BG | ✅ | ❌ | ❌ |
| Patient: tạo / sửa | Own (thông tin hành chính) | ❌ | ✅ | ❌ | ❌ |
| Patient: tìm kiếm (tên, năm sinh) | ❌ | ✅ | ✅ | ❌ | ❌ |
| Schedule / Slot: xem slot trống | ✅ | ✅ | ✅ | ✅ | ❌ |
| Schedule / Slot: tạo / sửa / chặn | ❌ | ❌ | ✅ | ✅ | ❌ |
| Appointment: đặt | Own | ❌ | ✅ | ❌ | ❌ |
| Appointment: hủy | Own (≥ 2h trước giờ khám) | ❌ | ✅ | ❌ | ❌ |
| Appointment: xem | Own | Của chính bác sĩ | ✅ | ❌ | ❌ |
| Encounter / Observation: đọc | Own (đã kết thúc) | Care / BG | ✅ | ❌ | ❌ |
| Encounter: tạo / check-in | ❌ | Care | ✅ | ❌ | ❌ |
| Condition (chẩn đoán): ghi | ❌ | Care | ❌ | ❌ | ❌ |
| Observation (sinh hiệu): ghi | ❌ | Care | ✅ | ❌ | ❌ |
| Tài liệu: upload | ❌ | Care | ✅ | ❌ | ❌ |
| Tài liệu: tải | Own + Rel | Care / BG | ✅ | ❌ | ❌ |
| Tài liệu: phát hành cho bệnh nhân | ❌ | Care | ❌ | ❌ | ❌ |
| Trợ lý RAG: hỏi | ❌ | ✅ | ✅ | ❌ | ❌ |
| Kho tri thức: quản lý tài liệu | ❌ | ❌ | ❌ | ✅ | ❌ |
| Tóm tắt hồ sơ AI | ❌ | Care / BG | ❌ | ❌ | ❌ |
| Duyệt kết quả trích xuất AI | ❌ | Care | ✅ | ❌ | ❌ |
| Chatbot đặt lịch | Own | ❌ | ❌ | ❌ | ❌ |
| Người dùng / role: quản lý | ❌ | ❌ | ❌ | ✅ | ❌ |
| Audit: xem / xuất báo cáo | ❌ | ❌ | ❌ | ✅ (không xem nội dung lâm sàng) | ✅ |
| Audit: sửa / xóa | ❌ | ❌ | ❌ | ❌ | ❌ |
| Break-glass | ❌ | ✅ (bắt buộc lý do, 60 phút) | ❌ | ❌ | ❌ |
| Sao lưu / khôi phục | ❌ | ❌ | ❌ | ✅ (qua pipeline vận hành, có duyệt) | ❌ |

## Quy tắc thực thi
1. **Mặc định từ chối**: hành động không có trong bảng thì bị từ chối.
2. Quyền kiểm ở **service sở hữu dữ liệu**; gateway chỉ xác thực token, không quyết định quyền.
3. Từ chối trả `403` và **cũng được ghi audit** (phát hiện dò quyền).
4. AI (RAG, tóm tắt, chatbot) gọi các service khác **bằng token của người dùng** (on-behalf-of), nên không có quyền nào rộng hơn người dùng.
5. Service-to-service dùng workload identity và chỉ được gọi các endpoint đã khai báo.
