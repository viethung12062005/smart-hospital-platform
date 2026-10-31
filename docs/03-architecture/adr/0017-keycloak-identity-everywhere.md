# ADR-0017: Keycloak là nhà cung cấp danh tính duy nhất cho người dùng ứng dụng, ở mọi môi trường

## Trạng thái
**Accepted (2026-10-31)**: SA quyết định sau khi rà luồng đăng ký và đăng nhập của bệnh nhân. Thay phần "Entra ID ở cloud" trong [ADR-0004](0004-azure-first-portable.md), [ADR-0015](0015-java-for-all-services.md) và [ADR-0016](0016-frontend-web-app.md). Không đổi cách dùng Entra ID để quản trị Azure (xem "Phạm vi").

## Bối cảnh
Thiết kế trước dùng hai nhà cung cấp danh tính (IdP): Keycloak ở local và Microsoft Entra ID ở cloud. Khi mô tả luồng "bệnh nhân mới đăng ký" từ đầu đến cuối, có ba vấn đề:

1. **Trải nghiệm không hợp bệnh nhân Việt Nam.** Bệnh nhân bị chuyển sang trang đăng nhập của Microsoft để tạo tài khoản và đặt mật khẩu, rồi mới quay về web làm hồ sơ. Hai lần "đăng ký" ở hai nơi, giao diện không do bệnh viện kiểm soát. Người dùng Việt Nam quen định danh bằng **số điện thoại** (nhận mã OTP), không quen tài khoản Microsoft.
2. **Entra ID thông thường dành cho người trong tổ chức.** Bệnh nhân bên ngoài cần dịch vụ riêng (Entra External ID) phải cấu hình thêm; thiết kế chưa chốt phần này.
3. **Hai IdP, hai bộ lỗi.** Lỗi liên quan Entra chỉ lộ ở UAT (ADR-0011 đã ghi rủi ro), mỗi thay đổi đăng nhập phải kiểm hai lần.

## Quyết định
**Dùng Keycloak cho toàn bộ người dùng ứng dụng (bệnh nhân và nhân viên) ở local, UAT và prod.** Code backend không đổi vì vẫn là OIDC chuẩn; chỉ đổi nơi đặt IdP và cấu hình.

| Hạng mục | Quyết định |
|---|---|
| Vận hành | Keycloak chạy trong cụm, namespace riêng `shp-iam`, 2 replica ở prod, triển khai bằng GitOps. Realm `shp` quản lý bằng code (`realm-shp.json` đã có cho local). Đặt cùng tên miền với web ở đường dẫn `/idp` (qua Gateway), chỉ HTTPS: không cần CORS và CSP chỉ cho `'self'` |
| Đăng ký bệnh nhân | Tự đăng ký ngay trên trang của Keycloak, giao diện tiếng Việt và tiếng Anh do bệnh viện thiết kế. Định danh đăng nhập là **số điện thoại**, email là tùy chọn. Bước xác minh bằng **mã OTP gửi qua SMS** (xem "Việc phải làm") |
| Mật khẩu bệnh nhân | Tối thiểu 12 ký tự, không bắt buộc ký tự đặc biệt, từ chối mật khẩu nằm trong danh sách bị lộ, không bắt đổi định kỳ (theo NIST SP 800-63B-4). Chống dò: khóa tạm sau 5 lần sai liên tiếp, chặn theo IP |
| Nhân viên | Admin mời qua email; mật khẩu tối thiểu 14 ký tự; **bắt buộc yếu tố thứ hai không phải SMS** (ứng dụng xác thực TOTP hoặc khóa WebAuthn) ở lần đăng nhập đầu. Không có đường bỏ qua MFA |
| Token | Giữ nguyên: Authorization Code + PKCE, access token ≤ 15 phút, claim `roles`, `amr` để kiểm MFA. `fhirUser` vẫn không nằm trong token mà tra qua `identity_links` (ADR C7) |
| Gọi giữa service (`/internal`) và AI thay mặt người dùng | Tài khoản dịch vụ (service account) trong Keycloak với client credentials; AI dùng token exchange RFC 8693. Cách xác thực client: ưu tiên chữ ký bằng token ServiceAccount của Kubernetes nếu phiên bản Keycloak hỗ trợ, nếu không thì client secret trong Key Vault xoay vòng 90 ngày (chốt ở IAM-02) |
| Lưu mật khẩu | **Chỉ Keycloak lưu**, dưới dạng băm chậm có salt (Argon2id, mặc định của Keycloak mới). Ứng dụng nghiệp vụ và các service khác không bao giờ thấy mật khẩu |
| Cơ sở dữ liệu của Keycloak | PostgreSQL riêng cho IAM, không dùng chung với scheduling (cô lập và khôi phục độc lập). Cách xác thực DB chốt ở IAM-01 |

### Phạm vi: Entra ID vẫn dùng ở đâu
Entra ID **không** biến mất khỏi dự án, chỉ không còn là nơi bệnh nhân và nhân viên bệnh viện đăng nhập vào ứng dụng:
- Quản trị Azure: đăng nhập AKS, đăng nhập Argo CD của người vận hành, quyền Azure RBAC.
- Danh tính workload (managed identity) để pod truy cập PostgreSQL, MongoDB, Redis, Service Bus, Storage, AI Search, Key Vault.
- Đăng nhập Grafana quản trị.

## Phương án đã cân nhắc
| Phương án | Kết luận |
|---|---|
| Entra External ID cho bệnh nhân, Keycloak cho local (thiết kế cũ) | Bỏ: trải nghiệm bị giới hạn bởi mức tùy biến của dịch vụ, hai IdP, cấu hình chưa chốt |
| Lai: Entra cho nhân viên, Keycloak cho bệnh nhân | Bỏ: vẫn hai IdP và hai luồng MFA; nhân viên bệnh viện không có sẵn tenant Entra |
| Tự viết đăng nhập và lưu mật khẩu | Bỏ: rủi ro bảo mật quá lớn với dữ liệu y tế, không đủ thời gian |
| Dịch vụ danh tính khác (Auth0, Cognito...) | Bỏ: thêm nhà cung cấp, chi phí theo người dùng, không có lợi thế rõ so với Keycloak |

## Hệ quả
**Tích cực**
- Một IdP, một bộ test, cùng hành vi ở local, UAT và prod. Lỗi đăng nhập lộ ngay trên máy dev.
- Luồng đăng ký liền mạch, tiếng Việt, theo số điện thoại; bệnh viện kiểm soát giao diện và nội dung.
- Không cần tenant Entra riêng cho người dùng ứng dụng, giảm phụ thuộc vào subscription Azure khi phát triển.

**Chi phí và rủi ro (nhóm tự chịu trách nhiệm)**
| Rủi ro | Xử lý |
|---|---|
| Keycloak là hệ thống nhạy cảm nhất, nay do ta vận hành: vá lỗi, sao lưu, HA | Image ghim digest và quét như service khác; PostgreSQL riêng có sao lưu; cập nhật Keycloak vào chu kỳ vá hằng tháng; kiểm thử khôi phục |
| Mật khẩu nay nằm trong phạm vi hệ thống: vi phạm kích hoạt thông báo 72 giờ (Luật BVDLCN) và đánh giá tác động | Cập nhật đánh giá tác động; mã hóa đĩa; băm Argon2id; không ghi mật khẩu vào log; khóa tài khoản và cảnh báo đăng nhập bất thường |
| Giao diện quản trị Keycloak bị tấn công | Không mở ra Internet: chỉ truy cập qua dải IP quản trị hoặc port-forward; tài khoản quản trị riêng có MFA |
| Keycloak không có sẵn SMS OTP | Viết một authenticator mở rộng (SPI) gọi giao diện gửi tin; nhà cung cấp SMS thật chọn sau, môi trường dev dùng nhà cung cấp giả hiển thị mã trong log |
| SMS OTP có chi phí theo tin và có thể bị chuyển hướng SIM | Giới hạn số lần gửi theo SĐT và IP; SMS chỉ dùng để xác minh sở hữu số điện thoại, không dùng làm yếu tố thứ hai của nhân viên |
| Một Keycloak lỗi làm cả hệ thống không đăng nhập được | 2 replica, kiểm tra sức khỏe, KEDA/HPA; chấp nhận không có đường đăng nhập dự phòng bỏ qua MFA (đã ghi ở SRS mục tích hợp) |

## Việc phải làm
Thêm vào kế hoạch (SA): **IAM-01** Keycloak trên cụm (chart, DB, realm as code, ingress, hardening, sao lưu), **IAM-02** tài khoản dịch vụ và token exchange cho `/internal` và AI, **IAM-03** giao diện đăng nhập, đăng ký, quên mật khẩu tiếng Việt, **IAM-04** authenticator SMS OTP và giao diện nhà cung cấp SMS (có bản giả), **IAM-05** đẩy sự kiện đăng nhập, đăng ký, khóa, đổi mật khẩu về audit. Sửa **IAC-16** từ "Entra app registration" thành hạ tầng cho Keycloak. Cập nhật SRS lên v0.96 (UC-01, UC-08, NFR-SEC1, tích hợp), thiết kế chi tiết 07 và 15, ADR-0016.
