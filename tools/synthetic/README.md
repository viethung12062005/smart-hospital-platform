# DAT-01 – Vietnamese Synthetic Data Generator

## 1. Giới thiệu

DAT-01 là bộ sinh dữ liệu giả tiếng Việt cho dự án Smart Hospital Platform.

Mục tiêu:
- Sinh dữ liệu giả cho bệnh nhân, bác sĩ và điều dưỡng.
- Không sử dụng dữ liệu cá nhân hoặc hồ sơ bệnh án thật.
- Hỗ trợ nhiều quy mô dữ liệu phục vụ phát triển và kiểm thử.
- Đảm bảo tính tái lập: cùng `seed` và `scale` phải tạo ra cùng dữ liệu.
- Đảm bảo tính toàn vẹn tham chiếu giữa các tài nguyên.

## 2. Danh sách tài nguyên và quy mô

| Resource | Small | Medium | Large |
|---|---:|---:|---:|
| Patient | 200 | 20.000 | 500.000 |
| Practitioner | 12 | 120 | 600 |
| PractitionerRole | 12 | 120 | 600 |

Trong đó:
- **Patient:** thông tin bệnh nhân giả.
- **Practitioner:** thông tin nhân viên y tế.
- **PractitionerRole:** vai trò chuyên môn của nhân viên y tế, gồm bác sĩ (`doctor`) và điều dưỡng (`nurse`).

## 3. Cấu trúc thư mục

```text
tools/synthetic/
├── README.md
├── generate.py
└── generators/
    ├── __init__.py
    ├── ids.py
    ├── patients.py
    └── practitioners.py

tests/
└── synthetic/
    └── test_generators.py
```

Chức năng các thành phần:

| File | Chức năng |
|---|---|
| `generate.py` | Giao diện dòng lệnh, điều phối sinh dữ liệu và tạo manifest |
| `generators/ids.py` | Sinh UUIDv7 có tính tái lập |
| `generators/patients.py` | Sinh dữ liệu Patient |
| `generators/practitioners.py` | Sinh Practitioner và PractitionerRole |
| `tests/synthetic/test_generators.py` | Kiểm thử các generator |

## 4. Yêu cầu môi trường

- Python >= 3.12.
- Không yêu cầu thư viện bên thứ ba cho chức năng sinh dữ liệu.
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
│   └── manifest.json
├── medium-seed-42/
│   ├── patients.json
│   ├── practitioners.json
│   ├── practitioner_roles.json
│   └── manifest.json
└── large-seed-42/
    ├── patients.json
    ├── practitioners.json
    ├── practitioner_roles.json
    └── manifest.json
```

Ý nghĩa các file:

| File | Nội dung |
|---|---|
| `patients.json` | Danh sách Patient |
| `practitioners.json` | Danh sách Practitioner |
| `practitioner_roles.json` | Danh sách PractitionerRole |
| `manifest.json` | Thông tin generator, scale, seed, số lượng bản ghi và SHA-256 |

Các file dữ liệu được mã hóa UTF-8 để hỗ trợ tiếng Việt có dấu.

Dữ liệu sinh ra là artifact phục vụ phát triển và kiểm thử tại máy local, không đưa vào Git.

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

Sử dụng thư viện `unittest` có sẵn trong Python.

Chạy toàn bộ test DAT-01:

```bash
python3 -m unittest discover \
    -s tests/synthetic \
    -p "test_*.py" \
    -v
```

Các nhóm kiểm thử:

| Nhóm | Nội dung |
|---|---|
| UUIDv7 | Kiểm tra phiên bản UUID và tính tái lập |
| Patient | Kiểm tra số lượng, ID, email, số điện thoại |
| Practitioner | Kiểm tra số lượng và tính duy nhất của ID |
| PractitionerRole | Kiểm tra mã vai trò và tham chiếu Practitioner |
| Reproducibility | Kiểm tra cùng seed và khác seed |

### 8.1. Kết quả kiểm thử hiện tại

| Hạng mục | Kết quả |
|---|---|
| Automated tests | 18/18 PASS |
| Small generation | PASS |
| Medium generation | PASS |
| Large generation | PASS |
| JSON validation | PASS trên Small |
| Gender distribution | Đã kiểm tra trên Medium |
| Age distribution | Đã kiểm tra trên Medium |
| ID uniqueness | Đã kiểm tra trên Medium |
| Referential integrity | Đã kiểm tra trên Medium |
| Reproducibility | Đã xác nhận trên Small và Medium |

Lưu ý: Large đã được xác nhận sinh thành công và có số lượng đúng theo manifest. Chưa thực hiện đầy đủ các kiểm tra chất lượng trên toàn bộ 500.000 Patient.

## 9. Các điểm cần thống nhất với ARC-11

Một số quy ước trong implementation cần được xác nhận với người phụ trách đặc tả trước khi chốt:

| Nội dung | Implementation hiện tại | Điểm cần xác nhận |
|---|---|---|
| Patient email | `<slug>-<sequence>@example.test` | ARC-11 mô tả `<slug>@example.test` |
| Practitioner email | Slug kèm mã nhân viên | Cần xác nhận định dạng |
| Doctor/nurse ratio | 2:1 | Chưa có tỷ lệ chính thức trong ARC-11 |
| PractitionerRole coding system | `urn:shp:practitioner-role` | Cần xác nhận URI chính thức |
| UUIDv7 | UUIDv7 xác định từ seed | Cần thống nhất cách thể hiện dấu hiệu nhận diện dữ liệu giả |
| Phone | Dải giả `09000xxxxx`, chuyển sang E.164 | Dải số có giới hạn 100.000 giá trị, có thể lặp ở Large |

Không tự ý thay đổi các quy ước này nếu chưa có thống nhất từ nhóm.

## 10. Lưu ý khi sử dụng

- Chỉ sử dụng dữ liệu sinh ra cho phát triển, demo và kiểm thử.
- Không nhập dữ liệu thật vào generator.
- Không commit các file JSON dữ liệu sinh ra.
- Giữ lại `seed` và `scale` để tái tạo cùng bộ dữ liệu.
- Kiểm tra manifest và SHA-256 khi cần xác minh dữ liệu.
- Khi thay đổi generator, cần chạy lại automated tests và kiểm tra tính tái lập.

---

**Module:** DAT-01 – Vietnamese Synthetic Data Generator
**Project:** Smart Hospital Platform
**Output:** Patient, Practitioner, PractitionerRole
**Default seed:** 42