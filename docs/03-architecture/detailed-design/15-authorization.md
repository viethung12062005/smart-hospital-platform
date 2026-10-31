# 15 — Thiết kế phân quyền

Thực thi [ma trận phân quyền](../../02-requirements/access-control-matrix.md) (NFR-SEC2) và BR-05, BR-06, BR-07.
Kết hợp **RBAC** (role trong token) và **ABAC** (quan hệ giữa người gọi và dữ liệu).

## 1. Các lớp kiểm soát

| Lớp | Việc làm | Ở đâu |
|---|---|---|
| 1. Xác thực | Kiểm chữ ký, `iss`, `aud`, `exp`; nhân viên phải có `amr` chứa `mfa` (NFR-SEC1) | Gateway **và** từng service |
| 2. Role | `roles` ∈ {`patient`,`doctor`,`nurse`,`admin`,`dpo`} từ claim; thiếu → 403 | Service (thư viện chung) |
| 3. Danh tính FHIR | `sub` → `identity_links` → `fhirUser` (cache 60 giây) và `profile_links` đang hiệu lực → `linkedProfiles[]` (hồ sơ, phạm vi); chưa liên kết hoặc `disabled` → 403 `forbidden` | Service, gọi clinical |
| 4. Chính sách theo tài nguyên | Hàm quyết định theo (role, hành động, loại resource, quan hệ) — mục 3 | Service sở hữu dữ liệu |
| 5. Lọc dữ liệu | Bộ lọc ép vào truy vấn (ví dụ `patient = mình`) để không bao giờ đọc dữ liệu ngoài phạm vi | Tầng truy vấn |
| 6. Audit | Mỗi quyết định (cả cho phép và từ chối) phát `audit-recorded` | Thư viện chung |

Gateway **không** quyết định quyền nghiệp vụ (chỉ lớp 1 và giới hạn tốc độ). Service không tin header gateway thêm vào.

## 2. Mô hình quyết định

```
Decision = Authorize(principal, action, resourceType, resourceContext)
principal       = { subject, roles[], fhirUser, mfa }
resourceContext = { patientId?, practitionerId?, encounterStatus?, releasedToPatient?, ... }
Decision        = Allow(purpose) | Deny(code)       purpose ∈ TREAT | ETREAT | HOPERAT | PATRQT
```
**Mặc định từ chối.** Một yêu cầu chỉ được phép khi có **đúng một quy tắc** khớp và mọi điều kiện của nó thỏa.

### Điều kiện (predicate) dùng trong quy tắc

| Predicate | Định nghĩa |
|---|---|
| `own` | `resourceContext.patientId == principal.fhirUser` (Patient) |
| `linked` | `resourceContext.patientId` thuộc `linkedProfiles` của người gọi với liên kết `active` chưa hết hạn, phạm vi từ `booking` trở lên (đặt, hủy, đổi, xem lịch hẹn và hồ sơ hành chính) |
| `linked-records` | như `linked`, phạm vi `records` hoặc `full` (thêm kết quả khám và tài liệu đã phát hành) |
| `guardian` | liên kết có `relationship=guardian` (cha, mẹ, người giám hộ của người dưới 16 tuổi) |
| `self-practitioner` | `resourceContext.practitionerId == principal.fhirUser` |
| `care` | Có quan hệ điều trị (mục 4) giữa `principal.fhirUser` (Practitioner) và `resourceContext.patientId` |
| `breakglass` | Có phiên break-glass chưa hết hạn cho cặp (practitioner, patient) |
| `released` | Tài liệu có `released-to-patient` |
| `encounter-finished` | Encounter của resource ở trạng thái `finished` |
| `before-2h` | `appointment.start - now ≥ 2 giờ` |
| `unfinished-encounter` | Encounter chưa `finished` |
| `not-care` | Bác sĩ **không** có `care` với bệnh nhân (điều kiện để được khởi tạo break-glass) |

## 3. Ma trận dưới dạng dữ liệu (nguồn sinh test)

Ma trận được lưu trong repo ở `contracts/authz/matrix.yaml`, **vừa là cấu hình cho thư viện chính sách vừa là đầu vào sinh test
tự động** (mỗi ô → ít nhất một test: Allow và Deny). Trích:

```yaml
rules:
  - { id: PAT-READ-OWN,   resource: Patient,     action: read,   role: patient, when: [own],                purpose: PATRQT }
  - { id: PAT-READ-DOC,   resource: Patient,     action: read,   role: doctor,  when: [care],               purpose: TREAT }
  - { id: PAT-READ-DOC-BG,resource: Patient,     action: read,   role: doctor,  when: [breakglass],         purpose: ETREAT }
  - { id: PAT-READ-NUR,   resource: Patient,     action: read,   role: nurse,   when: [],                    purpose: TREAT }
  - { id: PAT-UPD-OWN,    resource: Patient,     action: update, role: patient, when: [own], fields: [telecom, address, contact, communication] }
  - { id: APPT-BOOK-OWN,  resource: Appointment, action: book,   role: patient, when: [own] }
  - { id: APPT-BOOK-NUR,  resource: Appointment, action: book,   role: nurse,   when: [] }
  - { id: APPT-CANCEL-PAT,resource: Appointment, action: cancel, role: patient, when: [own, before-2h] }
  - { id: APPT-CANCEL-NUR,resource: Appointment, action: cancel, role: nurse,   when: [] }
  - { id: APPT-READ-DOC,  resource: Appointment, action: read,   role: doctor,  when: [self-practitioner] }
  - { id: ENC-READ-PAT,   resource: Encounter,   action: read,   role: patient, when: [own, encounter-finished], purpose: PATRQT }
  - { id: ENC-WRITE-DOC,  resource: Encounter,   action: update, role: doctor,  when: [care, unfinished-encounter] }
  - { id: COND-WRITE-DOC, resource: Condition,   action: create, role: doctor,  when: [care] }
  - { id: DOC-DL-PAT,     resource: Document,    action: download, role: patient, when: [own, released], purpose: PATRQT }
  - { id: DOC-DL-DOC,     resource: Document,    action: download, role: doctor,  when: [care] }
  - { id: DOC-REL-DOC,    resource: Document,    action: release,  role: doctor,  when: [care] }
  - { id: AUDIT-READ-DPO, resource: Audit,       action: read,   role: dpo,     when: [], purpose: HOPERAT }
  - { id: AUDIT-READ-ADM, resource: Audit,       action: read,   role: admin,   when: [], hide: [reason] }
  - { id: BG-START-DOC,   resource: BreakGlass,  action: start,  role: doctor,  when: [not-care] }
```
Ngoài các dòng trên: **admin không có dòng nào cho dữ liệu lâm sàng**, `dpo` chỉ có dòng cho `Audit`.

**Sinh test (SHR-09):** với mỗi `(resource, action)` trong ma trận, bộ test gọi endpoint tương ứng với **cả 5 role × các tổ hợp
điều kiện**; kỳ vọng Allow chỉ ở dòng khớp, mọi ô còn lại phải 403 hoặc 404 và có bản ghi audit `denied`. Thêm endpoint mới mà
chưa có dòng trong `matrix.yaml` → test "mọi endpoint phải có quy tắc" thất bại (chặn quên phân quyền).

## 4. Quan hệ điều trị (`care`)

Bác sĩ có `care` với bệnh nhân khi tồn tại **một nguồn còn hiệu lực** trong `care_relationships` (clinical):

| Nguồn | Tạo bởi | Hiệu lực |
|---|---|---|
| `appointment` | E1 (đặt lịch với bác sĩ đó) | từ lúc đặt đến `start + 365 ngày` |
| `encounter` | E4/`$start` (bác sĩ là attender) | đến `period.start + 365 ngày` |

- E2/E3 (hủy/đổi lịch) gỡ nguồn `appointment` tương ứng; còn nguồn khác thì quan hệ vẫn còn.
- `validUntil = max(hiệu lực các nguồn)`. Tra cứu: `findOne({_id: "<prac>|<pat>", validUntil > now})` — một lần đọc theo `_id`.
- Nurse **không** dùng `care` (được xem mọi bệnh nhân trong phạm vi nghiệp vụ tại quầy); mọi truy cập vẫn bị audit.
- Tính nhất quán: projection cập nhật theo event nên có thể trễ vài giây sau khi đặt lịch; chấp nhận được (đặt lịch xong
  bác sĩ chưa cần mở hồ sơ ngay). Nếu cần ngay, bác sĩ dùng break-glass.

## 5. Break-glass (UC-10)

1. Bác sĩ không `care` đọc hồ sơ → **403 `break-glass-required`** (đã audit `denied`).
2. `POST /break-glass` với `reason` 20–500 ký tự → phiên **60 phút** (TTL), `purpose = ETREAT`.
3. Trong phiên, quy tắc `*-READ-DOC-BG` cho phép **đọc** (không cho ghi chẩn đoán/sinh hiệu — ghi vẫn cần `care`).
4. Mỗi lần đọc audit với `purposeOfUse = ETREAT` và `reason`; audit service báo DPO ≤ 5 phút (AC-10.1); DPO đánh dấu
   justified/unjustified (A5). Tỉ lệ `unjustified` là chỉ số theo dõi.
5. Không gia hạn tự động; hết hạn phải khởi tạo lại và nhập lý do mới.

## 6. Bệnh nhân truy cập dữ liệu của mình

- `own` so khớp `fhirUser` với `patientId` của resource; **không** lấy `patientId` từ body/query của client.
- Tìm kiếm luôn bị **ép** bộ lọc `patient = fhirUser` (không báo lỗi nếu client gửi giá trị khác, mà ghi đè và ghi audit).
- Chỉ thấy: hồ sơ, lịch hẹn, `Encounter`/`Observation`/`Condition` thuộc lần khám **đã kết thúc**, tài liệu **đã phát hành** (AC-05.3).
- Truy cập resource không thuộc mình → **404** (không lộ sự tồn tại).

## 7. Service-to-service và AI

| Trường hợp | Cơ chế |
|---|---|
| Gọi `/internal/*` | Token workload identity, `aud` = service đích, app role `internal.<service>`; mỗi endpoint nội bộ có **danh sách service được gọi** (kiểm bằng role, và NetworkPolicy chặn ở tầng mạng) |
| AI đọc dữ liệu bệnh nhân | **Token exchange RFC 8693** (Keycloak) → token mang danh tính người dùng, `aud` = clinical/scheduling; quy tắc ma trận áp dụng nguyên vẹn cho người dùng đó |
| AI ghi (tạo `Observation` từ trích xuất, đặt lịch qua chatbot) | Cùng cơ chế, **chỉ sau khi người dùng xác nhận** (AI6, AI8) |
| Job nền (sinh slot, no-show, dọn dẹp) | Service identity; audit `actor.role = system`, `purposeOfUse = HOPERAT` |

## 8. Quản lý danh tính

- Nhân viên: tài khoản do Admin mời qua email trong Keycloak, bắt buộc TOTP hoặc WebAuthn (không dùng SMS làm yếu tố thứ hai); `identity_links` liên kết `sub` ↔ `Practitioner`, kiểm tra khớp `PractitionerRole.code` với role (`doctor`↔`doctor`, `nurse`↔`nurse`).
- Bệnh nhân: tự đăng ký trên trang Keycloak bằng số điện thoại (OTP SMS) hoặc email (role `patient` mặc định), rồi liên kết `Patient` qua `POST /registration` (C1).
- Hồ sơ người thân: `profile_links` (ADR-0018). Con dưới 16 tuổi: người giám hộ cam đoan, toàn quyền đến sinh nhật 16 tuổi. Người lớn: chỉ khi **chính họ đồng ý** (đọc mã OTP gửi tới số của họ, hoặc tại quầy có giấy tờ), phạm vi `booking` hoặc `records`, hết hạn 12 tháng, thu hồi ≤ 60 giây. Người được ủy quyền không xem được ghi chú chưa phát hành, không sửa họ tên, ngày sinh, giới tính, không đổi đồng ý AI. Audit mọi truy cập qua liên kết kèm `onBehalfOf`.
- Tự nhận hồ sơ đã có (C27, C28): chỉ liên kết "bản thân" khi OTP đúng gửi tới số **lưu trong hồ sơ**, hồ sơ chưa gắn tài khoản nào, và gửi thông báo cho số đó.
- Khóa: `PUT /admin/users/{sub} {disabled:true}` → ghi `identity_links.disabled`; tối đa 60 giây (hết cache) mọi service từ chối; token còn hạn (≤ 15 phút) vẫn bị từ chối vì lớp 3 kiểm `disabled`.
- Đổi role: có hiệu lực ở token kế tiếp (≤ 15 phút); khóa thì ngay (60 giây) như trên.

## 9. Điểm cần kiểm chứng (đưa vào test và kịch bản demo)

| Kịch bản | Kỳ vọng |
|---|---|
| Patient A đọc `Patient/B`, `Appointment` của B, tài liệu của B | 404, audit `denied` |
| Patient hủy lịch còn 90 phút | 422 `cancellation-too-late`; Nurse hủy được |
| Doctor không Care mở hồ sơ | 403 `break-glass-required` → break-glass → đọc được, DPO nhận cảnh báo ≤ 5 phút |
| Admin đọc `Encounter` | 403 |
| Doctor ghi `Condition` cho bệnh nhân không Care (kể cả đang break-glass) | 403 |
| Token hết hạn / thiếu MFA (nhân viên) | 401 / 403 |
| Tài khoản bị khóa, token còn hạn | 403 sau ≤ 60 giây |
| AI tóm tắt hồ sơ bệnh nhân bác sĩ không có quyền | 403, không có dữ liệu nào đi vào prompt |
| Thêm endpoint không có trong `matrix.yaml` | Test CI thất bại |
