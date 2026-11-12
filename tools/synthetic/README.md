
# DAT-01 + DAT-02 – Vietnamese Synthetic Data Generator

## 1. Giới thiệu

DAT-01 là bộ sinh dữ liệu giả tiếng Việt cho dự án Smart Hospital Platform. DAT-02 mở rộng DAT-01 bằng cách sinh thêm dữ liệu lịch làm việc, lịch hẹn, lần khám, chẩn đoán, sinh hiệu và tài liệu mẫu.

Mục tiêu:

- Sinh dữ liệu giả cho bệnh nhân, bác sĩ, điều dưỡng và các tài nguyên lâm sàng.
- Không sử dụng dữ liệu cá nhân hoặc hồ sơ bệnh án thật.
- Hỗ trợ nhiều quy mô dữ liệu phục vụ phát triển và kiểm thử.
- Đảm bảo tính tái lập: cùng `seed` và `scale` phải tạo ra cùng dữ liệu.
- Đảm bảo tính toàn vẹn tham chiếu giữa các tài nguyên.
- Sử dụng terminology contract của dự án để bảo đảm các mã chuyên môn nhất quán.

## 2. Danh sách tài nguyên và quy mô

| Resource | Small | Medium | Large |
|---|---:|---:|---:|
| Patient | 200 | 20.000 | 500.000 |
| Practitioner | 12 | 120 | 600 |
| PractitionerRole | 12 | 120 | 600 |
| Schedule | Theo số bác sĩ và khoảng thời gian | Theo số bác sĩ và khoảng thời gian | Theo số bác sĩ và khoảng thời gian |
| Slot | Theo số bác sĩ và ngày làm việc | Theo số bác sĩ và ngày làm việc | Theo số bác sĩ và ngày làm việc |
| Appointment | 600 | 100.000 | 2.000.000 |
| Encounter | Theo số Appointment fulfilled | Theo số Appointment fulfilled | Theo số Appointment fulfilled |
| Condition | Theo số Encounter | Theo số Encounter | Theo số Encounter |
| Observation | 8 trên mỗi Encounter | 8 trên mỗi Encounter | 8 trên mỗi Encounter |
| DocumentReference | 100 | 5.000 | 50.000 |
| Document files | 100 | 5.000 | 50.000 |

Trong đó:

- **Patient:** thông tin bệnh nhân giả.
- **Practitioner:** thông tin nhân viên y tế.
- **PractitionerRole:** vai trò chuyên môn của nhân viên y tế, gồm bác sĩ (`doctor`) và điều dưỡng (`nurse`).
- **Schedule:** lịch làm việc của bác sĩ.
- **Slot:** các khung giờ có thể đặt lịch.
- **Appointment:** lịch hẹn giữa bệnh nhân và nhân viên y tế.
- **Encounter:** lần khám được tạo từ Appointment có trạng thái `fulfilled`.
- **Condition:** thông tin chẩn đoán giả gắn với lần khám.
- **Observation:** các chỉ số sinh hiệu giả.
- **DocumentReference:** metadata tham chiếu đến tài liệu mẫu.

Các số lượng Large là quy mô cấu hình của generator, chưa được xác minh bằng lần chạy thực tế.

## 3. Cấu trúc thư mục

```text
tools/synthetic/
├── README.md
├── generate.py
└── generators/
    ├── __init__.py
    ├── ids.py
    ├── names.py
    ├── patients.py
    ├── practitioners.py
    ├── schedules.py
    ├── appointments.py
    ├── clinical.py
    └── documents.py

tests/
└── synthetic/
    ├── test_generators.py
    └── test_dat02.py
```

Chức năng các thành phần:

| File | Chức năng |
|---|---|
| `generate.py` | Giao diện dòng lệnh, điều phối sinh dữ liệu và tạo manifest |
| `generators/ids.py` | Sinh UUIDv7 có tính tái lập |
| `generators/names.py` | Họ, tên đệm, tên tiếng Việt theo giới; slug cho email |
| `generators/patients.py` | Sinh dữ liệu Patient |
| `generators/practitioners.py` | Sinh Practitioner và PractitionerRole |
| `generators/schedules.py` | Sinh Schedule và Slot |
| `generators/appointments.py` | Sinh Appointment và trạng thái lịch hẹn |
| `generators/clinical.py` | Sinh Encounter, Condition và Observation |
| `generators/documents.py` | Sinh DocumentReference và nội dung tài liệu SVG |
| `tests/synthetic/test_generators.py` | Kiểm thử DAT-01 và tính tái lập |
| `tests/synthetic/test_dat02.py` | Kiểm thử DAT-02 và tính toàn vẹn tham chiếu |

## 4. Yêu cầu môi trường

- Python >= 3.12.
- Cài đặt các dependency được khai báo trong `tools/contracts/requirements.txt`.
- Chạy lệnh từ thư mục gốc repository.

## 5. Cách sử dụng

### 5.1. Sinh dữ liệu Small

```bash
python3 -m tools.synthetic.generate --scale small --seed 42
```

### 5.2. Sinh dữ liệu Medium

```bash
python3 -m tools.synthetic.generate --scale medium --seed 42
```

### 5.3. Sinh dữ liệu Large

```bash
python3 -m tools.synthetic.generate --scale large --seed 42
```

Lưu ý: Large sinh 500.000 Patient và 2.000.000 Appointment. Cần cân nhắc tài nguyên RAM, dung lượng lưu trữ và thời gian chạy trước khi thực hiện.

### 5.4. Các tham số

| Tham số | Ý nghĩa | Mặc định |
|---|---|---|
| `--scale` | Quy mô dữ liệu: `small`, `medium`, `large` | `small` |
| `--seed` | Số nguyên dùng làm random seed | `42` |
| `--output-dir` | Thư mục gốc lưu dữ liệu | `dev/.local/synthetic` |

Có thể thay đổi thư mục đầu ra:

```bash
python3 -m tools.synthetic.generate \
    --scale small \
    --seed 42 \
    --output-dir /tmp/synthetic-data
```

## 6. Cấu trúc dữ liệu đầu ra

Dữ liệu được lưu theo cấu trúc:

```text
dev/.local/synthetic/
├── small-seed-42/
│   ├── patients.json
│   ├── practitioners.json
│   ├── practitioner_roles.json
│   ├── schedules.json
│   ├── slots.json
│   ├── appointments.json
│   ├── encounters.json
│   ├── conditions.json
│   ├── observations.json
│   ├── document_references.json
│   ├── documents/
│   │   ├── <uuid-1>.svg
│   │   ├── <uuid-2>.svg
│   │   └── ...
│   └── manifest.json
├── medium-seed-42/
│   ├── patients.json
│   ├── practitioners.json
│   ├── practitioner_roles.json
│   ├── schedules.json
│   ├── slots.json
│   ├── appointments.json
│   ├── encounters.json
│   ├── conditions.json
│   ├── observations.json
│   ├── document_references.json
│   ├── documents/
│   │   └── ...
│   └── manifest.json
└── large-seed-42/
    ├── patients.json
    ├── practitioners.json
    ├── practitioner_roles.json
    ├── schedules.json
    ├── slots.json
    ├── appointments.json
    ├── encounters.json
    ├── conditions.json
    ├── observations.json
    ├── document_references.json
    ├── documents/
    │   └── ...
    └── manifest.json
```

Ý nghĩa các file:

| File | Nội dung |
|---|---|
| `patients.json` | Danh sách Patient |
| `practitioners.json` | Danh sách Practitioner |
| `practitioner_roles.json` | Danh sách PractitionerRole |
| `schedules.json` | Danh sách Schedule |
| `slots.json` | Danh sách Slot |
| `appointments.json` | Danh sách Appointment |
| `encounters.json` | Danh sách Encounter |
| `conditions.json` | Danh sách Condition |
| `observations.json` | Danh sách Observation |
| `document_references.json` | Metadata của tài liệu mẫu |
| `documents/` | Các file tài liệu SVG |
| `manifest.json` | Thông tin generator, scale, seed, số lượng bản ghi và SHA-256 |

Các file dữ liệu được mã hóa UTF-8 để hỗ trợ tiếng Việt có dấu.

### 6.1. Kết quả kiểm tra output

Các số liệu sau được ghi nhận từ lần chạy với `seed=42`:

| Resource | Small | Medium |
|---|---:|---:|
| Patient | 200 | 20.000 |
| Practitioner | 12 | 120 |
| PractitionerRole | 12 | 120 |
| Schedule | 8 | 240 |
| Slot | 4.320 | 129.600 |
| Appointment | 600 | 100.000 |
| Encounter | 448 | 76.822 |
| Condition | 448 | 76.822 |
| Observation | 3.584 | 614.576 |
| DocumentReference | 100 | 5.000 |
| Document files | 100 | 5.000 |

Kết quả kiểm tra dung lượng và thời gian chạy:

| Scale | Dung lượng output | Thời gian |
|---|---:|---:|
| Small | Chưa ghi nhận riêng | Chưa ghi nhận |
| Medium | Khoảng 943 MiB | 1 phút 21 giây |
| Large | Chưa kiểm thử | Chưa kiểm thử |

Large là quy mô được cấu hình trong generator, chưa được chạy kiểm thử thực tế. Các số lượng Schedule và Slot phụ thuộc số bác sĩ và khoảng thời gian sinh lịch. Số lượng Encounter, Condition và Observation phụ thuộc số Appointment có trạng thái `fulfilled`.

Dữ liệu sinh ra là artifact phục vụ phát triển và kiểm thử tại máy local, không đưa vào Git.

### 6.2. Manifest

File `manifest.json` lưu:

- `generator`: tên generator.
- `scale`: quy mô dữ liệu.
- `seed`: seed sử dụng để tái lập dữ liệu.
- `counts`: số lượng resource được sinh.
- `sha256`: mã băm SHA-256 của từng file JSON và nhóm tài liệu.

## 7. Tính tái lập và định danh

Generator sử dụng random seed để đảm bảo:

- Cùng `scale` và `seed` tạo ra cùng dữ liệu.
- Khác `seed` tạo ra dữ liệu khác nhau.
- UUIDv7 được sinh theo cơ chế xác định từ seed.
- SHA-256 được tính cho từng file dữ liệu để phục vụ kiểm tra tính toàn vẹn và tái lập.

Manifest lưu lại:

- Tên generator.
- Scale.
- Seed.
- Số lượng từng resource.
- SHA-256 của từng file dữ liệu.

## 8. Kiểm thử

Test chạy bằng `pytest` (hoặc `unittest`), tự động trong CI ở job `synthetic` của workflow `qa` (ruff, mypy strict, test):

```bash
pip install -r tools/contracts/requirements.txt ruff mypy pytest types-PyYAML
ruff check tools/synthetic tests/synthetic && ruff format --check tools/synthetic tests/synthetic
mypy --strict --explicit-package-bases tools/synthetic tests/synthetic
python -m pytest -q tests/synthetic
```

Các nhóm kiểm thử DAT-01:

| Nhóm | Nội dung |
|---|---|
| UUIDv7 | Phiên bản UUID, tái lập, khác seed thì khác |
| Patient | Số lượng; ID/MRN/email/SĐT duy nhất; SĐT chỉ trong dải giả; email `.test`; tên theo giới; tỉ lệ giới 49/51; phân bố tuổi 18/35/29/18; ngày sinh hợp lệ; dấu hiệu giả `(test)`, `(giả)` |
| Dải số điện thoại | Đủ 500.000 số không trùng cho `large`; 100.000 số đầu thuộc dải gốc `09000xxxxx` |
| Practitioner / Role | ID không trùng với nhau và với Patient; tham chiếu tồn tại; chuyên khoa và vai trò lấy từ `contracts/terminology`; tên theo giới |
| Tái lập | Cùng seed + scale cho cùng file đầu ra từng byte |

Các nhóm kiểm thử DAT-02:

| Nhóm | Nội dung |
|---|---|
| Schedule / Slot | Tham chiếu Practitioner và Schedule tồn tại |
| Appointment | Tham chiếu Patient, Practitioner và Slot tồn tại; trạng thái và lý do hủy hợp lệ |
| Encounter | Chỉ sinh từ Appointment có trạng thái `fulfilled` |
| Condition | Tham chiếu Patient và Encounter tồn tại |
| Observation | Tham chiếu Patient và Encounter tồn tại; mã LOINC hợp lệ |
| DocumentReference | Tham chiếu Patient, Practitioner và Encounter tồn tại |
| Attachment | Đường dẫn file và kích thước chính xác; tài liệu có watermark dữ liệu giả |
| ID | ID không trùng trong từng nhóm resource |
| Terminology | Mã sinh ra thuộc các CodeSystem trong terminology contract |
| Tái lập | Cùng seed + scale tạo ra cùng nội dung file |

Chạy riêng test DAT-02:

```bash
python3 -m unittest tests.synthetic.test_dat02 -v
```

Chạy toàn bộ test synthetic:

```bash
python3 -m unittest discover -s tests/synthetic -v
```

## 9. Quy ước đã chốt

| Nội dung | Quy ước |
|---|---|
| Họ tên | Tên đệm và tên chọn theo giới (`names.py`); tỉ lệ giới 49/51 |
| Số điện thoại | `+84900` + 6 chữ số theo thứ tự bệnh nhân, không trùng. `small`/`medium` nằm trong dải `09000xxxxx` của ARC-11; `large` mở rộng sang `0900xxxxxx` (đã ghi vào `docs/00-synthetic-data.md` §3) |
| Patient email | `<slug>-<số thứ tự>@example.test` (thêm số thứ tự để không trùng) |
| Practitioner email | `<slug>-nv<mã nhân viên>@example.test` |
| Tỉ lệ bác sĩ/điều dưỡng | 2:1 |
| Mã vai trò | CodeSystem `practitioner-role` ([practitioner-role CodeSystem](https://shp.example.org/fhir/CodeSystem/practitioner-role)) trong `contracts/terminology`, khớp 01-domain-model §3 |
| Lịch làm việc | 07:00–12:00 và 13:00–17:00; mỗi Slot kéo dài 30 phút |
| Thời gian Schedule | Chia thành các khoảng không vượt quá 30 ngày |
| Appointment | Tập trung nhiều hơn vào giờ cao điểm và ngày thứ Hai; có tỷ lệ hủy và không đến khám giả lập |
| Encounter | Chỉ tạo cho Appointment `fulfilled` |
| Observation | 8 Observation trên mỗi Encounter |
| Tài liệu | SVG có watermark `DỮ LIỆU GIẢ` và `KHÔNG PHẢI HỒ SƠ Y TẾ THẬT` |

## 10. Lưu ý khi sử dụng

- Chỉ sử dụng dữ liệu sinh ra cho phát triển, demo và kiểm thử.
- Không nhập dữ liệu thật vào generator.
- Không commit các file dữ liệu sinh ra trong `dev/.local/synthetic/`.
- Giữ lại `seed` và `scale` để tái tạo cùng bộ dữ liệu.
- Kiểm tra manifest và SHA-256 khi cần xác minh dữ liệu.
- Khi thay đổi generator hoặc terminology contract, cần chạy lại automated tests và kiểm tra tính tái lập.
- Với scale Large, cần đánh giá RAM, dung lượng lưu trữ và thời gian chạy trước khi sinh dữ liệu.

## 11. DAT-02 – Synthetic Clinical Data Generator

DAT-02 mở rộng DAT-01 bằng cách sinh dữ liệu giả cho lịch làm việc, lịch hẹn, lần khám, chẩn đoán, sinh hiệu và tài liệu mẫu.

### 11.1. Cấu trúc generator

Các module DAT-02:

| File | Chức năng |
|---|---|
| `generators/schedules.py` | Sinh Schedule và Slot |
| `generators/appointments.py` | Sinh Appointment và trạng thái lịch hẹn |
| `generators/clinical.py` | Sinh Encounter, Condition và Observation |
| `generators/documents.py` | Sinh DocumentReference và nội dung tài liệu SVG |
| `tests/synthetic/test_dat02.py` | Kiểm thử DAT-02 và tính toàn vẹn tham chiếu |

### 11.2. Quy tắc liên kết dữ liệu

Các generator đảm bảo những quan hệ tham chiếu chính:

- Schedule tham chiếu Practitioner tồn tại.
- Slot tham chiếu Schedule tồn tại.
- Appointment tham chiếu Patient, Practitioner và Slot tồn tại.
- Encounter chỉ được sinh cho Appointment có trạng thái `fulfilled`.
- Condition tham chiếu Patient và Encounter tồn tại.
- Observation tham chiếu Patient và Encounter tồn tại.
- DocumentReference tham chiếu Patient, Practitioner và Encounter tồn tại.
- Attachment của DocumentReference trỏ đến file tài liệu được sinh ra.

Slot được cập nhật trạng thái sau khi sinh Appointment:

- `busy` đối với Appointment `fulfilled` hoặc `noshow`.
- `free` đối với Appointment `cancelled` hoặc không được đặt.

### 11.3. Terminology

Các mã được sử dụng từ `contracts/terminology/code-systems.yaml`:

| CodeSystem | Mục đích |
|---|---|
| `specialty` | Chuyên khoa |
| `cancel-reason` | Lý do hủy |
| `booking-channel` | Kênh đặt lịch |
| `practitioner-role` | Vai trò nhân viên y tế |
| `loinc-vital-signs` | Mã chỉ số sinh hiệu |
| `loinc-document-types` | Mã loại tài liệu |
| `icd10-subset` | Mã chẩn đoán |

Các URL và tập mã được lấy từ contract của dự án. Tập mã LOINC và ICD-10 là tập con phục vụ đồ án, không đại diện cho toàn bộ danh mục chính thức.

### 11.4. Tài liệu mẫu

DAT-02 sinh các tài liệu SVG có watermark:

- `DỮ LIỆU GIẢ`
- `KHÔNG PHẢI HỒ SƠ Y TẾ THẬT`

Mỗi DocumentReference chứa metadata, thông tin loại tài liệu, chủ thể, người tạo, lần khám liên quan và đường dẫn attachment.

### 11.5. Lưu ý khi sử dụng DAT-02

- Chỉ sử dụng dữ liệu sinh ra cho phát triển, demo và kiểm thử.
- Không sử dụng hồ sơ bệnh án hoặc thông tin cá nhân thật.
- Không commit dữ liệu sinh ra trong `dev/.local/synthetic/`.
- Giữ lại `seed` và `scale` để tái tạo dữ liệu.
- Khi thay đổi generator hoặc terminology contract, cần chạy lại automated tests và kiểm tra tính tái lập.

---

**Module:** DAT-01 + DAT-02 – Vietnamese Synthetic Data Generator
**Project:** Smart Hospital Platform
**Output:** Patient, Practitioner, PractitionerRole, Schedule, Slot, Appointment, Encounter, Condition, Observation, DocumentReference và tài liệu SVG
**Default seed:** 42