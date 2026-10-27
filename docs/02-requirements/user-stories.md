# User story

> Trạng thái: **Approved — G2 qua ngày 2026-10-26**.

> Sinh tự động từ `tools/plan/stories.py` và `tools/plan/tasks_*.py` bằng `python3 -I tools/plan/build.py`. Không sửa tay file này: sửa nguồn rồi chạy lại.
> Mỗi story truy về use case ([FR](functional-requirements.md)), API ([thiết kế chi tiết](../03-architecture/detailed-design/README.md)) và các task cụ thể của từng người ([backlog](../05-plan/backlog.md)).

Tổng: **74 story** — Must 49, Should 22, Could 3.

| ID | Story | Vai trò | MoSCoW | UC | Task | Giờ |
|---|---|---|---|---|---|---|
| [US-001](#us-001) | Bệnh nhân tự đăng ký hồ sơ | Bệnh nhân | Must | UC-01 | 5 | 18 |
| [US-002](#us-002) | Bệnh nhân xem và cập nhật hồ sơ của mình | Bệnh nhân | Must | UC-01 | 4 | 16 |
| [US-003](#us-003) | Điều dưỡng đăng ký bệnh nhân tại quầy | Điều dưỡng | Must | UC-01 | 3 | 9 |
| [US-004](#us-004) | Điều dưỡng gộp hồ sơ trùng | Điều dưỡng | Should | UC-01 | 4 | 12 |
| [US-005](#us-005) | Bệnh nhân chọn kênh và thiết bị nhận thông báo | Bệnh nhân | Should | UC-07 | 3 | 10 |
| [US-006](#us-006) | Quản trị viên quản lý nhân sự lâm sàng | Quản trị viên | Must | UC-08 | 2 | 8 |
| [US-007](#us-007) | Quản trị viên liên kết và khóa tài khoản | Quản trị viên | Must | UC-08 | 2 | 7 |
| [US-008](#us-008) | Nhân viên bắt buộc dùng MFA | Quản trị viên | Must | UC-08 | 3 | 10 |
| [US-009](#us-009) | Điều dưỡng tạo lịch làm việc cho bác sĩ | Điều dưỡng | Must | UC-02 | 6 | 20 |
| [US-010](#us-010) | Điều dưỡng đặt mẫu lịch tuần | Điều dưỡng | Must | UC-02 | 2 | 8 |
| [US-011](#us-011) | Hệ thống sinh slot từ mẫu tuần | Hệ thống | Must | UC-02 | 5 | 14 |
| [US-012](#us-012) | Điều dưỡng chặn/bỏ chặn slot | Điều dưỡng | Must | UC-02 | 3 | 11 |
| [US-013](#us-013) | Bác sĩ xem lịch của mình | Bác sĩ | Must | UC-02, UC-03 | 3 | 12 |
| [US-014](#us-014) | Bệnh nhân tìm khung giờ trống | Bệnh nhân | Must | UC-03 | 5 | 18 |
| [US-015](#us-015) | Bệnh nhân đặt lịch khám | Bệnh nhân | Must | UC-03 | 11 | 36 |
| [US-016](#us-016) | Bệnh nhân hủy lịch khám | Bệnh nhân | Must | UC-03 | 3 | 12 |
| [US-017](#us-017) | Bệnh nhân đổi lịch khám | Bệnh nhân | Should | UC-03 | 3 | 12 |
| [US-018](#us-018) | Bệnh nhân xem lịch hẹn của mình | Bệnh nhân | Must | UC-03 | 2 | 8 |
| [US-019](#us-019) | Điều dưỡng đặt/hủy/đổi lịch hộ bệnh nhân | Điều dưỡng | Must | UC-03 | 5 | 18 |
| [US-020](#us-020) | Hệ thống đánh dấu bệnh nhân không đến | Hệ thống | Must | UC-03, UC-15 | 1 | 3 |
| [US-021](#us-021) | Điều dưỡng check-in bệnh nhân | Điều dưỡng | Must | UC-04 | 2 | 7 |
| [US-022](#us-022) | Lịch hẹn chuyển sang hoàn thành khi khám xong | Hệ thống | Must | UC-04 | 2 | 5 |
| [US-023](#us-023) | Lần khám được tạo tự động khi check-in | Hệ thống | Must | UC-04 | 2 | 6 |
| [US-024](#us-024) | Bác sĩ mở lần khám và ghi bệnh sử | Bác sĩ | Must | UC-04 | 3 | 12 |
| [US-025](#us-025) | Điều dưỡng ghi sinh hiệu | Điều dưỡng | Must | UC-04 | 3 | 11 |
| [US-026](#us-026) | Bác sĩ ghi chẩn đoán | Bác sĩ | Must | UC-04 | 3 | 12 |
| [US-027](#us-027) | Bác sĩ ghi dị ứng và bệnh nền | Bác sĩ | Should | UC-04 | 2 | 6 |
| [US-028](#us-028) | Bác sĩ kết thúc lần khám | Bác sĩ | Must | UC-04 | 2 | 7 |
| [US-029](#us-029) | Bác sĩ bổ sung ghi chú sau khi kết thúc | Bác sĩ | Must | UC-04 | 3 | 8 |
| [US-030](#us-030) | Bệnh nhân xem kết quả khám của mình | Bệnh nhân | Should | UC-04, UC-06 | 1 | 3 |
| [US-031](#us-031) | Tìm bệnh nhân | Bác sĩ | Must | UC-06 | 3 | 12 |
| [US-032](#us-032) | Bác sĩ xem timeline bệnh nhân | Bác sĩ | Must | UC-06 | 4 | 15 |
| [US-033](#us-033) | Hệ thống duy trì quan hệ điều trị | Hệ thống | Must | UC-06, UC-10 | 3 | 11 |
| [US-034](#us-034) | Bác sĩ truy cập khẩn cấp (break-glass) | Bác sĩ | Should | UC-10 | 3 | 10 |
| [US-035](#us-035) | Nhân viên tải tài liệu lâm sàng lên | Điều dưỡng | Must | UC-05 | 8 | 26 |
| [US-036](#us-036) | Hệ thống quét mã độc tài liệu | Hệ thống | Must | UC-05 | 2 | 7 |
| [US-037](#us-037) | Người có quyền tải tài liệu bằng link ngắn hạn | Bác sĩ | Must | UC-05 | 5 | 18 |
| [US-038](#us-038) | Bác sĩ phát hành tài liệu cho bệnh nhân | Bác sĩ | Must | UC-05 | 3 | 10 |
| [US-039](#us-039) | Bệnh nhân xem tài liệu đã phát hành | Bệnh nhân | Must | UC-05 | 2 | 7 |
| [US-040](#us-040) | Bác sĩ đánh dấu tài liệu nhập nhầm | Bác sĩ | Could | UC-05 | 2 | 6 |
| [US-041](#us-041) | Bệnh nhân nhận xác nhận và nhắc lịch | Bệnh nhân | Must | UC-07 | 5 | 14 |
| [US-042](#us-042) | Nhắc lịch được hủy khi lịch hẹn bị hủy/đổi | Bệnh nhân | Must | UC-07 | 1 | 3 |
| [US-043](#us-043) | Gửi thông báo bền bỉ khi nhà cung cấp lỗi | Hệ thống | Must | UC-07 | 7 | 25 |
| [US-044](#us-044) | Quản trị viên xử lý nhắc lịch lỗi | Quản trị viên | Should | UC-07 | 2 | 6 |
| [US-045](#us-045) | Bệnh nhân được báo có kết quả mới | Bệnh nhân | Should | UC-07 | 1 | 3 |
| [US-046](#us-046) | Lịch hẹn rủi ro cao được nhắc thêm | Hệ thống | Should | UC-15, UC-07 | 3 | 9 |
| [US-047](#us-047) | Mọi truy cập dữ liệu bệnh nhân đều được ghi audit | Cán bộ DPO | Must | UC-09 | 4 | 12 |
| [US-048](#us-048) | Audit bất biến | Cán bộ DPO | Must | UC-09 | 3 | 11 |
| [US-049](#us-049) | DPO tra cứu ai đã xem hồ sơ | Cán bộ DPO | Must | UC-09 | 3 | 12 |
| [US-050](#us-050) | DPO xuất báo cáo audit và kiểm tra toàn vẹn | Cán bộ DPO | Should | UC-09 | 3 | 12 |
| [US-051](#us-051) | DPO xem xét các lần break-glass | Cán bộ DPO | Should | UC-10 | 2 | 8 |
| [US-052](#us-052) | Quản trị viên quản lý kho tri thức | Quản trị viên | Must | UC-11 | 7 | 26 |
| [US-053](#us-053) | Bác sĩ/điều dưỡng hỏi trợ lý và nhận câu trả lời có trích dẫn | Bác sĩ | Must | UC-11 | 8 | 30 |
| [US-054](#us-054) | Trợ lý kháng prompt injection | Bác sĩ | Must | UC-11 | 3 | 10 |
| [US-055](#us-055) | Trợ lý vẫn hoạt động khi model lỗi | Bác sĩ | Must | UC-11 | 9 | 32 |
| [US-056](#us-056) | Người dùng đánh giá câu trả lời của trợ lý | Bác sĩ | Could | UC-11 | 2 | 7 |
| [US-057](#us-057) | Bộ đánh giá chất lượng RAG | Nhóm dự án | Must | UC-11 | 4 | 13 |
| [US-058](#us-058) | Bác sĩ nhận tóm tắt hồ sơ bệnh nhân | Bác sĩ | Should | UC-12 | 5 | 19 |
| [US-059](#us-059) | Hệ thống trích xuất dữ liệu từ tài liệu tải lên | Hệ thống | Should | UC-13 | 7 | 26 |
| [US-060](#us-060) | Nhân viên duyệt kết quả trích xuất | Điều dưỡng | Should | UC-13 | 4 | 15 |
| [US-061](#us-061) | Bộ đánh giá trích xuất tài liệu | Nhóm dự án | Should | UC-13 | 2 | 7 |
| [US-062](#us-062) | Bệnh nhân đặt lịch qua chatbot | Bệnh nhân | Could | UC-14 | 5 | 19 |
| [US-063](#us-063) | Huấn luyện và đăng ký mô hình dự đoán no-show | Nhóm dự án | Should | UC-15 | 9 | 30 |
| [US-064](#us-064) | Chấm điểm no-show khi đặt lịch | Hệ thống | Should | UC-15 | 6 | 19 |
| [US-065](#us-065) | Theo dõi drift và hiệu năng thực tế của mô hình | Nhóm dự án | Should | UC-15 | 5 | 17 |
| [US-071](#us-071) | Dữ liệu huấn luyện có phiên bản và được kiểm tra | Nhóm dự án | Should | UC-15 | 5 | 15 |
| [US-072](#us-072) | Đánh giá mô hình theo nhóm con và có model card | Nhóm dự án | Should | UC-15 | 2 | 7 |
| [US-073](#us-073) | Triển khai model mới bằng canary tự động | DevOps | Should | UC-15 | 3 | 12 |
| [US-074](#us-074) | Huấn luyện lại tự động khi có drift | Hệ thống | Should | UC-15 | 3 | 12 |
| [US-066](#us-066) | Người dùng đăng nhập và thấy giao diện theo vai trò | Người dùng | Must | UC-08 | 7 | 22 |
| [US-067](#us-067) | Khôi phục dữ liệu về một thời điểm | Quản trị viên | Must | UC-16 | 3 | 12 |
| [US-068](#us-068) | Hệ thống chịu tải mở slot lúc 07:00 | Nhóm dự án | Must | UC-03 | 5 | 19 |
| [US-069](#us-069) | Hệ thống chịu lỗi phụ thuộc | Nhóm dự án | Must |  | 2 | 9 |
| [US-070](#us-070) | Triển khai an toàn, có phê duyệt | DevOps | Must |  | 32 | 101 |

## Độ phủ tiêu chí nghiệm thu của yêu cầu chức năng

Mỗi AC trong [functional-requirements.md](functional-requirements.md) phải thuộc ít nhất một story (script kiểm tra).

| AC | Story |
|---|---|
| AC-01.1 | US-002 |
| AC-01.2 | US-001 |
| AC-01.3 | US-002 |
| AC-02.1 | US-011 |
| AC-02.2 | US-012 |
| AC-02.3 | US-013 |
| AC-03.1 | US-015 |
| AC-03.2 | US-015 |
| AC-03.3 | US-015 |
| AC-03.4 | US-015 |
| AC-03.5 | US-016 |
| AC-03.6 | US-015 |
| AC-04.1 | US-026 |
| AC-04.2 | US-029 |
| AC-05.1 | US-035 |
| AC-05.2 | US-037 |
| AC-05.3 | US-039 |
| AC-06.1 | US-031 |
| AC-06.2 | US-031 |
| AC-07.1 | US-042 |
| AC-07.2 | US-043 |
| AC-08.1 | US-007 |
| AC-08.2 | US-008 |
| AC-09.1 | US-047 |
| AC-09.2 | US-048 |
| AC-10.1 | US-034 |
| AC-11.1 | US-053 |
| AC-11.2 | US-054 |
| AC-11.3 | US-055 |
| AC-12.1 | US-058 |
| AC-12.2 | US-058 |
| AC-12.3 | US-058 |
| AC-13.1 | US-060 |
| AC-13.2 | US-059 |
| AC-14.1 | US-062 |
| AC-14.2 | US-062 |
| AC-14.3 | US-062 |
| AC-15.1 | US-046 |
| AC-15.2 | US-063, US-064 |
| AC-16.1 | US-067 |
| AC-16.2 | US-067 |

## US-001
### Bệnh nhân tự đăng ký hồ sơ — Must

**Là** Bệnh nhân, **tôi muốn** đăng ký thông tin cá nhân sau khi tạo tài khoản, **để** có hồ sơ để đặt lịch khám.

- Use case: UC-01 · API: C1 · AC của FR: AC-01.2

**Tiêu chí nghiệm thu**

1. Given tài khoản mới chưa liên kết hồ sơ, When gửi thông tin hợp lệ, Then tạo Patient với mã BN-YYYY-NNNNNN và liên kết tài khoản
2. Given đã có hồ sơ cùng SĐT + ngày sinh, When đăng ký, Then 422 duplicate-patient-suspected và KHÔNG tạo hồ sơ, không lộ dữ liệu hồ sơ trùng
3. Given SĐT sai định dạng hoặc ngày sinh ở tương lai, When đăng ký, Then 400 validation-failed
4. Given tài khoản đã có hồ sơ, When đăng ký lại, Then 409 already-registered

**Task**

| Task | Việc | Người | Giờ | Tuần |
|---|---|---|---|---|
| CLN-02 | Validator profile cho Patient, Practitioner, PractitionerRole (cardinality, value set) | D2 | 4 | W3 |
| CLN-03 | Patient repository: sinh MRN BN-YYYY-NNNNNN, nameTokens bỏ dấu, chuẩn hóa SĐT E.164 | D2 | 4 | W4 |
| CLN-04 | Phát hiện trùng hồ sơ theo SĐT + ngày sinh | D2 | 2 | W4 |
| CLN-05 | API đăng ký bệnh nhân (C1) + tạo identity_links | D2 | 4 | W5 |
| WEB-27 | Màn hình hồ sơ: đăng ký, xem/sửa hồ sơ của tôi, đăng ký tại quầy | D2 | 4 | W6 |

## US-002
### Bệnh nhân xem và cập nhật hồ sơ của mình — Must

**Là** Bệnh nhân, **tôi muốn** xem và sửa thông tin liên hệ, **để** thông tin luôn đúng và nhận được thông báo.

- Use case: UC-01 · API: C2, C4, C5, C7 · AC của FR: AC-01.1, AC-01.3

**Tiêu chí nghiệm thu**

1. Given đã đăng nhập, When đọc hồ sơ, Then chỉ thấy hồ sơ của mình; đọc hồ sơ người khác trả 404
2. Given sửa telecom/address/contact/communication, When gửi PUT đúng If-Match, Then tạo phiên bản mới, lịch sử xem được
3. Given sửa họ tên/ngày sinh/giới tính, When gửi PUT, Then 403
4. Given If-Match cũ, When PUT, Then 412 version-conflict

**Task**

| Task | Việc | Người | Giờ | Tuần |
|---|---|---|---|---|
| CLN-01 | Bootstrap MongoDB clinical: collections, $jsonSchema validator, mọi index theo thiết kế | D2 | 4 | W4 |
| CLN-06 | API đọc/sửa/lịch sử Patient (C4, C5, C7) với quy tắc trường cho bệnh nhân | D2 | 4 | W5 |
| WEB-27 | Màn hình hồ sơ: đăng ký, xem/sửa hồ sơ của tôi, đăng ký tại quầy | D2 | 4 | W6 |
| CLN-26 | Chạy bộ test ma trận phân quyền cho mọi endpoint clinical | D2 | 4 | W7 |

## US-003
### Điều dưỡng đăng ký bệnh nhân tại quầy — Must

**Là** Điều dưỡng, **tôi muốn** tạo hồ sơ cho bệnh nhân chưa có tài khoản, **để** bệnh nhân vãng lai vẫn được phục vụ.

- Use case: UC-01 · API: C3 · AC của FR: —

**Tiêu chí nghiệm thu**

1. Given thông tin hợp lệ, When Nurse tạo hồ sơ, Then 201 kèm MRN
2. Given nghi trùng, When Nurse tạo không có xác nhận, Then 422; với forceCreate + lý do thì tạo được và ghi audit

**Task**

| Task | Việc | Người | Giờ | Tuần |
|---|---|---|---|---|
| CLN-04 | Phát hiện trùng hồ sơ theo SĐT + ngày sinh | D2 | 2 | W4 |
| CLN-07 | API Nurse tạo hồ sơ (C3) + forceCreate có lý do và audit | D2 | 3 | W5 |
| WEB-27 | Màn hình hồ sơ: đăng ký, xem/sửa hồ sơ của tôi, đăng ký tại quầy | D2 | 4 | W6 |

## US-004
### Điều dưỡng gộp hồ sơ trùng — Should

**Là** Điều dưỡng, **tôi muốn** gộp hai hồ sơ trùng thành một, **để** lịch sử bệnh nhân không bị chia nhỏ.

- Use case: UC-01 · API: C8 · AC của FR: —

**Tiêu chí nghiệm thu**

1. Given hai hồ sơ active, When $merge, Then hồ sơ nguồn active=false và link replaced-by tới hồ sơ đích, phát patient-merged
2. Given một hồ sơ đã bị gộp, When gộp lại, Then 409
3. Given patient-merged, When scheduling/notification/ml/document nhận, Then cập nhật patientId (idempotent)

**Task**

| Task | Việc | Người | Giờ | Tuần |
|---|---|---|---|---|
| NTF-11 | Consumer document-released, no-show-scored, patient-merged | D1 | 3 | W5 |
| CLN-09 | API gộp hồ sơ (C8) + phát patient-merged | D2 | 4 | W6 |
| SCH-26 | Consumer patient-merged: thay patientId trong dữ liệu scheduling (idempotent) | D1 | 2 | W8 |
| DOC-10 | API nội bộ (D8, D9) + consumer patient-merged | D2 | 3 | W9 |

## US-005
### Bệnh nhân chọn kênh và thiết bị nhận thông báo — Should

**Là** Bệnh nhân, **tôi muốn** chọn kênh, ngôn ngữ, giờ yên tĩnh và đăng ký thiết bị, **để** nhận nhắc lịch đúng cách tôi muốn.

- Use case: UC-07 · API: N1, N2, N3, N4 · AC của FR: —

**Tiêu chí nghiệm thu**

1. Given thứ tự kênh push, email, sms, When lưu, Then các lần gửi thử theo thứ tự đó
2. Given kênh ngoài {push, sms, email}, When lưu, Then 400
3. Given token thiết bị mới, When đăng ký, Then upsert theo token, chủ sở hữu lấy từ danh tính, không từ body

**Task**

| Task | Việc | Người | Giờ | Tuần |
|---|---|---|---|---|
| NTF-04 | Logic tùy chọn: thứ tự kênh, ngôn ngữ, khung giờ yên tĩnh (t-2h bỏ qua) | D1 | 3 | W4 |
| NTF-09 | API thiết bị và tùy chọn (N1-N4) | D1 | 4 | W5 |
| NTF-14 | Test ma trận phân quyền + hợp đồng event cho notification | D1 | 3 | W6 |

## US-006
### Quản trị viên quản lý nhân sự lâm sàng — Must

**Là** Quản trị viên, **tôi muốn** tạo bác sĩ/điều dưỡng và chuyên khoa của họ, **để** lịch khám và phân quyền có dữ liệu nền.

- Use case: UC-08 · API: C9, C10 · AC của FR: —

**Tiêu chí nghiệm thu**

1. Given thông tin nhân sự hợp lệ, When Admin tạo Practitioner và PractitionerRole, Then có mã NV-NNNN
2. Given mọi người dùng đăng nhập, When tra danh bạ bác sĩ, Then thấy tên và chuyên khoa, không thấy thông tin liên hệ cá nhân

**Task**

| Task | Việc | Người | Giờ | Tuần |
|---|---|---|---|---|
| CLN-02 | Validator profile cho Patient, Practitioner, PractitionerRole (cardinality, value set) | D2 | 4 | W3 |
| CLN-10 | API Practitioner/PractitionerRole (C9, C10): danh bạ cho mọi người dùng, ghi chỉ Admin | D2 | 4 | W3 |

## US-007
### Quản trị viên liên kết và khóa tài khoản — Must

**Là** Quản trị viên, **tôi muốn** liên kết tài khoản đăng nhập với nhân sự và khóa khi cần, **để** chỉ người được phép mới truy cập hệ thống.

- Use case: UC-08 · API: C21, C2 · AC của FR: AC-08.1

**Tiêu chí nghiệm thu**

1. Given tài khoản và Practitioner khớp role, When Admin liên kết, Then người đó đăng nhập thấy đúng quyền
2. Given tài khoản bị khóa, When gọi API với token còn hạn, Then bị từ chối trong tối đa 60 giây
3. Given role không khớp PractitionerRole.code, When liên kết, Then 422

**Task**

| Task | Việc | Người | Giờ | Tuần |
|---|---|---|---|---|
| SHR-21 | Identity resolver: sub → fhirUser + roles + disabled, cache 60 giây | D1 | 3 | W2 |
| CLN-11 | API /me (C2), quản lý liên kết/khóa tài khoản (C21), API nội bộ identity | D2 | 4 | W3 |

## US-008
### Nhân viên bắt buộc dùng MFA — Must

**Là** Quản trị viên, **tôi muốn** buộc mọi nhân viên xác thực nhiều lớp, **để** tài khoản nhân viên khó bị chiếm đoạt.

- Use case: UC-08 · API: — · AC của FR: AC-08.2

**Tiêu chí nghiệm thu**

1. Given nhân viên đăng nhập không có MFA, When gọi API, Then token không chứa amr=mfa và bị từ chối 403
2. Given bệnh nhân, When đăng nhập, Then không bắt buộc MFA

**Task**

| Task | Việc | Người | Giờ | Tuần |
|---|---|---|---|---|
| LOC-02 | Keycloak realm local | SA | 3 | W2 |
| SHR-20 | JWT bearer cho Entra ID và Keycloak: map claim roles, kiểm amr=mfa với nhân viên | D1 | 3 | W2 |
| IAC-16 | Module identity ứng dụng (Entra ID) | SA | 4 | W6 |

## US-009
### Điều dưỡng tạo lịch làm việc cho bác sĩ — Must

**Là** Điều dưỡng, **tôi muốn** tạo và bật/tắt lịch làm việc của bác sĩ theo chuyên khoa, **để** bệnh nhân có khung giờ để đặt.

- Use case: UC-02 · API: S1, S2, S3, S4 · AC của FR: —

**Tiêu chí nghiệm thu**

1. Given bác sĩ có PractitionerRole đúng chuyên khoa, When tạo Schedule, Then 201 và planningHorizon = hôm nay → +30 ngày
2. Given đã có Schedule active cùng bác sĩ và chuyên khoa, When tạo thêm, Then 409
3. Given chuyên khoa bác sĩ không có role, When tạo, Then 400

**Task**

| Task | Việc | Người | Giờ | Tuần |
|---|---|---|---|---|
| SCH-01 | Migration V001: schedule, schedule_template, slot (+ extension, exclusion constraint) | D1 | 2 | W5 |
| SCH-03 | Schedule repository: tạo/đọc/tìm/sửa với version CAS và history | D1 | 4 | W5 |
| SCH-04 | API Schedule (S1-S4) + kiểm tra Practitioner/chuyên khoa qua interface (bản giả) + quy tắc authz | D1 | 4 | W5 |
| SCH-33 | Nối client thật tới clinical (roles của Practitioner) thay cho bản giả trong SCH-04 | D1 | 2 | W6 |
| WEB-06 | Màn hình quản lý lịch bác sĩ: Schedule và mẫu tuần | D1 | 4 | W7 |
| SCH-29 | Chạy bộ test ma trận phân quyền cho mọi endpoint scheduling | D1 | 4 | W8 |

## US-010
### Điều dưỡng đặt mẫu lịch tuần — Must

**Là** Điều dưỡng, **tôi muốn** khai báo giờ làm việc theo thứ trong tuần, **để** hệ thống tự sinh slot thay vì nhập tay.

- Use case: UC-02 · API: S5, S6 · AC của FR: —

**Tiêu chí nghiệm thu**

1. Given mẫu hợp lệ, When PUT templates, Then lưu toàn bộ mẫu thay thế mẫu cũ
2. Given khoảng giờ không chia hết cho độ dài slot hoặc hai mẫu chồng giờ, When lưu, Then 400

**Task**

| Task | Việc | Người | Giờ | Tuần |
|---|---|---|---|---|
| SCH-05 | Template repository + API S5, S6 với validate (chia hết, không chồng giờ) | D1 | 4 | W6 |
| WEB-06 | Màn hình quản lý lịch bác sĩ: Schedule và mẫu tuần | D1 | 4 | W7 |

## US-011
### Hệ thống sinh slot từ mẫu tuần — Must

**Là** Hệ thống, **tôi muốn** sinh slot cho 30 ngày tới mỗi ngày và theo yêu cầu, **để** luôn có lịch trống trong cửa sổ đặt.

- Use case: UC-02 · API: S7 · AC của FR: AC-02.1

**Tiêu chí nghiệm thu**

1. Given mẫu 08:00–11:30 slot 15 phút, When sinh cho một ngày, Then đúng 14 slot free
2. Given gọi sinh lại cùng khoảng, When thực hiện, Then không tạo trùng (idempotent)
3. Given khoảng ngày vượt hôm nay + 30 hoặc > 31 ngày, When sinh, Then 400
4. Given job 01:00 hằng ngày, When chạy, Then mở rộng cửa sổ đến hôm nay + 30 ngày

**Task**

| Task | Việc | Người | Giờ | Tuần |
|---|---|---|---|---|
| SCH-01 | Migration V001: schedule, schedule_template, slot (+ extension, exclusion constraint) | D1 | 2 | W5 |
| SCH-06 | Bộ sinh slot: múi giờ Asia/Ho_Chi_Minh, ON CONFLICT DO NOTHING | D1 | 4 | W6 |
| SCH-07 | API slots:generate (S7) + giới hạn ngày | D1 | 2 | W7 |
| SCH-08 | Job sinh slot hằng ngày 01:00 (một replica chạy nhờ advisory lock) | D1 | 2 | W7 |
| WEB-07 | Lịch slot dạng tuần: sinh slot, chặn/bỏ chặn; bác sĩ xem lịch của mình | D1 | 4 | W9 |

## US-012
### Điều dưỡng chặn/bỏ chặn slot — Must

**Là** Điều dưỡng, **tôi muốn** chặn slot khi bác sĩ nghỉ, **để** bệnh nhân không đặt vào khung giờ không khám được.

- Use case: UC-02 · API: S9, S10 · AC của FR: AC-02.2

**Tiêu chí nghiệm thu**

1. Given slot đang free, When $block có lý do, Then busy-unavailable
2. Given slot đã có lịch hẹn hiệu lực, When $block, Then 409 slot-has-appointment
3. Given slot busy-unavailable, When $unblock, Then free; slot trạng thái khác thì 409

**Task**

| Task | Việc | Người | Giờ | Tuần |
|---|---|---|---|---|
| SCH-11 | API block/unblock slot (S9, S10) | D1 | 3 | W6 |
| SCH-29 | Chạy bộ test ma trận phân quyền cho mọi endpoint scheduling | D1 | 4 | W8 |
| WEB-07 | Lịch slot dạng tuần: sinh slot, chặn/bỏ chặn; bác sĩ xem lịch của mình | D1 | 4 | W9 |

## US-013
### Bác sĩ xem lịch của mình — Must

**Là** Bác sĩ, **tôi muốn** xem lịch làm việc và lịch hẹn trong ngày của tôi, **để** chuẩn bị cho ca khám.

- Use case: UC-02, UC-03 · API: S2, S3, S6, S13 · AC của FR: AC-02.3

**Tiêu chí nghiệm thu**

1. Given bác sĩ đăng nhập, When tìm Schedule/Appointment, Then bị ép lọc theo chính mình
2. Given bác sĩ gọi tạo/sửa Schedule, When thực hiện, Then 403
3. Given xem lịch hẹn của bác sĩ khác, When gọi, Then 404

**Task**

| Task | Việc | Người | Giờ | Tuần |
|---|---|---|---|---|
| SCH-04 | API Schedule (S1-S4) + kiểm tra Practitioner/chuyên khoa qua interface (bản giả) + quy tắc authz | D1 | 4 | W5 |
| SCH-17 | Đọc/tìm lịch hẹn (S12, S13): ép bộ lọc theo vai trò, tối đa 92 ngày | D1 | 4 | W6 |
| WEB-07 | Lịch slot dạng tuần: sinh slot, chặn/bỏ chặn; bác sĩ xem lịch của mình | D1 | 4 | W9 |

## US-014
### Bệnh nhân tìm khung giờ trống — Must

**Là** Bệnh nhân, **tôi muốn** lọc slot trống theo chuyên khoa, bác sĩ và ngày, **để** chọn được giờ phù hợp.

- Use case: UC-03 · API: S8 · AC của FR: —

**Tiêu chí nghiệm thu**

1. Given bộ lọc specialty và khoảng ngày ≤ 31, When tìm, Then chỉ trả slot free, start ≥ now, sắp theo thời gian, phân trang keyset
2. Given thiếu cả specialty, practitioner, schedule, When tìm, Then 400
3. Given 500 RPS lúc mở slot, When tìm, Then p95 ≤ 300 ms (NFR-P1)

**Task**

| Task | Việc | Người | Giờ | Tuần |
|---|---|---|---|---|
| SCH-09 | Repository tìm slot với keyset pagination (specialty, practitioner, schedule, khoảng ngày) | D1 | 4 | W6 |
| SCH-10 | API tìm slot (S8): ép status/start cho bệnh nhân, 400 khi thiếu bộ lọc hoặc > 31 ngày | D1 | 3 | W7 |
| SCH-27 | Cache Redis cho tìm slot (TTL 5 giây) + invalidate theo event; Redis lỗi → đọc thẳng DB | D1 | 4 | W8 |
| WEB-03 | Màn hình tìm slot trống: bộ lọc chuyên khoa/bác sĩ/ngày, danh sách phân trang | D1 | 4 | W8 |
| SCH-31 | EXPLAIN ANALYZE 4 truy vấn nóng trên dữ liệu cỡ năm 1 + chỉnh index nếu cần | D1 | 3 | W9 |

## US-015
### Bệnh nhân đặt lịch khám — Must

**Là** Bệnh nhân, **tôi muốn** đặt một khung giờ trống, **để** được xác nhận ngay mà không cần gọi điện.

- Use case: UC-03 · API: S11 · AC của FR: AC-03.1, AC-03.2, AC-03.3, AC-03.4, AC-03.6

**Tiêu chí nghiệm thu**

1. Given 1 slot trống và 50 yêu cầu đồng thời, When đặt, Then đúng 1 thành công, 49 nhận 409 slot-unavailable kèm 3 slot gợi ý
2. Given cùng Idempotency-Key và cùng body gửi hai lần, When đặt, Then cả hai nhận cùng một lịch hẹn, chỉ tạo một
3. Given cùng key khác body, When đặt, Then 422 idempotency-key-reused
4. Given bệnh nhân đã có 3 lịch hẹn tương lai, When đặt thêm, Then 422 max-future-appointments
5. Given slot ngoài 30 ngày, When đặt, Then 422 outside-booking-window
6. Given đặt cho bệnh nhân khác, When Patient gọi, Then 403
7. Given đặt thành công, When commit, Then slot busy, ghi lịch sử, phát appointment-booked trong cùng transaction
8. Given 50 RPS, When đặt, Then p95 ≤ 800 ms, lỗi 5xx < 0,1% (NFR-P2)

**Task**

| Task | Việc | Người | Giờ | Tuần |
|---|---|---|---|---|
| SHR-28 | Idempotency-Key middleware cho PostgreSQL (claim, request hash, replay) | D1 | 4 | W2 |
| SCH-02 | Migration V002: appointment, resource_history, outbox, idempotency_key, inbox | D1 | 2 | W5 |
| SCH-12 | $book bước 1: claim Idempotency-Key, advisory lock theo bệnh nhân, kiểm BR-03 | D1 | 4 | W6 |
| SCH-13 | $book bước 2: compare-and-set slot, BR-04, tạo appointment, history, outbox appointment-booked | D1 | 4 | W7 |
| SCH-14 | API $book (S11): parse Parameters, kiểm patient=own, gợi ý 3 slot khi 409 | D1 | 4 | W7 |
| SCH-15 | Test idempotency end-to-end: cùng key+body, cùng key khác body | D1 | 3 | W7 |
| SCH-16 | Test đồng thời: 50 yêu cầu đặt một slot (Testcontainers) | D1 | 3 | W7 |
| SCH-23 | Relay outbox: bổ sung ageBand từ clinical (cache 24 giờ, lỗi → unknown) | D1 | 3 | W8 |
| SCH-28 | Job dọn dẹp: outbox đã phát >7 ngày, idempotency >24h, slot free quá khứ >90 ngày | D1 | 2 | W8 |
| SCH-30 | Test hợp đồng cho event E1-E5 (producer sinh đúng schema) | D1 | 3 | W9 |
| WEB-04 | Màn hình đặt lịch: xác nhận, xử lý 409 (gợi ý slot), 422 BR-03/BR-04, chống bấm đúp | D1 | 4 | W9 |

## US-016
### Bệnh nhân hủy lịch khám — Must

**Là** Bệnh nhân, **tôi muốn** hủy lịch khám khi không đến được, **để** nhường slot cho người khác.

- Use case: UC-03 · API: S14 · AC của FR: AC-03.5

**Tiêu chí nghiệm thu**

1. Given còn ≥ 2 giờ, When Patient hủy, Then cancelled, slot về free, phát appointment-cancelled
2. Given còn < 2 giờ, When Patient hủy, Then 422 cancellation-too-late; Nurse hủy được
3. Given lịch hẹn đã arrived hoặc cancelled, When hủy, Then 409 invalid-state-transition
4. Given hủy lịch của người khác, When Patient gọi, Then 403

**Task**

| Task | Việc | Người | Giờ | Tuần |
|---|---|---|---|---|
| SCH-18 | API $cancel (S14): BR-02, trả slot về free, outbox appointment-cancelled | D1 | 4 | W8 |
| SCH-29 | Chạy bộ test ma trận phân quyền cho mọi endpoint scheduling | D1 | 4 | W8 |
| WEB-05 | Màn hình lịch hẹn của tôi: xem, hủy (cảnh báo <2h), đổi lịch | D1 | 4 | W9 |

## US-017
### Bệnh nhân đổi lịch khám — Should

**Là** Bệnh nhân, **tôi muốn** chuyển sang khung giờ khác trong một thao tác, **để** không mất chỗ cũ nếu đổi thất bại.

- Use case: UC-03 · API: S15 · AC của FR: —

**Tiêu chí nghiệm thu**

1. Given slot mới trống, When đổi lịch, Then lịch mới booked, lịch cũ cancelled (rescheduled) trong cùng transaction, phát appointment-rescheduled
2. Given slot mới đã bị đặt, When đổi, Then 409 và lịch cũ giữ nguyên
3. Given lịch cũ còn < 2 giờ, When Patient đổi, Then 422

**Task**

| Task | Việc | Người | Giờ | Tuần |
|---|---|---|---|---|
| SCH-29 | Chạy bộ test ma trận phân quyền cho mọi endpoint scheduling | D1 | 4 | W8 |
| SCH-19 | API $reschedule (S15) nguyên tử: đặt mới + hủy cũ trong một transaction | D1 | 4 | W9 |
| WEB-05 | Màn hình lịch hẹn của tôi: xem, hủy (cảnh báo <2h), đổi lịch | D1 | 4 | W9 |

## US-018
### Bệnh nhân xem lịch hẹn của mình — Must

**Là** Bệnh nhân, **tôi muốn** xem các lịch hẹn sắp tới và đã qua, **để** nắm lịch khám của mình.

- Use case: UC-03 · API: S12, S13 · AC của FR: —

**Tiêu chí nghiệm thu**

1. Given bệnh nhân đăng nhập, When tìm Appointment, Then bị ép patient = mình, sắp theo ngày, phân trang
2. Given đọc lịch hẹn của người khác, When Patient gọi, Then 404

**Task**

| Task | Việc | Người | Giờ | Tuần |
|---|---|---|---|---|
| SCH-17 | Đọc/tìm lịch hẹn (S12, S13): ép bộ lọc theo vai trò, tối đa 92 ngày | D1 | 4 | W6 |
| WEB-05 | Màn hình lịch hẹn của tôi: xem, hủy (cảnh báo <2h), đổi lịch | D1 | 4 | W9 |

## US-019
### Điều dưỡng đặt/hủy/đổi lịch hộ bệnh nhân — Must

**Là** Điều dưỡng, **tôi muốn** đặt, hủy, đổi lịch cho bệnh nhân tại quầy, **để** bệnh nhân không dùng được ứng dụng vẫn được phục vụ.

- Use case: UC-03 · API: S11, S14, S15, S12, S13, S17 · AC của FR: —

**Tiêu chí nghiệm thu**

1. Given Nurse đặt cho bất kỳ bệnh nhân, When đặt, Then channel=staff được ép
2. Given lịch hẹn bất kỳ, When Nurse hoặc Admin xem _history, Then thấy mọi phiên bản (ai, khi nào)
3. Given Nurse hủy lịch còn < 2 giờ, When hủy, Then thành công
4. Given Nurse hủy lịch arrived, When hủy với reason=other, Then thành công

**Task**

| Task | Việc | Người | Giờ | Tuần |
|---|---|---|---|---|
| SCH-17 | Đọc/tìm lịch hẹn (S12, S13): ép bộ lọc theo vai trò, tối đa 92 ngày | D1 | 4 | W6 |
| SCH-21 | API _history cho Appointment (S17) | D1 | 2 | W6 |
| SCH-18 | API $cancel (S14): BR-02, trả slot về free, outbox appointment-cancelled | D1 | 4 | W8 |
| SCH-19 | API $reschedule (S15) nguyên tử: đặt mới + hủy cũ trong một transaction | D1 | 4 | W9 |
| WEB-08 | Màn hình quầy: đặt hộ, hủy/đổi hộ, check-in | D1 | 4 | W9 |

## US-020
### Hệ thống đánh dấu bệnh nhân không đến — Must

**Là** Hệ thống, **tôi muốn** đánh dấu no-show khi quá giờ khám, **để** slot và thống kê phản ánh đúng thực tế.

- Use case: UC-03, UC-15 · API: — · AC của FR: —

**Tiêu chí nghiệm thu**

1. Given lịch booked có end + 30 phút < now, When job 5 phút chạy, Then noshow và phát appointment-status-changed
2. Given nhiều replica chạy job, When quét, Then không xử lý trùng (SKIP LOCKED)

**Task**

| Task | Việc | Người | Giờ | Tuần |
|---|---|---|---|---|
| SCH-24 | Job no-show mỗi 5 phút (SKIP LOCKED) + event status-changed | D1 | 3 | W8 |

## US-021
### Điều dưỡng check-in bệnh nhân — Must

**Là** Điều dưỡng, **tôi muốn** ghi nhận bệnh nhân đã đến, **để** bác sĩ biết bệnh nhân đang chờ.

- Use case: UC-04 · API: S16 · AC của FR: —

**Tiêu chí nghiệm thu**

1. Given lịch booked trong khoảng -60/+30 phút quanh giờ hẹn, When $check-in, Then arrived, phát appointment-checked-in
2. Given ngoài khoảng, When check-in, Then 422 checkin-out-of-window
3. Given không phải Nurse, When check-in, Then 403

**Task**

| Task | Việc | Người | Giờ | Tuần |
|---|---|---|---|---|
| SCH-20 | API $check-in (S16): cửa sổ -60/+30 phút, chỉ Nurse, outbox appointment-checked-in | D1 | 3 | W7 |
| WEB-08 | Màn hình quầy: đặt hộ, hủy/đổi hộ, check-in | D1 | 4 | W9 |

## US-022
### Lịch hẹn chuyển sang hoàn thành khi khám xong — Must

**Là** Hệ thống, **tôi muốn** đồng bộ trạng thái lịch hẹn với lần khám, **để** dữ liệu lịch hẹn và lâm sàng nhất quán.

- Use case: UC-04 · API: S18 · AC của FR: —

**Tiêu chí nghiệm thu**

1. Given nhận encounter-finished, When xử lý, Then Appointment arrived → fulfilled và phát appointment-status-changed
2. Given nhận event trùng hoặc đến muộn, When xử lý, Then bỏ qua (inbox, version)

**Task**

| Task | Việc | Người | Giờ | Tuần |
|---|---|---|---|---|
| SCH-22 | API nội bộ đọc lịch hẹn (S18) với service token | D1 | 2 | W7 |
| SCH-25 | Consumer encounter-finished → Appointment fulfilled (inbox, bỏ qua event cũ) | D1 | 3 | W8 |

## US-023
### Lần khám được tạo tự động khi check-in — Must

**Là** Hệ thống, **tôi muốn** tạo Encounter từ sự kiện check-in, **để** bác sĩ có sẵn bệnh án để ghi.

- Use case: UC-04 · API: — · AC của FR: —

**Tiêu chí nghiệm thu**

1. Given nhận appointment-checked-in, When xử lý, Then tạo Encounter arrived gắn appointmentId
2. Given nhận event hai lần, When xử lý, Then chỉ có một Encounter (unique appointmentId)

**Task**

| Task | Việc | Người | Giờ | Tuần |
|---|---|---|---|---|
| CLN-14 | Consumer appointment-checked-in → tạo Encounter (unique appointmentId) | D2 | 3 | W6 |
| CLN-27 | Test hợp đồng event E1-E7 cho clinical (consumer đọc được ví dụ, producer sinh đúng schema) | D2 | 3 | W8 |

## US-024
### Bác sĩ mở lần khám và ghi bệnh sử — Must

**Là** Bác sĩ, **tôi muốn** bắt đầu lần khám và ghi lý do khám, bệnh sử, **để** hồ sơ phản ánh quá trình khám.

- Use case: UC-04 · API: C11, C12, C13 · AC của FR: —

**Tiêu chí nghiệm thu**

1. Given Encounter arrived của bác sĩ đó, When $start, Then in-progress, attender = bác sĩ
2. Given không phải bác sĩ của lịch hẹn, When $start, Then 403
3. Given Encounter finished, When sửa ghi chú, Then 409 invalid-state-transition

**Task**

| Task | Việc | Người | Giờ | Tuần |
|---|---|---|---|---|
| CLN-15 | API Encounter: $start, đọc, tìm, sửa ghi chú (C11-C13), chặn sửa khi finished | D2 | 4 | W7 |
| CLN-26 | Chạy bộ test ma trận phân quyền cho mọi endpoint clinical | D2 | 4 | W7 |
| WEB-22 | Form lần khám: bắt đầu, lý do, bệnh sử, sinh hiệu (Nurse) | D2 | 4 | W9 |

## US-025
### Điều dưỡng ghi sinh hiệu — Must

**Là** Điều dưỡng, **tôi muốn** ghi mạch, huyết áp, nhiệt độ, SpO2, cân nặng, chiều cao, **để** bác sĩ có dữ liệu ban đầu.

- Use case: UC-04 · API: C16 · AC của FR: —

**Tiêu chí nghiệm thu**

1. Given giá trị trong khoảng hợp lý và đúng đơn vị, When ghi, Then 201, interpretation do server tính
2. Given giá trị ngoài khoảng (ví dụ nhiệt độ 50 °C), When ghi, Then 422 value-out-of-range
3. Given sinh hiệu không gắn Encounter, When ghi, Then 400

**Task**

| Task | Việc | Người | Giờ | Tuần |
|---|---|---|---|---|
| CLN-16 | Validator Observation: LOINC, đơn vị UCUM, khoảng hợp lý, tính interpretation | D2 | 4 | W3 |
| CLN-17 | API Observation (C16): ghi/đọc/tìm, ràng buộc Encounter và quyền | D2 | 3 | W7 |
| WEB-22 | Form lần khám: bắt đầu, lý do, bệnh sử, sinh hiệu (Nurse) | D2 | 4 | W9 |

## US-026
### Bác sĩ ghi chẩn đoán — Must

**Là** Bác sĩ, **tôi muốn** ghi chẩn đoán ICD-10 cho lần khám, **để** bệnh án có kết luận.

- Use case: UC-04 · API: C17 · AC của FR: AC-04.1

**Tiêu chí nghiệm thu**

1. Given bác sĩ có quan hệ điều trị, When ghi Condition với ICD-10 hợp lệ, Then 201, recorder = bác sĩ
2. Given bác sĩ không có quan hệ điều trị (kể cả đang break-glass), When ghi, Then 403 no-care-relationship
3. Given mã ICD-10 không tồn tại, When ghi, Then 422 invalid-code
4. Given chẩn đoán sai, When sửa verificationStatus = entered-in-error, Then lưu giữ, không xóa

**Task**

| Task | Việc | Người | Giờ | Tuần |
|---|---|---|---|---|
| CLN-18 | API Condition (C17) + bảng ICD-10 + quy tắc recorder phải có Care | D2 | 4 | W7 |
| CLN-26 | Chạy bộ test ma trận phân quyền cho mọi endpoint clinical | D2 | 4 | W7 |
| WEB-23 | Form chẩn đoán/dị ứng, kết thúc lần khám, bổ sung addendum | D2 | 4 | W10 |

## US-027
### Bác sĩ ghi dị ứng và bệnh nền — Should

**Là** Bác sĩ, **tôi muốn** ghi dị ứng và danh sách bệnh nền của bệnh nhân, **để** mọi lần khám sau đều thấy cảnh báo.

- Use case: UC-04 · API: C18 · AC của FR: —

**Tiêu chí nghiệm thu**

1. Given thông tin dị ứng, When ghi, Then 201; điều dưỡng cũng ghi được dị ứng
2. Given đọc dị ứng, When bác sĩ có Care hoặc bệnh nhân của mình, Then thấy; người khác không thấy

**Task**

| Task | Việc | Người | Giờ | Tuần |
|---|---|---|---|---|
| CLN-19 | API AllergyIntolerance (C18) | D2 | 2 | W8 |
| WEB-23 | Form chẩn đoán/dị ứng, kết thúc lần khám, bổ sung addendum | D2 | 4 | W10 |

## US-028
### Bác sĩ kết thúc lần khám — Must

**Là** Bác sĩ, **tôi muốn** kết thúc lần khám sau khi ghi chẩn đoán, **để** lịch hẹn được hoàn thành và dữ liệu khóa lại.

- Use case: UC-04 · API: C14 · AC của FR: —

**Tiêu chí nghiệm thu**

1. Given có ≥ 1 chẩn đoán encounter-diagnosis, When $finish, Then finished, period.end, phát encounter-finished
2. Given chưa có chẩn đoán, When $finish, Then 422 diagnosis-required

**Task**

| Task | Việc | Người | Giờ | Tuần |
|---|---|---|---|---|
| CLN-20 | API $finish (C14) + phát encounter-finished | D2 | 3 | W7 |
| WEB-23 | Form chẩn đoán/dị ứng, kết thúc lần khám, bổ sung addendum | D2 | 4 | W10 |

## US-029
### Bác sĩ bổ sung ghi chú sau khi kết thúc — Must

**Là** Bác sĩ, **tôi muốn** thêm bổ sung vào lần khám đã kết thúc, **để** sửa sai sót mà không làm mất bản gốc.

- Use case: UC-04 · API: C15 · AC của FR: AC-04.2

**Tiêu chí nghiệm thu**

1. Given Encounter finished, When $addendum, Then thêm extension addendum (tác giả, thời điểm), bản gốc không đổi
2. Given Encounter chưa finished, When $addendum, Then 409

**Task**

| Task | Việc | Người | Giờ | Tuần |
|---|---|---|---|---|
| CLN-21 | API $addendum (C15) | D2 | 2 | W7 |
| CLN-25 | Dựng dữ liệu ICD-10 và LOINC vào bảng tra cứu khi khởi động | D2 | 2 | W8 |
| WEB-23 | Form chẩn đoán/dị ứng, kết thúc lần khám, bổ sung addendum | D2 | 4 | W10 |

## US-030
### Bệnh nhân xem kết quả khám của mình — Should

**Là** Bệnh nhân, **tôi muốn** xem các lần khám đã kết thúc và kết quả liên quan, **để** nắm tình trạng sức khỏe.

- Use case: UC-04, UC-06 · API: C12, C16, C17 · AC của FR: —

**Tiêu chí nghiệm thu**

1. Given Encounter đã finished của mình, When đọc, Then thấy; chưa finished thì 404
2. Given đọc dữ liệu của bệnh nhân khác, When gọi, Then 404

**Task**

| Task | Việc | Người | Giờ | Tuần |
|---|---|---|---|---|
| CLN-22 | Quy tắc bệnh nhân đọc dữ liệu lâm sàng của mình (chỉ Encounter finished) | D2 | 3 | W8 |

## US-031
### Tìm bệnh nhân — Must

**Là** Bác sĩ, **tôi muốn** tìm bệnh nhân theo tên, SĐT, mã, ngày sinh, **để** mở đúng hồ sơ nhanh.

- Use case: UC-06 · API: C6 · AC của FR: AC-06.1, AC-06.2

**Tiêu chí nghiệm thu**

1. Given 'nguyen va' không dấu, When tìm, Then ra 'Nguyễn Văn An' và không ra 'Trần Thị Vân'
2. Given bác sĩ không có Care, When tìm, Then thấy tên và năm sinh nhưng mở hồ sơ trả 403 no-care-relationship
3. Given tìm theo dữ liệu cá nhân, When gọi, Then bắt buộc POST _search (không để PII trong URL) và có audit không ghi giá trị tìm
4. Given không có tham số nào, When tìm, Then 400

**Task**

| Task | Việc | Người | Giờ | Tuần |
|---|---|---|---|---|
| CLN-08 | API tìm bệnh nhân POST _search (C6): tên không dấu, SĐT, MRN, ngày sinh, rút gọn kết quả | D2 | 4 | W5 |
| WEB-20 | Màn hình tìm bệnh nhân (POST _search): tên không dấu, SĐT, MRN | D2 | 4 | W6 |
| CLN-26 | Chạy bộ test ma trận phân quyền cho mọi endpoint clinical | D2 | 4 | W7 |

## US-032
### Bác sĩ xem timeline bệnh nhân — Must

**Là** Bác sĩ, **tôi muốn** xem lần khám, chẩn đoán, sinh hiệu, tài liệu theo thời gian, **để** nắm lịch sử bệnh nhân trong 30 giây.

- Use case: UC-06 · API: C19, D8 · AC của FR: —

**Tiêu chí nghiệm thu**

1. Given bệnh nhân có Care, When mở timeline, Then thấy các mục theo thứ tự thời gian, lọc được theo loại và khoảng thời gian
2. Given document service chậm hoặc lỗi, When mở timeline, Then vẫn trả phần còn lại kèm partial=[document]
3. Given p95, When mở timeline, Then ≤ 500 ms (NFR-P3)

**Task**

| Task | Việc | Người | Giờ | Tuần |
|---|---|---|---|---|
| CLN-23 | API timeline (C19): 4 truy vấn song song + document client có timeout 300 ms, partial | D2 | 4 | W9 |
| CLN-28 | Tối ưu timeline: đo p95, index, projection để đạt ≤ 500 ms | D2 | 4 | W9 |
| DOC-10 | API nội bộ (D8, D9) + consumer patient-merged | D2 | 3 | W9 |
| WEB-21 | Màn hình timeline bệnh nhân: lọc loại/khoảng, phân trang, hiển thị partial | D2 | 4 | W9 |

## US-033
### Hệ thống duy trì quan hệ điều trị — Must

**Là** Hệ thống, **tôi muốn** cập nhật quan hệ bác sĩ–bệnh nhân từ lịch hẹn và lần khám, **để** phân quyền theo quan hệ điều trị chính xác.

- Use case: UC-06, UC-10 · API: C20 · AC của FR: —

**Tiêu chí nghiệm thu**

1. Given appointment-booked, When xử lý, Then bác sĩ có Care đến start + 365 ngày
2. Given appointment-cancelled và không còn nguồn nào, When xử lý, Then mất Care
3. Given tra Care, When gọi nội bộ, Then trả care, nguồn và validUntil bằng một lần đọc theo _id

**Task**

| Task | Việc | Người | Giờ | Tuần |
|---|---|---|---|---|
| CLN-12 | API nội bộ: kiểm tra Care, roles của Practitioner, thông tin liên hệ, age-band | D2 | 4 | W3 |
| CLN-13 | Consumer appointment-booked/cancelled/rescheduled/status-changed → care_relationships | D2 | 4 | W5 |
| CLN-27 | Test hợp đồng event E1-E7 cho clinical (consumer đọc được ví dụ, producer sinh đúng schema) | D2 | 3 | W8 |

## US-034
### Bác sĩ truy cập khẩn cấp (break-glass) — Should

**Là** Bác sĩ, **tôi muốn** mở hồ sơ bệnh nhân ngoài quan hệ điều trị khi cấp cứu, **để** cứu người mà vẫn có dấu vết kiểm soát.

- Use case: UC-10 · API: C20 · AC của FR: AC-10.1

**Tiêu chí nghiệm thu**

1. Given bác sĩ không Care mở hồ sơ, When gọi, Then 403 break-glass-required
2. Given lý do 20–500 ký tự, When khởi tạo break-glass, Then phiên 60 phút, cho phép ĐỌC, không cho ghi chẩn đoán
3. Given khởi tạo thành công, When commit, Then phát audit break-glass-start và DPO nhận cảnh báo ≤ 5 phút

**Task**

| Task | Việc | Người | Giờ | Tuần |
|---|---|---|---|---|
| CLN-24 | API break-glass (C20): phiên 60 phút TTL, lý do 20-500 ký tự, audit ETREAT, chỉ đọc | D2 | 4 | W6 |
| CLN-26 | Chạy bộ test ma trận phân quyền cho mọi endpoint clinical | D2 | 4 | W7 |
| WEB-24 | Luồng break-glass: hộp thoại lý do, banner đếm ngược 60 phút | D2 | 2 | W7 |

## US-035
### Nhân viên tải tài liệu lâm sàng lên — Must

**Là** Điều dưỡng, **tôi muốn** tải PDF/ảnh kết quả vào hồ sơ bệnh nhân, **để** kết quả nằm ở một nơi duy nhất.

- Use case: UC-05 · API: D1, D2 · AC của FR: AC-05.1

**Tiêu chí nghiệm thu**

1. Given file PDF/JPEG/PNG ≤ 20 MB, When xin upload, Then nhận SAS ghi 10 phút, upload thẳng lên Blob
2. Given file sai định dạng hoặc quá 20 MB, When xin upload, Then 415 hoặc 413
3. Given size/SHA-256/magic bytes không khớp khi complete, When xác nhận, Then 422 upload-incomplete
4. Given xin upload hai lần cùng Idempotency-Key, When gọi, Then cùng documentId

**Task**

| Task | Việc | Người | Giờ | Tuần |
|---|---|---|---|---|
| SHR-12 | Idempotency-Key cho MongoDB (document service) | D2 | 2 | W2 |
| DOC-01 | Bootstrap MongoDB document: collections, validator, index, TTL upload_sessions | D2 | 2 | W5 |
| DOC-02 | IBlobStore: Azure Blob (user delegation SAS) + MinIO; sinh SAS ghi 10 phút/đọc 5 phút | D2 | 4 | W6 |
| DOC-03 | API xin upload (D1): kiểm loại, kích thước, Care, Idempotency-Key | D2 | 4 | W7 |
| DOC-04 | API complete (D2): kiểm tồn tại, size, SHA-256, magic bytes | D2 | 4 | W8 |
| DOC-11 | Job dọn blob mồ côi và pending-upload > 24 giờ | D2 | 2 | W9 |
| DOC-12 | Test ma trận phân quyền và hợp đồng event cho document | D2 | 4 | W9 |
| WEB-25 | Màn hình tài liệu: upload trực tiếp bằng SAS, xem bằng link 5 phút, phát hành | D2 | 4 | W10 |

## US-036
### Hệ thống quét mã độc tài liệu — Must

**Là** Hệ thống, **tôi muốn** quét mọi file tải lên, **để** không có file độc hại trong hồ sơ.

- Use case: UC-05 · API: — · AC của FR: —

**Tiêu chí nghiệm thu**

1. Given file sạch, When quét xong, Then available và phát document-uploaded
2. Given file nhiễm, When quét xong, Then chuyển vào quarantine, KHÔNG phát document-uploaded, cảnh báo vận hành
3. Given quét lỗi/timeout, When retry 3 lần vẫn lỗi, Then giữ scanning, không cấp link tải

**Task**

| Task | Việc | Người | Giờ | Tuần |
|---|---|---|---|---|
| IAC-18 | Module Defender for Storage + Event Grid → queue cho kết quả quét | SA | 3 | W7 |
| DOC-05 | Xử lý kết quả quét: sạch → available + phát document-uploaded; nhiễm → quarantine | D2 | 4 | W8 |

## US-037
### Người có quyền tải tài liệu bằng link ngắn hạn — Must

**Là** Bác sĩ, **tôi muốn** mở tài liệu qua link hết hạn sau 5 phút, **để** tài liệu không bị lộ qua link cũ.

- Use case: UC-05 · API: D3, D4, D5 · AC của FR: AC-05.2

**Tiêu chí nghiệm thu**

1. Given người có Care, When xin link, Then SAS đọc 5 phút cho đúng một blob, Content-Disposition attachment
2. Given sau 5 phút, When dùng link, Then Blob trả 403
3. Given tài liệu chưa available, When xin link, Then 409 document-not-available
4. Given mỗi lần xin link, When cấp hoặc từ chối, Then ghi audit download-url

**Task**

| Task | Việc | Người | Giờ | Tuần |
|---|---|---|---|---|
| DOC-02 | IBlobStore: Azure Blob (user delegation SAS) + MinIO; sinh SAS ghi 10 phút/đọc 5 phút | D2 | 4 | W6 |
| DOC-06 | API đọc/tìm metadata (D3, D4) với quy tắc bệnh nhân (đã phát hành) | D2 | 3 | W8 |
| DOC-07 | API cấp link tải 5 phút (D5) + audit mỗi lần | D2 | 3 | W8 |
| DOC-12 | Test ma trận phân quyền và hợp đồng event cho document | D2 | 4 | W9 |
| WEB-25 | Màn hình tài liệu: upload trực tiếp bằng SAS, xem bằng link 5 phút, phát hành | D2 | 4 | W10 |

## US-038
### Bác sĩ phát hành tài liệu cho bệnh nhân — Must

**Là** Bác sĩ, **tôi muốn** cho phép bệnh nhân xem kết quả, **để** bệnh nhân chỉ thấy kết quả khi bác sĩ đã xem xét.

- Use case: UC-05 · API: D6 · AC của FR: —

**Tiêu chí nghiệm thu**

1. Given tài liệu available, When release, Then đặt released-to-patient và phát document-released
2. Given đã phát hành, When release lại, Then 409

**Task**

| Task | Việc | Người | Giờ | Tuần |
|---|---|---|---|---|
| DOC-08 | API phát hành (D6) + phát document-released | D2 | 2 | W8 |
| DOC-12 | Test ma trận phân quyền và hợp đồng event cho document | D2 | 4 | W9 |
| WEB-25 | Màn hình tài liệu: upload trực tiếp bằng SAS, xem bằng link 5 phút, phát hành | D2 | 4 | W10 |

## US-039
### Bệnh nhân xem tài liệu đã phát hành — Must

**Là** Bệnh nhân, **tôi muốn** xem và tải kết quả bác sĩ đã phát hành, **để** chủ động nắm kết quả.

- Use case: UC-05 · API: D3, D4, D5 · AC của FR: AC-05.3

**Tiêu chí nghiệm thu**

1. Given tài liệu của mình và đã phát hành, When xem/tải, Then được
2. Given chưa phát hành hoặc của người khác, When xem, Then 404

**Task**

| Task | Việc | Người | Giờ | Tuần |
|---|---|---|---|---|
| DOC-06 | API đọc/tìm metadata (D3, D4) với quy tắc bệnh nhân (đã phát hành) | D2 | 3 | W8 |
| WEB-25 | Màn hình tài liệu: upload trực tiếp bằng SAS, xem bằng link 5 phút, phát hành | D2 | 4 | W10 |

## US-040
### Bác sĩ đánh dấu tài liệu nhập nhầm — Could

**Là** Bác sĩ, **tôi muốn** đánh dấu tài liệu tải nhầm, **để** tài liệu sai không gây hiểu lầm.

- Use case: UC-05 · API: D7 · AC của FR: —

**Tiêu chí nghiệm thu**

1. Given người tải lên và có Care, When PUT entered-in-error, Then ẩn khỏi timeline và bệnh nhân, bản ghi được giữ

**Task**

| Task | Việc | Người | Giờ | Tuần |
|---|---|---|---|---|
| DOC-09 | API entered-in-error (D7) | D2 | 2 | W9 |
| WEB-25 | Màn hình tài liệu: upload trực tiếp bằng SAS, xem bằng link 5 phút, phát hành | D2 | 4 | W10 |

## US-041
### Bệnh nhân nhận xác nhận và nhắc lịch — Must

**Là** Bệnh nhân, **tôi muốn** nhận xác nhận ngay và nhắc trước T-24h, T-2h, **để** không quên lịch khám.

- Use case: UC-07 · API: N5 · AC của FR: —

**Tiêu chí nghiệm thu**

1. Given appointment-booked, When xử lý, Then tạo nhắc booking-confirmation, t-24h, t-2h; bỏ mốc đã qua
2. Given event trùng, When xử lý, Then không tạo nhắc trùng (unique subject+kind)
3. Given khung giờ yên tĩnh, When tới hạn nhắc t-24h, Then lùi sang hết giờ yên tĩnh; t-2h vẫn gửi

**Task**

| Task | Việc | Người | Giờ | Tuần |
|---|---|---|---|---|
| NTF-01 | Migration notification: preference, device_token, reminder, delivery_attempt, inbox | D1 | 2 | W4 |
| NTF-02 | Consumer appointment-booked → tạo nhắc xác nhận, T-24h, T-2h (bỏ mốc đã qua) | D1 | 3 | W4 |
| NTF-04 | Logic tùy chọn: thứ tự kênh, ngôn ngữ, khung giờ yên tĩnh (t-2h bỏ qua) | D1 | 3 | W4 |
| NTF-06 | Abstraction kênh + provider giả + template vi/en (không nhúng PII/chẩn đoán) | D1 | 4 | W4 |
| NTF-13 | Metric độ trễ gửi để đo NFR-P8 | D1 | 2 | W5 |

## US-042
### Nhắc lịch được hủy khi lịch hẹn bị hủy/đổi — Must

**Là** Bệnh nhân, **tôi muốn** không nhận nhắc cho lịch đã hủy, **để** không bị làm phiền sai.

- Use case: UC-07 · API: — · AC của FR: AC-07.1

**Tiêu chí nghiệm thu**

1. Given appointment-cancelled, When xử lý, Then các nhắc scheduled của lịch đó thành cancelled và tạo nhắc cancellation
2. Given appointment-rescheduled, When xử lý, Then hủy nhắc lịch cũ, tạo nhắc cho lịch mới, chỉ một thông báo gộp

**Task**

| Task | Việc | Người | Giờ | Tuần |
|---|---|---|---|---|
| NTF-03 | Consumer appointment-cancelled/rescheduled: hủy nhắc chưa gửi, tạo nhắc hủy | D1 | 3 | W4 |

## US-043
### Gửi thông báo bền bỉ khi nhà cung cấp lỗi — Must

**Là** Hệ thống, **tôi muốn** retry, chuyển kênh và dead-letter khi gửi lỗi, **để** không mất thông báo.

- Use case: UC-07 · API: — · AC của FR: AC-07.2

**Tiêu chí nghiệm thu**

1. Given kênh chính lỗi tạm thời, When gửi, Then thử kênh dự phòng theo thứ tự ưu tiên
2. Given mọi kênh lỗi, When gửi, Then lên lịch lại theo backoff 1/5/15/60 phút
3. Given lỗi lần thứ 5, When gửi, Then dead-lettered và phát cảnh báo
4. Given worker chết giữa chừng, When locked_until quá hạn, Then dòng được thu hồi, không gửi trùng (attempt unique)

**Task**

| Task | Việc | Người | Giờ | Tuần |
|---|---|---|---|---|
| CLN-12 | API nội bộ: kiểm tra Care, roles của Practitioner, thông tin liên hệ, age-band | D2 | 4 | W3 |
| NTF-05 | Vòng lấy việc: SKIP LOCKED, locked_until, thu hồi dòng sending bị treo | D1 | 4 | W4 |
| NTF-06 | Abstraction kênh + provider giả + template vi/en (không nhúng PII/chẩn đoán) | D1 | 4 | W4 |
| NTF-07 | Bộ gửi: kênh dự phòng, backoff 1/5/15/60 phút, dead-letter lần 5, metric | D1 | 4 | W4 |
| NTF-08 | Client lấy thông tin liên hệ từ clinical (nội bộ) + audit; không lưu PII | D1 | 2 | W4 |
| NTF-12 | Adapter thật: push (FCM), email, SMS qua interface kênh | D1 | 4 | W4 |
| NTF-15 | Test lỗi: nhà cung cấp ngừng, worker bị kill giữa chừng | D1 | 3 | W5 |

## US-044
### Quản trị viên xử lý nhắc lịch lỗi — Should

**Là** Quản trị viên, **tôi muốn** xem và đưa lại hàng đợi các nhắc bị dead-letter, **để** phát hiện và khắc phục sự cố gửi tin.

- Use case: UC-07 · API: N6, N7 · AC của FR: —

**Tiêu chí nghiệm thu**

1. Given nhắc dead-lettered, When Admin xem, Then thấy lý do lỗi, không thấy thông tin liên hệ
2. Given retry, When lịch hẹn chưa qua giờ khám, Then về scheduled; đã qua thì 409

**Task**

| Task | Việc | Người | Giờ | Tuần |
|---|---|---|---|---|
| NTF-10 | API xem nhắc theo lịch hẹn và dead-letter, retry (N5-N7) | D1 | 3 | W5 |
| NTF-14 | Test ma trận phân quyền + hợp đồng event cho notification | D1 | 3 | W6 |

## US-045
### Bệnh nhân được báo có kết quả mới — Should

**Là** Bệnh nhân, **tôi muốn** nhận thông báo khi bác sĩ phát hành kết quả, **để** biết để mở xem.

- Use case: UC-07 · API: — · AC của FR: —

**Tiêu chí nghiệm thu**

1. Given document-released, When xử lý, Then tạo nhắc result-available với nội dung chung chung, không nêu loại kết quả

**Task**

| Task | Việc | Người | Giờ | Tuần |
|---|---|---|---|---|
| NTF-11 | Consumer document-released, no-show-scored, patient-merged | D1 | 3 | W5 |

## US-046
### Lịch hẹn rủi ro cao được nhắc thêm — Should

**Là** Hệ thống, **tôi muốn** gửi thêm nhắc T-6h cho lịch có nguy cơ no-show cao, **để** giảm tỉ lệ vắng mặt.

- Use case: UC-15, UC-07 · API: — · AC của FR: AC-15.1

**Tiêu chí nghiệm thu**

1. Given no-show-scored có highRisk và start-6h còn ở tương lai, When xử lý, Then tạo nhắc t-6h-risk
2. Given ML tắt hoặc lỗi, When đặt lịch, Then vẫn có nhắc t-24h và t-2h bình thường

**Task**

| Task | Việc | Người | Giờ | Tuần |
|---|---|---|---|---|
| NTF-11 | Consumer document-released, no-show-scored, patient-merged | D1 | 3 | W5 |
| ML-07 | Chấm điểm khi nhận E1: dựng đặc trưng, dự đoán, lưu prediction, phát no-show-scored qua outbox | D3 | 4 | W7 |
| ML-12 | Test hợp đồng: consume E1/E5 và phát E10; ML lỗi không ảnh hưởng nhắc lịch | D3 | 2 | W7 |

## US-047
### Mọi truy cập dữ liệu bệnh nhân đều được ghi audit — Must

**Là** Cán bộ DPO, **tôi muốn** có dấu vết đầy đủ mọi truy cập, **để** chứng minh tuân thủ bảo vệ dữ liệu.

- Use case: UC-09 · API: — · AC của FR: AC-09.1

**Tiêu chí nghiệm thu**

1. Given đọc/ghi/tìm/tải/hỏi AI trên dữ liệu bệnh nhân, When thành công hoặc bị từ chối, Then có bản ghi audit (ai, khi nào, cái gì, kết quả, mục đích, trace id)
2. Given bus lỗi, When ghi audit, Then event nằm trong outbox và phát khi bus phục hồi, không mất
3. Given bản ghi audit, When kiểm tra, Then không chứa nội dung lâm sàng hay PII; search chỉ ghi tên tham số

**Task**

| Task | Việc | Người | Giờ | Tuần |
|---|---|---|---|---|
| AUD-01 | Consumer audit.recorded: validate schema, inbox theo eventId, gom batch 60s/5.000 bản ghi | D2 | 4 | W4 |
| PY-04 | Audit emitter Python (E11) cho AI/ML | D3 | 2 | W4 |
| SHR-09 | Thư viện audit emitter: dựng audit.recorded (schema 05), che IP, chặn PII, middleware FHIR | D2 | 4 | W4 |
| SHR-27 | Authz: phát audit cho mọi quyết định (cho phép và từ chối) | D1 | 2 | W5 |

## US-048
### Audit bất biến — Must

**Là** Cán bộ DPO, **tôi muốn** bảo đảm không ai sửa hoặc xóa audit, **để** audit đáng tin cậy.

- Use case: UC-09 · API: — · AC của FR: AC-09.2

**Tiêu chí nghiệm thu**

1. Given bản ghi đã ghi, When ai đó (kể cả Admin) sửa/xóa blob, Then bị từ chối bởi immutability policy
2. Given audit service nhận event trùng, When ghi, Then không tạo bản ghi trùng (eventId)
3. Given batch ghi, When tạo, Then chứa prevHash và hash của batch trước

**Task**

| Task | Việc | Người | Giờ | Tuần |
|---|---|---|---|---|
| AUD-02 | Ghi append blob NDJSON vào container WORM kèm chuỗi hash (prevHash, batchHash) | D2 | 4 | W5 |
| IAC-12 | Module storage | SA | 4 | W5 |
| AUD-08 | Test bất biến: sửa/xóa blob audit bị từ chối, chuỗi hash đứt bị phát hiện | D2 | 3 | W6 |

## US-049
### DPO tra cứu ai đã xem hồ sơ — Must

**Là** Cán bộ DPO, **tôi muốn** tra cứu theo bệnh nhân, người truy cập, thời gian, **để** điều tra sự cố nhanh.

- Use case: UC-09 · API: A1 · AC của FR: —

**Tiêu chí nghiệm thu**

1. Given bệnh nhân X và khoảng 30 ngày, When tra cứu, Then thấy từng người xem, hành động, mục đích, trong ≤ 1 phút kể từ lúc xảy ra
2. Given khoảng > 90 ngày hoặc thiếu from/to, When tra cứu, Then 400
3. Given Admin tra cứu, When xem, Then không thấy lý do break-glass; việc tra cứu cũng được audit

**Task**

| Task | Việc | Người | Giờ | Tuần |
|---|---|---|---|---|
| AUD-03 | Đẩy sang Log Analytics (Logs Ingestion) / Loki local | D2 | 4 | W4 |
| AUD-04 | API tra cứu audit (A1): bộ lọc, khoảng ≤ 90 ngày, ẩn reason với Admin, audit việc tra cứu | D2 | 4 | W4 |
| WEB-26 | Màn hình DPO: tra cứu audit, xuất CSV, xem xét break-glass | D2 | 4 | W8 |

## US-050
### DPO xuất báo cáo audit và kiểm tra toàn vẹn — Should

**Là** Cán bộ DPO, **tôi muốn** xuất CSV và kiểm tra chuỗi hash, **để** nộp báo cáo cho cơ quan quản lý.

- Use case: UC-09 · API: A2, A3, A4 · AC của FR: —

**Tiêu chí nghiệm thu**

1. Given khoảng ≤ 366 ngày, When tạo export, Then 202 và có link tải SAS 5 phút khi xong
2. Given chuỗi hash bị đứt, When kiểm tra toàn vẹn, Then báo vị trí batch đứt

**Task**

| Task | Việc | Người | Giờ | Tuần |
|---|---|---|---|---|
| AUD-05 | API export audit (A2, A3): job nền xuất CSV, link SAS 5 phút | D2 | 4 | W5 |
| AUD-06 | Công cụ + API kiểm tra toàn vẹn chuỗi hash (A4) | D2 | 4 | W6 |
| WEB-26 | Màn hình DPO: tra cứu audit, xuất CSV, xem xét break-glass | D2 | 4 | W8 |

## US-051
### DPO xem xét các lần break-glass — Should

**Là** Cán bộ DPO, **tôi muốn** đánh giá lý do break-glass, **để** ngăn lạm dụng.

- Use case: UC-10 · API: A5 · AC của FR: —

**Tiêu chí nghiệm thu**

1. Given break-glass mới, When DPO xem, Then trạng thái new → acknowledged với quyết định justified/unjustified và ghi chú
2. Given break-glass mới, When phát sinh, Then DPO nhận email + push ≤ 5 phút

**Task**

| Task | Việc | Người | Giờ | Tuần |
|---|---|---|---|---|
| AUD-07 | Break-glass review (A5) + phát cảnh báo DPO ≤ 5 phút qua notification | D2 | 4 | W7 |
| WEB-26 | Màn hình DPO: tra cứu audit, xuất CSV, xem xét break-glass | D2 | 4 | W8 |

## US-052
### Quản trị viên quản lý kho tri thức — Must

**Là** Quản trị viên, **tôi muốn** tải, duyệt, thay thế và gỡ tài liệu phác đồ, **để** trợ lý chỉ dựa trên tài liệu đã duyệt.

- Use case: UC-11 · API: AI9 · AC của FR: —

**Tiêu chí nghiệm thu**

1. Given file PDF/DOCX/MD ≤ 30 MB, When tải lên, Then draft; approve thì chunk, embed, index → indexed
2. Given thay file, When index xong, Then chunk cũ mới bị xóa (không có khoảng trống)
3. Given retire, When gỡ, Then biến khỏi truy xuất, giữ bản ghi

**Task**

| Task | Việc | Người | Giờ | Tuần |
|---|---|---|---|---|
| AI-01 | Bootstrap MongoDB ai + script tạo index AI Search kb-chunks-v1 (và OpenSearch local) | D3 | 3 | W4 |
| AI-02 | Parser tài liệu: PDF, DOCX, Markdown → văn bản có cấu trúc (mục, trang) | D3 | 3 | W4 |
| AI-03 | Chunker: 600 token, overlap 80, tách theo tiêu đề mục | D3 | 4 | W4 |
| AI-04 | Embedding + upsert/xóa index; thay version không có khoảng trống | D3 | 4 | W5 |
| AI-05 | API kho tri thức (AI9): tải lên, duyệt, thay file, gỡ; quyền Admin/Doctor khoa | D3 | 4 | W5 |
| WEB-31 | Giao diện quản lý kho tri thức và xem chi phí AI (Admin) | D3 | 4 | W7 |
| AI-32 | Test ma trận phân quyền và hợp đồng event cho ai-service | D3 | 4 | W10 |

## US-053
### Bác sĩ/điều dưỡng hỏi trợ lý và nhận câu trả lời có trích dẫn — Must

**Là** Bác sĩ, **tôi muốn** hỏi về phác đồ nội bộ, **để** tra cứu nhanh và có nguồn kiểm chứng.

- Use case: UC-11 · API: AI1 · AC của FR: AC-11.1

**Tiêu chí nghiệm thu**

1. Given có đoạn liên quan, When hỏi, Then câu trả lời kèm ≥ 1 trích dẫn trỏ đúng chunk (quote khớp)
2. Given không có đoạn đạt ngưỡng, When hỏi, Then answered=false 'không có thông tin trong tài liệu', không gọi LLM sinh nội dung
3. Given chỉ tài liệu allowedRoles/khoa phù hợp, When truy xuất, Then lọc ngay trong truy vấn (security trimming)
4. Given p95, When hỏi, Then token đầu ≤ 3 giây, đầy đủ ≤ 10 giây

**Task**

| Task | Việc | Người | Giờ | Tuần |
|---|---|---|---|---|
| PY-02 | Client on-behalf-of (Entra OBO, Keycloak RFC 8693 token exchange) | D3 | 3 | W2 |
| AI-06 | Truy xuất hybrid + semantic rerank + security trimming theo role/khoa; xuống cấp khi embedding/reranker lỗi | D3 | 4 | W5 |
| AI-07 | Prompt template có version + lắp ngữ cảnh trong giới hạn token | D3 | 3 | W5 |
| AI-08 | Sinh câu trả lời + kiểm tra trích dẫn khớp chunk + quy tắc answered=false | D3 | 4 | W5 |
| AI-09 | API hỏi trợ lý (AI1) + SSE stream, event reset khi chuyển model | D3 | 4 | W6 |
| WEB-30 | Giao diện trợ lý: hỏi, stream, hiển thị trích dẫn bấm được, chấm điểm | D3 | 4 | W6 |
| WEB-31 | Giao diện quản lý kho tri thức và xem chi phí AI (Admin) | D3 | 4 | W7 |
| AI-32 | Test ma trận phân quyền và hợp đồng event cho ai-service | D3 | 4 | W10 |

## US-054
### Trợ lý kháng prompt injection — Must

**Là** Bác sĩ, **tôi muốn** tài liệu hoặc câu hỏi độc hại không điều khiển được trợ lý, **để** an toàn khi tài liệu có chỉ dẫn lạ.

- Use case: UC-11 · API: AI1 · AC của FR: AC-11.2

**Tiêu chí nghiệm thu**

1. Given bộ 50 mẫu tấn công (trong câu hỏi và trong tài liệu), When chạy, Then 0 lần vượt rào (NFR-AI3)
2. Given câu hỏi có mẫu injection, When hỏi, Then answered=false, guardrail blocked, có audit

**Task**

| Task | Việc | Người | Giờ | Tuần |
|---|---|---|---|---|
| AI-10 | Guardrail đầu vào: độ dài, mẫu prompt injection, chặn có audit | D3 | 3 | W6 |
| AI-11 | Guardrail đầu ra: schema, không có chẩn đoán/chỉ định tự đưa ra, quote khớp chunk | D3 | 3 | W6 |
| AI-17 | Bộ tấn công prompt injection 50 mẫu (trong câu hỏi và trong tài liệu) + runner | D3 | 4 | W6 |

## US-055
### Trợ lý vẫn hoạt động khi model lỗi — Must

**Là** Bác sĩ, **tôi muốn** tự chuyển model dự phòng và xuống cấp có kiểm soát, **để** tra cứu không gián đoạn.

- Use case: UC-11 · API: AI1, AI10 · AC của FR: AC-11.3

**Tiêu chí nghiệm thu**

1. Given model chính timeout/5xx/429, When hỏi, Then chuyển model kế (redact PII khi ra ngoài Azure) và ghi fallbackLevel
2. Given mọi model lỗi, When hỏi, Then mode=retrieval-only trả danh sách mục liên quan
3. Given hết ngân sách token ngày, When hỏi, Then 503 ai-budget-exceeded (kèm retrieval-only nếu còn)
4. Given usage, When xem, Then thấy token, chi phí, tỉ lệ fallback theo tác vụ/model/ngày

**Task**

| Task | Việc | Người | Giờ | Tuần |
|---|---|---|---|---|
| PY-06 | Bộ che PII (tên, SĐT, email, địa chỉ, mã định danh → token giả có bản đồ ngược) | D3 | 4 | W2 |
| PY-07 | Giao diện LLM provider + adapter Azure OpenAI, Anthropic, Ollama + provider giả cho test | D3 | 4 | W2 |
| PY-08 | Router: chuỗi model theo tác vụ, timeout, chuyển model kế, kết quả cuối cùng khi hết chuỗi | D3 | 4 | W2 |
| AI-13 | Theo dõi token/chi phí/fallback + API usage (AI10) + cảnh báo 80% ngân sách | D3 | 3 | W3 |
| PY-09 | Circuit breaker + hạn mức token/ngày + metric theo model | D3 | 3 | W3 |
| AI-09 | API hỏi trợ lý (AI1) + SSE stream, event reset khi chuyển model | D3 | 4 | W6 |
| AI-18 | Chạy eval cho từng model trong chuỗi fallback và lập báo cáo so sánh | D3 | 3 | W7 |
| IAC-19 | Module Azure OpenAI + Document Intelligence và các deployment | SA | 4 | W7 |
| AI-33 | Báo cáo token và chi phí theo tác vụ/model | D3 | 3 | W11 |

## US-056
### Người dùng đánh giá câu trả lời của trợ lý — Could

**Là** Bác sĩ, **tôi muốn** chấm điểm câu trả lời, **để** chất lượng được cải thiện.

- Use case: UC-11 · API: AI2, AI3 · AC của FR: —

**Tiêu chí nghiệm thu**

1. Given câu trả lời của mình, When đánh giá up/down, Then lưu feedback; đánh giá lại thì 409, sửa bằng PUT
2. Given lịch sử hội thoại, When xem, Then chỉ thấy của mình, tự xóa sau 30 ngày

**Task**

| Task | Việc | Người | Giờ | Tuần |
|---|---|---|---|---|
| WEB-30 | Giao diện trợ lý: hỏi, stream, hiển thị trích dẫn bấm được, chấm điểm | D3 | 4 | W6 |
| AI-12 | Lưu hội thoại/tin nhắn (TTL 30 ngày) + API feedback và lịch sử (AI2, AI3) | D3 | 3 | W7 |

## US-057
### Bộ đánh giá chất lượng RAG — Must

**Là** Nhóm dự án, **tôi muốn** đo Recall@5, MRR, groundedness theo từng model, **để** chứng minh chất lượng và quyết định chuỗi fallback.

- Use case: UC-11 · API: — · AC của FR: —

**Tiêu chí nghiệm thu**

1. Given 100 câu hỏi có đáp án chuẩn, When chạy eval, Then báo cáo Recall@5 ≥ 0,8, MRR ≥ 0,6, groundedness ≥ 0,9, citation đúng ≥ 0,9
2. Given từng model trong chuỗi fallback, When chạy eval, Then có báo cáo riêng (NFR-AI7)

**Task**

| Task | Việc | Người | Giờ | Tuần |
|---|---|---|---|---|
| AI-14 | Bộ eval RAG phần 1: 50 câu hỏi + đoạn đáp án chuẩn từ kho tri thức mẫu | D3 | 3 | W5 |
| AI-15 | Bộ eval RAG phần 2: thêm 50 câu (có câu không có đáp án trong tài liệu) | D3 | 3 | W6 |
| AI-16 | Harness eval: Recall@5, MRR, groundedness, độ đúng trích dẫn, tỉ lệ từ chối đúng | D3 | 4 | W6 |
| AI-18 | Chạy eval cho từng model trong chuỗi fallback và lập báo cáo so sánh | D3 | 3 | W7 |

## US-058
### Bác sĩ nhận tóm tắt hồ sơ bệnh nhân — Should

**Là** Bác sĩ, **tôi muốn** tóm tắt bệnh nền, dị ứng, lần khám gần nhất, kết quả bất thường, **để** nắm tình trạng bệnh nhân trong vài giây.

- Use case: UC-12 · API: AI4 · AC của FR: AC-12.1, AC-12.2, AC-12.3

**Tiêu chí nghiệm thu**

1. Given bác sĩ có Care hoặc break-glass, When tóm tắt, Then chỉ dùng dữ liệu người đó được xem (token của bác sĩ)
2. Given mỗi ý trong tóm tắt, When hiển thị, Then có liên kết bản ghi gốc; ý không có nguồn hợp lệ bị loại
3. Given kết quả, When hiển thị, Then có nhãn 'Do AI tạo, cần bác sĩ kiểm tra'
4. Given mọi model lỗi, When tóm tắt, Then trả structured-timeline không qua LLM
5. Given bác sĩ không có quyền, When tóm tắt, Then 403 và không có dữ liệu nào đi vào prompt

**Task**

| Task | Việc | Người | Giờ | Tuần |
|---|---|---|---|---|
| AI-19 | Thu thập dữ liệu cho tóm tắt bằng OBO: timeline, bệnh nền, dị ứng, kết quả bất thường; đánh số nguồn | D3 | 4 | W9 |
| AI-20 | Prompt + schema đầu ra + bộ kiểm tra nguồn (loại ý không có nguồn hợp lệ) | D3 | 4 | W9 |
| AI-21 | API tóm tắt (AI4): Idempotency-Key, cache 10 phút, nhãn AI, fallback structured-timeline | D3 | 4 | W9 |
| AI-32 | Test ma trận phân quyền và hợp đồng event cho ai-service | D3 | 4 | W10 |
| WEB-32 | Panel tóm tắt hồ sơ: nhãn AI, nguồn bấm được sang bản ghi | D3 | 3 | W10 |

## US-059
### Hệ thống trích xuất dữ liệu từ tài liệu tải lên — Should

**Là** Hệ thống, **tôi muốn** OCR, phân loại, trích trường từ tài liệu, **để** giảm nhập tay cho điều dưỡng.

- Use case: UC-13 · API: AI5, D9 · AC của FR: AC-13.2

**Tiêu chí nghiệm thu**

1. Given document-uploaded loại xét nghiệm/đơn thuốc/chuyển viện, When xử lý, Then tạo extraction job (unique documentId+version) rồi OCR → phân loại → trích → validate → needs-review
2. Given lỗi ở một bước, When xử lý, Then retry 1/5/15 phút, vẫn lỗi thì failed, upload không bị ảnh hưởng
3. Given overallConfidence ≥ 0,85, When hoàn tất, Then đánh dấu đề xuất chấp nhận; thấp hơn thì cần kiểm tra kỹ

**Task**

| Task | Việc | Người | Giờ | Tuần |
|---|---|---|---|---|
| PY-03 | Consumer CloudEvents Python: validate schema, inbox (Mongo/Postgres), retry/DLQ | D3 | 4 | W4 |
| AI-22 | Mô hình job trích xuất + consumer document-uploaded + state machine + retry/DLQ | D3 | 4 | W8 |
| AI-23 | Bước OCR: Azure Document Intelligence, dự phòng Tesseract; văn bản lưu Blob | D3 | 4 | W9 |
| AI-24 | Phân loại + trích trường bằng LLM theo schema + validator giá trị/đơn vị + độ tin cậy | D3 | 4 | W9 |
| AI-25 | API danh sách và chi tiết trích xuất (AI5) kèm vị trí trường để tô trên tài liệu | D3 | 3 | W9 |
| DOC-10 | API nội bộ (D8, D9) + consumer patient-merged | D2 | 3 | W9 |
| WEB-33 | Màn hình duyệt trích xuất: tô trường trên tài liệu gốc, sửa, chấp nhận/từ chối | D3 | 4 | W10 |

## US-060
### Nhân viên duyệt kết quả trích xuất — Should

**Là** Điều dưỡng, **tôi muốn** xem trường được trích, sửa và chấp nhận hoặc từ chối, **để** dữ liệu vào hồ sơ đã được người xác nhận.

- Use case: UC-13 · API: AI5, AI6 · AC của FR: AC-13.1

**Tiêu chí nghiệm thu**

1. Given job needs-review, When duyệt accepted, Then tạo Observation (derivedFrom DocumentReference) bằng token người duyệt, ghi editedFields
2. Given chưa có người xác nhận, When job xong, Then KHÔNG có dữ liệu nào vào hồ sơ
3. Given giá trị sau sửa ngoài khoảng, When duyệt, Then lỗi từ clinical được trả nguyên văn

**Task**

| Task | Việc | Người | Giờ | Tuần |
|---|---|---|---|---|
| AI-25 | API danh sách và chi tiết trích xuất (AI5) kèm vị trí trường để tô trên tài liệu | D3 | 3 | W9 |
| AI-26 | API duyệt (AI6): tạo Observation bằng OBO sau khi người xác nhận; ghi editedFields | D3 | 4 | W9 |
| AI-32 | Test ma trận phân quyền và hợp đồng event cho ai-service | D3 | 4 | W10 |
| WEB-33 | Màn hình duyệt trích xuất: tô trường trên tài liệu gốc, sửa, chấp nhận/từ chối | D3 | 4 | W10 |

## US-061
### Bộ đánh giá trích xuất tài liệu — Should

**Là** Nhóm dự án, **tôi muốn** đo F1 mức trường, **để** chứng minh độ chính xác.

- Use case: UC-13 · API: — · AC của FR: —

**Tiêu chí nghiệm thu**

1. Given bộ tài liệu giả có nhãn, When chạy eval, Then báo cáo F1 mức trường ≥ 0,85 (NFR-AI4) và F1 phân loại

**Task**

| Task | Việc | Người | Giờ | Tuần |
|---|---|---|---|---|
| AI-27 | Bộ tài liệu giả có nhãn cho trích xuất (xét nghiệm, đơn thuốc, chuyển viện) | D3 | 4 | W10 |
| AI-28 | Eval F1 mức trường và phân loại + báo cáo | D3 | 3 | W10 |

## US-062
### Bệnh nhân đặt lịch qua chatbot — Could

**Là** Bệnh nhân, **tôi muốn** chat để tìm slot và đặt lịch, **để** đặt lịch bằng ngôn ngữ tự nhiên.

- Use case: UC-14 · API: AI7, AI8 · AC của FR: AC-14.1, AC-14.2, AC-14.3

**Tiêu chí nghiệm thu**

1. Given bot đề xuất đặt lịch, When chưa xác nhận, Then không có lịch hẹn nào được tạo
2. Given bệnh nhân xác nhận trong 5 phút, When gọi confirm, Then bot gọi $book bằng token bệnh nhân với channel=chatbot
3. Given bot, When thực thi, Then chỉ có 4 tool được phép, không đọc hồ sơ lâm sàng, chỉ làm việc với bệnh nhân của chính người dùng
4. Given mọi model lỗi, When chat, Then 503 ai-unavailable kèm fallback form đặt lịch
5. Given slot vừa bị đặt, When confirm, Then 409 và bot đề xuất lại 3 slot

**Task**

| Task | Việc | Người | Giờ | Tuần |
|---|---|---|---|---|
| AI-29 | Bot đặt lịch: 4 tool, schema tham số, giới hạn 6 vòng và timeout 10 giây | D3 | 4 | W3 |
| AI-30 | pending_action + API xác nhận (AI8): gọi $book/$cancel bằng token bệnh nhân, hết hạn 5 phút | D3 | 4 | W8 |
| AI-31 | API chat đặt lịch (AI7) + fallback sang form khi mọi model lỗi | D3 | 3 | W9 |
| AI-32 | Test ma trận phân quyền và hợp đồng event cho ai-service | D3 | 4 | W10 |
| WEB-34 | Widget chatbot đặt lịch có bước xác nhận (Could) | D3 | 4 | W10 |

## US-063
### Huấn luyện và đăng ký mô hình dự đoán no-show — Should

**Là** Nhóm dự án, **tôi muốn** huấn luyện mô hình trên dữ liệu giả và đăng ký có kiểm soát chất lượng, **để** có vòng đời MLOps hoàn chỉnh.

- Use case: UC-15 · API: M2 · AC của FR: AC-15.2

**Tiêu chí nghiệm thu**

1. Given dữ liệu giả có quy luật, When huấn luyện baseline và LightGBM, Then ghi tham số/metric vào MLflow
2. Given AUC < 0,75 hoặc không hơn baseline, When đăng ký, Then bị cổng chất lượng chặn
3. Given đổi alias production, When pod chạy, Then tự nạp model mới trong ≤ 60 giây

**Task**

| Task | Việc | Người | Giờ | Tuần |
|---|---|---|---|---|
| ML-01 | Sinh dữ liệu lịch hẹn có quy luật no-show (lead time, lịch sử vắng, giờ khám, kênh) | D3 | 4 | W3 |
| ML-02 | Feature engineering + chia train/validation theo thời gian | D3 | 4 | W3 |
| ML-03 | Baseline Logistic Regression + metric (AUC, precision/recall tại ngưỡng) | D3 | 3 | W3 |
| ML-04 | LightGBM + tìm tham số + MLflow tracking | D3 | 4 | W3 |
| ML-05 | Cổng chất lượng và model registry: AUC ≥ 0,75 và hơn baseline mới được đăng ký alias production | D3 | 3 | W3 |
| GOP-10 | Chart MLflow (tracking + registry) cho môi trường cluster | SA | 3 | W6 |
| ML-08 | API M1 (xem dự đoán), M2 (model hiện hành), M3 (predict nội bộ) | D3 | 3 | W7 |
| ML-09 | Nạp lại model khi đổi alias production trong ≤ 60 giây không cần deploy | D3 | 2 | W7 |
| MLO-01 | Pipeline huấn luyện ML trong CI + canary deploy model | SA | 4 | W8 |

## US-064
### Chấm điểm no-show khi đặt lịch — Should

**Là** Hệ thống, **tôi muốn** chấm điểm mỗi lịch hẹn mới, **để** biết lịch nào rủi ro cao.

- Use case: UC-15 · API: M1, M3 · AC của FR: AC-15.2

**Tiêu chí nghiệm thu**

1. Given appointment-booked, When xử lý, Then lưu appointment_fact, dự đoán (score, ngưỡng, model version, đặc trưng) và phát no-show-scored
2. Given kết quả thật (fulfilled/noshow/cancelled), When nhận event, Then cập nhật outcome và attendance_stats

**Task**

| Task | Việc | Người | Giờ | Tuần |
|---|---|---|---|---|
| PY-03 | Consumer CloudEvents Python: validate schema, inbox (Mongo/Postgres), retry/DLQ | D3 | 4 | W4 |
| ML-11 | Migration PostgreSQL ml: attendance_stats, appointment_fact, prediction, inbox, outbox | D3 | 2 | W5 |
| ML-06 | Consumer E1/E2/E3/E5/E7: ghi appointment_fact, cập nhật outcome và attendance_stats | D3 | 4 | W6 |
| ML-07 | Chấm điểm khi nhận E1: dựng đặc trưng, dự đoán, lưu prediction, phát no-show-scored qua outbox | D3 | 4 | W7 |
| ML-08 | API M1 (xem dự đoán), M2 (model hiện hành), M3 (predict nội bộ) | D3 | 3 | W7 |
| ML-12 | Test hợp đồng: consume E1/E5 và phát E10; ML lỗi không ảnh hưởng nhắc lịch | D3 | 2 | W7 |

## US-065
### Theo dõi drift và hiệu năng thực tế của mô hình — Should

**Là** Nhóm dự án, **tôi muốn** phát hiện mô hình xuống cấp bằng PSI và AUC thực tế, **để** biết khi nào cần huấn luyện lại.

- Use case: UC-15 · API: — · AC của FR: —

**Tiêu chí nghiệm thu**

1. Given job hằng ngày, When so phân phối đặc trưng và điểm dự đoán với dữ liệu huấn luyện, Then tính PSI từng đặc trưng và cảnh báo khi PSI > 0,2 ở ≥ 2 đặc trưng chính
2. Given kết quả thật từ appointment-status-changed, When có ≥ 500 mẫu có nhãn trong 14 ngày, Then tính AUC thực tế và cảnh báo khi giảm > 0,05 so với lúc đăng ký
3. Given bộ dữ liệu giả có phân phối đã dịch chuyển, When chạy detector, Then phát hiện đúng drift (kiểm bằng test)

**Task**

| Task | Việc | Người | Giờ | Tuần |
|---|---|---|---|---|
| ML-21 | Bộ dữ liệu giả thứ hai có phân phối dịch chuyển (lead time dài hơn, tỉ lệ vắng tăng) | D3 | 3 | W4 |
| ML-10 | Job drift hằng ngày chạy detector (ML-19, ML-20), xuất metric và cảnh báo | D3 | 3 | W8 |
| ML-19 | Detector drift: PSI từng đặc trưng và điểm dự đoán so với dữ liệu huấn luyện | D3 | 4 | W8 |
| ML-20 | Giám sát hiệu năng thực tế: ghép dự đoán với kết quả thật, AUC cuộn 14 ngày | D3 | 4 | W8 |
| MLO-05 | Cảnh báo drift và AUC thực tế (Alertmanager) + panel dashboard MLOps | SA | 3 | W8 |

## US-071
### Dữ liệu huấn luyện có phiên bản và được kiểm tra — Should

**Là** Nhóm dự án, **tôi muốn** mỗi lần train biết chính xác dùng dữ liệu nào và dữ liệu đó hợp lệ, **để** tái lập được kết quả và không train trên dữ liệu hỏng.

- Use case: UC-15 · API: — · AC của FR: —

**Tiêu chí nghiệm thu**

1. Given dataset Parquet, When train, Then hash dataset được ghi vào run MLflow và dựng lại được model từ hash đó
2. Given dữ liệu sai schema, ngoài khoảng, quá nhiều giá trị thiếu hoặc rò rỉ thời gian, When kiểm tra, Then dừng trước khi train
3. Given cùng code đặc trưng cho huấn luyện và phục vụ, When so sánh đầu ra trên mẫu chuẩn, Then khớp tuyệt đối (không train/serve skew)

**Task**

| Task | Việc | Người | Giờ | Tuần |
|---|---|---|---|---|
| ML-13 | Dataset Parquet có phiên bản: ghi hash dataset vào run MLflow, dựng lại model từ hash | D3 | 3 | W4 |
| ML-15 | Package đặc trưng dùng chung cho huấn luyện và phục vụ | D3 | 3 | W4 |
| ML-14 | Kiểm tra dữ liệu trước khi train (schema, khoảng, tỉ lệ thiếu, rò rỉ thời gian) bằng pandera | D3 | 3 | W5 |
| ML-16 | Test chống lệch train/serve: cùng mẫu cho đầu ra khớp giữa hai đường | D3 | 3 | W7 |
| MLO-02 | Image huấn luyện tái lập được: pin phiên bản thư viện, seed, image cố định, quét lỗ hổng | SA | 3 | W8 |

## US-072
### Đánh giá mô hình theo nhóm con và có model card — Should

**Là** Nhóm dự án, **tôi muốn** chỉ đăng ký model đạt chất lượng ở mọi nhóm quan trọng, **để** không có nhóm bệnh nhân bị mô hình dự đoán kém hơn hẳn.

- Use case: UC-15 · API: — · AC của FR: —

**Tiêu chí nghiệm thu**

1. Given model ứng viên, When đánh giá, Then đo AUC theo chuyên khoa, nhóm tuổi, kênh đặt; cổng chặn nếu một nhóm thấp hơn AUC tổng > 0,07
2. Given model ứng viên, When đánh giá, Then kiểm calibration và độ trễ dự đoán p95 ≤ 100 ms
3. Given model được đăng ký, When hoàn tất, Then sinh model card (dữ liệu, metric, nhóm con, ngưỡng, giới hạn, phiên bản)

**Task**

| Task | Việc | Người | Giờ | Tuần |
|---|---|---|---|---|
| ML-17 | Đánh giá theo nhóm con (chuyên khoa, nhóm tuổi, kênh) + calibration + độ trễ dự đoán | D3 | 4 | W4 |
| ML-18 | Sinh model card tự động khi đăng ký model | D3 | 3 | W5 |

## US-073
### Triển khai model mới bằng canary tự động — Should

**Là** DevOps, **tôi muốn** đưa model mới ra production theo từng bước có phân tích tự động, **để** model xấu bị chặn hoặc rollback mà không cần can thiệp tay.

- Use case: UC-15 · API: — · AC của FR: —

**Tiêu chí nghiệm thu**

1. Given alias candidate, When triển khai, Then chạy shadow (chấm điểm song song, không ảnh hưởng người dùng) rồi canary 10% → 50% → 100%
2. Given mỗi bước canary, When phân tích, Then so sánh tỉ lệ lỗi, p95 độ trễ, phân phối điểm (PSI so với bản đang chạy < 0,1) và đồng thuận với bản cũ; đạt thì tự tăng tỉ lệ
3. Given một chỉ số vượt ngưỡng, When phân tích, Then tự rollback về alias production cũ và cảnh báo
4. Given môi trường prod, When cấu hình autoPromote=false, Then dừng chờ phê duyệt trước bước 100%

**Task**

| Task | Việc | Người | Giờ | Tuần |
|---|---|---|---|---|
| ML-24 | Chế độ shadow trong serving: model candidate chấm điểm song song, chỉ ghi nhận, không dùng kết quả | D3 | 3 | W7 |
| ML-25 | Xuất metric cho phân tích canary: tỉ lệ lỗi, p95, phân phối điểm, đồng thuận, gắn nhãn phiên bản | D3 | 3 | W8 |
| MLO-03 | Argo Rollouts canary cho ml-service + AnalysisTemplate (Prometheus), tự tăng tỉ lệ hoặc rollback | SA | 6 | W8 |

## US-074
### Huấn luyện lại tự động khi có drift — Should

**Là** Hệ thống, **tôi muốn** tự huấn luyện model mới khi phát hiện drift hoặc hiệu năng giảm, **để** mô hình luôn bám dữ liệu hiện tại.

- Use case: UC-15 · API: — · AC của FR: —

**Tiêu chí nghiệm thu**

1. Given cảnh báo drift hoặc AUC thực tế giảm, When thỏa điều kiện (cooldown 7 ngày, ≥ 500 mẫu mới có nhãn), Then tự chạy pipeline huấn luyện lại
2. Given model ứng viên, When qua kiểm tra dữ liệu và mọi cổng chất lượng (US-072), Then đăng ký alias candidate; không đạt thì dừng và cảnh báo, model production giữ nguyên
3. Given candidate đã đăng ký, When cấu hình autoPromote=true, Then tự đi qua canary của US-073; mọi bước có audit và liên kết run MLflow

**Task**

| Task | Việc | Người | Giờ | Tuần |
|---|---|---|---|---|
| ML-22 | Logic kích hoạt huấn luyện lại: điều kiện, cooldown 7 ngày, ≥ 500 mẫu nhãn mới, chống chạy chồng | D3 | 3 | W8 |
| ML-23 | Job huấn luyện lại: train → kiểm tra dữ liệu → cổng chất lượng → đăng ký alias candidate, không bao giờ tự đổi production | D3 | 4 | W8 |
| MLO-04 | Điều phối huấn luyện lại trên cluster: CronJob/KEDA nhận yêu cầu từ ML-22, chạy ML-23, theo dõi và audit | SA | 5 | W8 |

## US-066
### Người dùng đăng nhập và thấy giao diện theo vai trò — Must

**Là** Người dùng, **tôi muốn** đăng nhập bằng OIDC và vào đúng màn hình theo vai trò, **để** chỉ thấy chức năng mình được phép.

- Use case: UC-08 · API: C2 · AC của FR: —

**Tiêu chí nghiệm thu**

1. Given đăng nhập Entra ID hoặc Keycloak, When vào ứng dụng, Then menu hiển thị theo role
2. Given hết phiên, When gọi API, Then tự đăng nhập lại; lỗi 403/404 hiển thị thông điệp theo Accept-Language

**Task**

| Task | Việc | Người | Giờ | Tuần |
|---|---|---|---|---|
| LOC-02 | Keycloak realm local | SA | 3 | W2 |
| PY-01 | Thư viện Python: xác thực JWT (Entra + Keycloak), dependency role cho FastAPI | D3 | 3 | W2 |
| SHR-20 | JWT bearer cho Entra ID và Keycloak: map claim roles, kiểm amr=mfa với nhân viên | D1 | 3 | W2 |
| WEB-01 | Web shell React/Vite: OIDC (Entra/Keycloak), router theo vai trò, khung giao diện, i18n vi/en | D1 | 4 | W3 |
| WEB-02 | Client API: gắn token, Idempotency-Key, If-Match/ETag, ánh xạ OperationOutcome/Problem Details sang thông điệp | D1 | 3 | W3 |
| IAC-16 | Module identity ứng dụng (Entra ID) | SA | 4 | W6 |
| WEB-10 | Pipeline + chart cho web | SA | 2 | W6 |

## US-067
### Khôi phục dữ liệu về một thời điểm — Must

**Là** Quản trị viên, **tôi muốn** khôi phục lịch hẹn và hồ sơ khi sự cố, **để** mất dữ liệu tối đa 5 phút, khôi phục trong 1 giờ.

- Use case: UC-16 · API: — · AC của FR: AC-16.1, AC-16.2

**Tiêu chí nghiệm thu**

1. Given PITR 7 ngày, When khôi phục PostgreSQL/MongoDB về thời điểm bất kỳ, Then dữ liệu đúng thời điểm đó
2. Given diễn tập, When thực hiện, Then ghi lại thời gian thực tế so với RTO 1 giờ

**Task**

| Task | Việc | Người | Giờ | Tuần |
|---|---|---|---|---|
| IAC-09 | Module postgresql | SA | 4 | W5 |
| IAC-10 | Module mongodb (Cosmos DB for MongoDB vCore) | SA | 4 | W5 |
| PRD-03 | Diễn tập backup/restore | SA | 4 | W10 |

## US-068
### Hệ thống chịu tải mở slot lúc 07:00 — Must

**Là** Nhóm dự án, **tôi muốn** chịu 500 RPS tìm slot và 50 RPS đặt lịch, xuống cấp có kiểm soát ở 10×, **để** đáp ứng NFR hiệu năng và mở rộng.

- Use case: UC-03 · API: — · AC của FR: —

**Tiêu chí nghiệm thu**

1. Given k6 1×, When chạy 10 phút, Then p95 tìm ≤ 300 ms, đặt ≤ 800 ms
2. Given 5×, When chạy, Then p95 không quá 2× mục tiêu
3. Given 10×, When chạy, Then trả 429 có kiểm soát thay vì sập

**Task**

| Task | Việc | Người | Giờ | Tuần |
|---|---|---|---|---|
| GW-02 | Xác thực + rate limit theo người dùng | SA | 4 | W7 |
| PRD-10 | Script k6: tìm slot + đặt lịch (kịch bản 1x/5x/10x) | SA | 4 | W8 |
| PRD-11 | KEDA cron scale trước 07:00 + HPA cho scheduling | SA | 1 | W8 |
| PRD-01 | Load test 1x/5x/10x + báo cáo | SA | 6 | W10 |
| SCH-32 | Tinh chỉnh hiệu năng theo kết quả load test (pool, index, truy vấn) | D1 | 4 | W10 |

## US-069
### Hệ thống chịu lỗi phụ thuộc — Must

**Là** Nhóm dự án, **tôi muốn** chứng minh hệ thống vẫn đúng khi phụ thuộc lỗi, **để** đáp ứng NFR-A3, A6.

- Use case: — · API: — · AC của FR: —

**Tiêu chí nghiệm thu**

1. Given kill pod, DB failover, nhà cung cấp SMS lỗi, LLM lỗi, consumer crash, When kiểm thử, Then không mất dữ liệu đã commit và có kết quả ghi lại

**Task**

| Task | Việc | Người | Giờ | Tuần |
|---|---|---|---|---|
| NTF-15 | Test lỗi: nhà cung cấp ngừng, worker bị kill giữa chừng | D1 | 3 | W5 |
| PRD-02 | Failure test | SA | 6 | W10 |

## US-070
### Triển khai an toàn, có phê duyệt — Must

**Là** DevOps, **tôi muốn** mọi thay đổi lên môi trường đi qua pipeline có phê duyệt và rollback được, **để** triển khai không downtime, rollback ≤ 5 phút.

- Use case: — · API: — · AC của FR: —

**Tiêu chí nghiệm thu**

1. Given commit vào main, When CI chạy, Then qua đủ kiểm tra, image được ký, chỉ image đã ký mới chạy được trên cluster
2. Given deploy staging/prod, When thực hiện, Then cần phê duyệt GitHub Environment và PR GitOps
3. Given sự cố, When rollback bằng revert GitOps, Then ≤ 5 phút

**Task**

| Task | Việc | Người | Giờ | Tuần |
|---|---|---|---|---|
| GOV-01 | Tạo 4 repo theo ADR-0010 | SA | 1 | W1 |
| GOV-02 | Ruleset bảo vệ nhánh main cho 4 repo | SA | 2 | W1 |
| GOV-03 | CODEOWNERS + PR template + issue template | SA | 2 | W1 |
| GOV-07 | GitHub Project + import backlog | SA | 2 | W1 |
| CIT-01 | Reusable workflow .NET CI | SA | 4 | W2 |
| CIT-03 | Reusable workflow container | SA | 5 | W2 |
| GOP-01 | Helm chart chung microservice | SA | 8 | W2 |
| CIT-05 | Reusable workflow Terraform checks + plan | SA | 4 | W3 |
| CIT-06 | Reusable workflow GitOps update/promote | SA | 4 | W3 |
| GOP-03 | Chart bootstrap Argo CD | SA | 6 | W3 |
| GOV-05 | GitHub App bot cho GitOps | SA | 2 | W3 |
| CIT-02 | Reusable workflow Python CI | SA | 3 | W4 |
| CIT-04 | Reusable workflow Helm validate | SA | 3 | W4 |
| CIT-07 | Workflow kiểm tra chung cho mọi PR | SA | 3 | W4 |
| CIT-08 | Caller workflow mẫu cho service + script sinh | SA | 2 | W4 |
| GOP-04 | Platform add-on: Argo Rollouts, KEDA, cert-manager | SA | 3 | W4 |
| GOP-05 | Platform add-on: Envoy Gateway (Gateway API) | SA | 3 | W4 |
| GOV-04 | GitHub Environments và người duyệt | SA | 2 | W4 |
| ENV-01 | Workflow env-provision | SA | 6 | W5 |
| ENV-02 | Workflow env-destroy | SA | 2 | W5 |
| GOP-06 | Platform add-on: OpenTelemetry Collector | SA | 3 | W5 |
| GOP-07 | Kyverno + policy | SA | 4 | W5 |
| GOP-08 | NetworkPolicy default-deny + namespace | SA | 2 | W5 |
| ENV-05 | Diễn tập G4 | SA | 4 | W6 |
| IAC-20 | Gắn các module dữ liệu và identity (IAC-09..IAC-16) vào 3 stack | SA | 4 | W6 |
| CIT-09 | Phát hành templates có version | SA | 2 | W7 |
| CIT-10 | Đóng gói NuGet nội bộ cho thư viện dùng chung | SA | 3 | W7 |
| GOP-02 | Unit test cho chart | SA | 3 | W7 |
| GOP-09 | Canary prod với Argo Rollouts | SA | 3 | W7 |
| GOV-06 | Dependabot cho actions, nuget, pip, docker, terraform | SA | 2 | W7 |
| ENV-03 | Workflow cost-guard | SA | 2 | W8 |
| ENV-04 | Runbook vòng đời môi trường | SA | 2 | W8 |
