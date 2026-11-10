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
    ├── names.py
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
| `generators/names.py` | Họ, tên đệm, tên tiếng Việt theo giới; slug cho email |
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

Test chạy bằng `pytest` (hoặc `unittest`), tự động trong CI ở job `synthetic` của workflow `qa` (ruff, mypy strict, test):

```bash
pip install -r tools/contracts/requirements.txt ruff mypy pytest types-PyYAML
ruff check tools/synthetic tests/synthetic && ruff format --check tools/synthetic tests/synthetic
mypy --strict --explicit-package-bases tools/synthetic tests/synthetic
python -m pytest -q tests/synthetic
```

Các nhóm kiểm thử (theo `docs/00-synthetic-data.md` §7):

| Nhóm | Nội dung |
|---|---|
| UUIDv7 | Phiên bản UUID, tái lập, khác seed thì khác |
| Patient | Số lượng; ID/MRN/email/SĐT duy nhất; SĐT chỉ trong dải giả; email `.test`; tên theo giới; tỉ lệ giới 49/51; phân bố tuổi 18/35/29/18; ngày sinh hợp lệ; dấu hiệu giả `(test)`, `(giả)` |
| Dải số điện thoại | Đủ 500.000 số không trùng cho `large`; 100.000 số đầu thuộc dải gốc `09000xxxxx` |
| Practitioner / Role | ID không trùng với nhau và với Patient; tham chiếu tồn tại; chuyên khoa và vai trò lấy từ `contracts/terminology`; tên theo giới |
| Tái lập | Cùng seed + scale cho cùng file đầu ra từng byte |

## 9. Quy ước đã chốt

| Nội dung | Quy ước |
|---|---|
| Họ tên | Tên đệm và tên chọn theo giới (`names.py`); tỉ lệ giới 49/51 |
| Số điện thoại | `+84900` + 6 chữ số theo thứ tự bệnh nhân, không trùng. `small`/`medium` nằm trong dải `09000xxxxx` của ARC-11; `large` mở rộng sang `0900xxxxxx` (đã ghi vào `docs/00-synthetic-data.md` §3) |
| Patient email | `<slug>-<số thứ tự>@example.test` (thêm số thứ tự để không trùng) |
| Practitioner email | `<slug>-nv<mã nhân viên>@example.test` |
| Tỉ lệ bác sĩ/điều dưỡng | 2:1 |
| Mã vai trò | CodeSystem `practitioner-role` (`https://shp.example.org/fhir/CodeSystem/practitioner-role`) trong `contracts/terminology`, khớp 01-domain-model §3 |

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