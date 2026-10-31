# ADR-0018: Một tài khoản quản lý nhiều hồ sơ, tự nhận hồ sơ đã có, và dịch vụ OTP dùng chung

## Trạng thái
**Accepted (2026-11-01)**: SA quyết định sau khi khảo sát cách các bệnh viện và ứng dụng đặt khám ở Việt Nam làm. Bổ sung cho [ADR-0017](0017-keycloak-identity-everywhere.md) và [ADR-0003](0003-fhir-native-store.md). Thay quy tắc "một tài khoản chỉ gắn một hồ sơ bệnh nhân" (thiết kế chi tiết 15 §8, C7).

## Bối cảnh
Khảo sát công khai (ứng dụng Medpro, Viện Tim TP.HCM, Bạch Mai, Thủ Đức, Tâm Anh và các bài báo; nguồn mỏng, chưa kiểm trong ứng dụng thật) cho thấy ba điều thiết kế trước chưa lo:

1. **Người dùng thường đặt khám hộ.** Cha mẹ đặt cho con, con đặt cho cha mẹ lớn tuổi không dùng điện thoại thông minh; một gia đình có thể dùng chung một số điện thoại. Nhiều ứng dụng cho một tài khoản thêm "hồ sơ người thân". Thiết kế cũ (AC-03.6) chỉ cho đặt lịch cho **chính mình**.
2. **Bệnh nhân cũ đã có hồ sơ ở quầy.** Tâm Anh đối chiếu thông tin rồi gửi OTP để **liên kết tài khoản với hồ sơ có sẵn**. Thiết kế cũ chỉ tạo hồ sơ mới hoặc báo "nghi trùng, đến quầy", dễ sinh hồ sơ trùng.
3. **OTP chỉ qua SMS.** SMS tốn chi phí cao hơn tin xác thực Zalo và có rủi ro đổi SIM. Zalo là ứng dụng nhắn tin phổ biến ở Việt Nam.

## Quyết định

### 1. Mô hình liên kết tài khoản và hồ sơ
- Giữ `identity_links`: mỗi tài khoản có **một** hồ sơ "bản thân" (tối đa một tài khoản "bản thân" cho một hồ sơ). Mọi thư viện hiện có (SHR-21, `fhirUser`) không đổi.
- Thêm `profile_links`: các hồ sơ khác mà tài khoản quản lý. Hai loại:
  - **Người giám hộ** của người **dưới 16 tuổi** (theo Luật Trẻ em 2016): cha, mẹ hoặc người giám hộ cam đoan, toàn quyền, hết hiệu lực khi người đó đủ 16 tuổi.
  - **Người được ủy quyền** cho người lớn: **chỉ khi chính người đó đồng ý**, bằng cách đọc mã OTP gửi tới số của họ trong hồ sơ, hoặc tại quầy với giấy tờ gốc (phù hợp người lớn tuổi không dùng điện thoại thông minh).
- **Phạm vi:** `booking` (xem, đặt, hủy, đổi lịch hẹn và hồ sơ hành chính; mặc định) hoặc `records` (thêm kết quả khám và tài liệu đã phát hành, chỉ khi tin OTP nêu rõ phạm vi để chủ hồ sơ biết trước). Người được ủy quyền không xem ghi chú chưa phát hành, không sửa họ tên, ngày sinh, giới tính và không bật xử lý AI thay chủ hồ sơ.
- **Giới hạn:** tối đa 10 hồ sơ mỗi tài khoản, hiệu lực 12 tháng và gia hạn bằng OTP mới, thu hồi bất kỳ lúc nào với hiệu lực ≤ 60 giây (cùng hạn bộ nhớ đệm quyền).
- **Phân quyền:** thêm điều kiện `linked`, `linked-records`, `guardian` vào ma trận (không đổi `own`). Giới hạn 3 lịch tương lai (BR-03) tính theo từng hồ sơ. Audit mọi truy cập qua liên kết kèm `onBehalfOf`.

### 2. Tự nhận hồ sơ đã có
Bệnh nhân nhập họ tên, ngày sinh, số điện thoại đã đăng ký tại bệnh viện. Nếu đúng **một** hồ sơ khớp, hệ thống gửi OTP tới số **lưu trong hồ sơ** (không phải số vừa nhập nếu khác). Mã đúng và hồ sơ chưa gắn tài khoản "bản thân" nào thì liên kết, rồi báo tới số trong hồ sơ để chủ hồ sơ phát hiện nếu bị mạo nhận.
- Phản hồi **luôn giống nhau** dù có khớp hay không và không lộ dữ liệu nào trước khi OTP đúng, để không dò ai là bệnh nhân của bệnh viện.
- Không khớp, nhiều ứng viên (số điện thoại dùng chung) hoặc hồ sơ không có số điện thoại thì đến quầy để Điều dưỡng liên kết sau khi đối chiếu giấy tờ.
- Giới hạn chống lạm dụng: 5 lần thử mỗi 24 giờ mỗi tài khoản, 3 mã mỗi 24 giờ mỗi số.

### 3. Dịch vụ OTP dùng chung, Zalo trước SMS sau
`notification` cung cấp API nội bộ **N8** (gửi mã) và **N9** (kiểm mã); Keycloak (đăng ký, đổi số) và clinical (nhận hồ sơ, liên kết người thân) cùng gọi. Kênh cho số điện thoại: **Zalo trước**, nếu Zalo lỗi, số không dùng Zalo hoặc quá 30 giây chưa giao thì **SMS với cùng mã**; email dùng kênh email. Mã 6 chữ số, hết hạn 5 phút, dùng một lần, chỉ lưu băm, sai 5 lần khóa 15 phút, giới hạn theo số điện thoại và IP. Mỗi kênh là một adapter sau giao diện `OtpChannel` (có bản giả cho dev và test). OTP **không** làm yếu tố thứ hai của nhân viên (vẫn TOTP hoặc WebAuthn).

## Phương án đã cân nhắc
| Phương án | Kết luận |
|---|---|
| Mỗi người một tài khoản, không có hồ sơ người thân | Bỏ: không hợp thực tế gia đình Việt Nam, người lớn tuổi và trẻ em không tự dùng được |
| Người thân đặt hộ chỉ bằng cách nhập thông tin, không cần đồng ý của chủ hồ sơ | Bỏ: lộ dữ liệu sức khỏe của người khác, trái nguyên tắc đồng ý |
| Gộp mọi hồ sơ trong gia đình vào một hồ sơ chung | Bỏ: sai mô hình dữ liệu y tế, mỗi người một hồ sơ |
| Nhận hồ sơ chỉ tại quầy | Giữ làm đường dự phòng, nhưng thêm đường tự phục vụ bằng OTP để giảm tải quầy và giảm hồ sơ trùng |
| Mỗi chỗ tự gửi OTP (Keycloak một nơi, clinical một nơi) | Bỏ: trùng cấu hình nhà cung cấp, giới hạn và ngân sách; chọn một dịch vụ chung |
| Chỉ SMS | Bỏ: đắt hơn và rủi ro đổi SIM; giữ SMS làm dự phòng |

## Hệ quả
**Tích cực**
- Khớp cách người Việt dùng: đặt khám cho con và cha mẹ, nhận lại hồ sơ cũ mà không đến quầy.
- Ít hồ sơ trùng, ít việc tại quầy, OTP rẻ hơn và quản lý ở một chỗ.

**Rủi ro và cách xử lý**
| Rủi ro | Xử lý |
|---|---|
| Lộ dữ liệu sức khỏe của người thân qua liên kết giả mạo | Mã OTP đến số của chính người thân; phạm vi mặc định chỉ lịch hẹn; thu hồi nhanh; thông báo cho chủ hồ sơ; audit `onBehalfOf` |
| Dò hồ sơ bằng cách thử họ tên, ngày sinh, số điện thoại | Phản hồi trung lập, giới hạn theo tài khoản và số, khóa tạm, cảnh báo bất thường |
| Số điện thoại đã đổi chủ (mã gửi nhầm người) | Chỉ liên kết khi đúng mã; báo tới số trong hồ sơ; hồ sơ có số lạ thì đến quầy |
| Người dùng 16 đến 17 tuổi: ranh giới giữa tự quyết và người giám hộ | Đủ 16 tuổi thì quyền giám hộ chấm dứt, đề nghị chuyển quyền; cần xác nhận lại với Luật bảo vệ dữ liệu cá nhân về trẻ em trước khi vận hành thật |
| Zalo cần tài khoản doanh nghiệp và mẫu tin xác thực; SMS cần nhãn gửi tin | Làm sau bản giả; nhà cung cấp và quy định hiện hành chốt khi triển khai thật |

## Việc phải làm
Thêm user story US-076 đến US-079 và các task CLN-33 đến CLN-38, NTF-16 đến NTF-18, SHR-30, WEB-35, WEB-36, cập nhật IAM-04 (gọi `notification`). Ma trận phân quyền thêm ba điều kiện và các quy tắc cho hồ sơ liên kết. SRS v0.96 (UC claim-record, add-dependent, link-relative, revoke-link; AC-RG.3, AC-RG.4, AC-03.7).
