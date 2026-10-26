# Pha 1 — Phân tích nghiệp vụ (Business Analysis)

> Trạng thái: **Draft — chờ G1**. Bối cảnh bệnh viện dưới đây là **giả định** để có số liệu
> thiết kế; mọi dữ liệu bệnh nhân dùng trong dự án là dữ liệu giả.

## 1. Bối cảnh tổ chức (giả định)

Một bệnh viện đa khoa tư nhân quy mô vừa:

| Chỉ số | Giá trị giả định |
|---|---|
| Số giường nội trú | 300 |
| Số phòng khám ngoại trú (chuyên khoa) | 40 |
| Bác sĩ khám ngoại trú | 120 |
| Điều dưỡng / nhân viên lâm sàng | 250 |
| Lượt khám ngoại trú / ngày | ~1.500 (cao điểm thứ Hai: ~2.200) |
| Bệnh nhân đăng ký trên hệ thống năm đầu | ~50.000 |
| Giờ làm việc ngoại trú | 7:00–17:00, thứ Hai–thứ Bảy |

## 2. Vấn đề nghiệp vụ

| # | Vấn đề hiện tại (as-is) | Hệ quả | Nguyên nhân gốc |
|---|---|---|---|
| BP-1 | Đặt lịch qua điện thoại/quầy, tổng đài quá tải giờ cao điểm | Bệnh nhân chờ lâu, mất khách | Không có kênh tự phục vụ |
| BP-2 | Tỉ lệ bệnh nhân không đến khám (no-show) ~18% | Lãng phí slot của bác sĩ | Nhắc lịch thủ công, không ưu tiên ca có rủi ro |
| BP-3 | Kết quả xét nghiệm và giấy tờ là file rời, giấy | Bác sĩ mất 5–10 phút tìm lịch sử bệnh nhân | Không có hồ sơ tập trung |
| BP-4 | Tra cứu phác đồ và quy trình nội bộ bằng tay | Chậm, thiếu nhất quán giữa các khoa | Tài liệu không được lập chỉ mục |
| BP-5 | Không biết ai đã xem hồ sơ bệnh nhân | Rủi ro pháp lý về dữ liệu sức khỏe (dữ liệu cá nhân nhạy cảm) | Không có audit tập trung |
| BP-6 | Nhập tay dữ liệu từ giấy tờ upload | Sai sót, tốn công điều dưỡng | Không có trích xuất tự động |

## 3. Stakeholder

| Stakeholder | Vai trò trong hệ thống | Mối quan tâm chính | Mức ảnh hưởng |
|---|---|---|---|
| Bệnh nhân | Patient | Đặt lịch nhanh, được nhắc lịch, xem kết quả của mình, quyền riêng tư | Cao |
| Bác sĩ | Doctor | Thấy lịch sử bệnh nhân nhanh, ghi nhận lần khám ít thao tác, tra cứu phác đồ | Cao |
| Điều dưỡng / nhân viên lâm sàng | Nurse | Tiếp đón, đo sinh hiệu, upload giấy tờ, quản lý lịch khám | Cao |
| Quản trị hệ thống | Administrator | Quản lý người dùng/quyền, xem audit, sao lưu/khôi phục | Trung bình |
| Ban giám đốc | (không dùng trực tiếp) | Giảm no-show, chi phí vận hành, tuân thủ pháp luật | Cao |
| Cán bộ bảo vệ dữ liệu (DPO) | (đọc báo cáo audit) | Tuân thủ quy định bảo vệ dữ liệu cá nhân | Cao |
| Nhà cung cấp AI/cloud | Bên ngoài | Phạm vi dữ liệu được gửi đi | — |

## 4. Quy trình nghiệp vụ

### 4.1 As-is: khám ngoại trú
```
Bệnh nhân gọi tổng đài ─► nhân viên ghi sổ/Excel ─► (không nhắc lịch) ─► bệnh nhân đến quầy
─► điều dưỡng tìm hồ sơ giấy ─► bác sĩ khám, ghi giấy ─► kết quả xét nghiệm in giấy, bệnh nhân tự giữ
```

### 4.2 To-be: khám ngoại trú trên nền tảng
```
1. Bệnh nhân tự tìm slot trống theo chuyên khoa/bác sĩ/ngày ─► đặt lịch (app/web hoặc chatbot)
2. Hệ thống xác nhận ngay ─► nhắc lịch T-24h và T-2h; lịch rủi ro no-show cao được nhắc thêm
3. Bệnh nhân đến, điều dưỡng check-in ─► đo sinh hiệu ─► upload giấy tờ mang theo
   └► AI trích xuất trường dữ liệu ─► điều dưỡng xác nhận nếu độ tin cậy thấp
4. Bác sĩ mở hồ sơ ─► xem tóm tắt AI + lịch sử ─► ghi nhận lần khám (encounter), chẩn đoán
   └► hỏi trợ lý tri thức về phác đồ nội bộ (có trích dẫn nguồn)
5. Kết quả xét nghiệm upload vào hồ sơ ─► bệnh nhân nhận thông báo, xem qua link bảo mật ngắn hạn
6. Mọi lần xem/sửa dữ liệu nhạy cảm được ghi audit; DPO xem báo cáo truy cập
```

## 5. Quy tắc nghiệp vụ (business rules)

| ID | Quy tắc |
|---|---|
| BR-01 | Một slot chỉ được đặt bởi một lịch hẹn đang hiệu lực. |
| BR-02 | Bệnh nhân hủy lịch muộn nhất 2 giờ trước giờ khám; sau đó chỉ nhân viên hủy được. |
| BR-03 | Bệnh nhân có tối đa 3 lịch hẹn tương lai đang hiệu lực (chống giữ chỗ). |
| BR-04 | Slot được mở đặt trước tối đa 30 ngày. |
| BR-05 | Bệnh nhân chỉ xem được dữ liệu của chính mình. |
| BR-06 | Bác sĩ chỉ xem được hồ sơ của bệnh nhân đang/đã có lịch hẹn với mình (quan hệ điều trị), trừ khi dùng truy cập khẩn cấp (break-glass). |
| BR-07 | Truy cập khẩn cấp bắt buộc nhập lý do, bị ghi audit và báo cho DPO. |
| BR-08 | Tài liệu lâm sàng chỉ được tải qua link có hiệu lực tối đa 5 phút. |
| BR-09 | Câu trả lời của trợ lý AI phải kèm trích dẫn nguồn; không có nguồn thì không trả lời. AI không đưa ra chẩn đoán thay bác sĩ. |
| BR-10 | Dữ liệu AI trích xuất có độ tin cậy dưới ngưỡng phải được người xác nhận trước khi lưu vào hồ sơ. |
| BR-11 | Hồ sơ bệnh án được lưu trữ dài hạn theo quy định pháp luật (giả định 10 năm, cần xác minh lại văn bản hiện hành). |

## 6. KPI nghiệp vụ (để đánh giá thành công)

| KPI | Hiện tại (giả định) | Mục tiêu sau 6 tháng |
|---|---|---|
| Tỉ lệ đặt lịch tự phục vụ | 0% | ≥ 60% |
| Tỉ lệ no-show | 18% | ≤ 12% |
| Thời gian bác sĩ tìm lịch sử bệnh nhân | 5–10 phút | ≤ 30 giây |
| Thời gian nhập tay giấy tờ / bệnh nhân | ~4 phút | ≤ 1 phút |
| Tỉ lệ truy cập hồ sơ được audit | không đo được | 100% |

## 7. Phạm vi

**Trong phạm vi (MVP 12 tuần):** khám **ngoại trú**: hồ sơ bệnh nhân, lịch bác sĩ, đặt/hủy lịch,
lần khám, upload tài liệu, tìm lịch sử, nhắc lịch, audit, và 4 tính năng AI + 1 mô hình ML.

**Ngoài phạm vi:** nội trú, thanh toán/bảo hiểm, kê đơn và kho dược, PACS/DICOM, tích hợp HIS bên
ngoài, ứng dụng native mobile (dùng PWA thay thế).

## 8. Giả định, ràng buộc, rủi ro

| Loại | Nội dung |
|---|---|
| Ràng buộc | 12 tuần, 5–7 người; hạ tầng Azure nhóm đã có; ngân sách cloud giới hạn (chốt ở NFR); chỉ dữ liệu giả |
| Ràng buộc pháp lý | Dữ liệu sức khỏe là dữ liệu cá nhân nhạy cảm theo pháp luật Việt Nam về bảo vệ dữ liệu cá nhân → cần đồng ý, tối thiểu hóa dữ liệu, ghi nhận truy cập (cần xác minh văn bản hiện hành) |
| Giả định | Bệnh viện có hệ thống định danh nhân viên chuẩn OIDC; bệnh nhân có email hoặc số điện thoại |
| Rủi ro | Dữ liệu y tế bị gửi ra nhà cung cấp AI ngoài → giảm thiểu bằng redact PII và ưu tiên model trong Azure |
| Rủi ro | Phạm vi quá rộng với 12 tuần → ưu tiên MoSCoW ở Pha 2, cắt "Could" nếu chậm |
