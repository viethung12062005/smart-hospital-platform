# Đặc tả yêu cầu phần mềm (SRS)

Bộ SRS của Smart Hospital Platform theo cấu trúc mẫu SRS doanh nghiệp (đã bỏ mọi nhận diện thương hiệu của mẫu gốc).
Nội dung viết dưới dạng YAML để review qua Pull Request, kiểm tra tự động và truy vết sang test case; file Word, Excel
và sơ đồ được **sinh ra** từ YAML, không sửa tay.

| Thư mục / file | Nội dung |
|---|---|
| `content/00-document.yaml` | Thông tin tài liệu, trang phê duyệt, lịch sử thay đổi, Chương 1 (Giới thiệu) |
| `content/10-actors-objects.yaml` | Actor, hệ thống bên ngoài, đối tượng dữ liệu, danh sách yêu cầu mức cao |
| `content/20-high-level.yaml` | Chương 2: sơ đồ đối tượng, quy trình, máy trạng thái, nhóm use case, ma trận phân quyền, site map |
| `content/3x-uc-*.yaml` | Chương 3: use case theo module (bảng tóm tắt, các bước, quy tắc nghiệp vụ, tiêu chí nghiệm thu) |
| `content/40-common.yaml` | Use case dùng chung và quy tắc nghiệp vụ dùng chung (phân quyền, audit, không lộ PII, an toàn AI…) |
| `content/50-screens.yaml` | Chương 4: màn hình và thành phần |
| `content/60-nfr.yaml` | Chương 5: yêu cầu phi chức năng (chỉ số, cách đo, loại kiểm thử) |
| `content/70-other.yaml`, `80-messages.yaml` | Chương 6–9: quy ước chung, tích hợp, chuyển đổi dữ liệu, thông điệp, mẫu thông báo |
| `dist/SHP_System_Requirement_Specification_v*.docx` | Tài liệu SRS (sinh ra) |
| `dist/SHP_System_Analysis_Result_v*.xlsx` | Danh sách yêu cầu, actor, đối tượng dữ liệu, chức năng, màn hình (sinh ra) |
| `dist/diagrams/*.drawio`, `dist/diagrams/png/` | Sơ đồ mở/sửa được bằng draw.io và ảnh đã xuất (sinh ra) |

## Sửa và sinh lại

```bash
python -I tools/srs/build.py --check                 # chỉ kiểm tra (CI chạy lệnh này)
python -I tools/srs/build.py                         # sinh lại toàn bộ (cần draw.io desktop để xuất PNG, ~5 phút)
python -I tools/srs/build.py --only act-book,ord     # chỉ vẽ lại vài sơ đồ, các sơ đồ khác dùng PNG có sẵn
```

Cần `pyyaml`, `python-docx`, `openpyxl`, `Pillow`. draw.io desktop được tìm ở `C:\Program Files\draw.io\draw.io.exe`
hoặc biến môi trường `DRAWIO_CLI`. Sau khi mở file Word, chọn *Update Field* để cập nhật mục lục và số trang.

## Quy ước viết nội dung

- Số hiệu (UC n, BR n, CBR n, Màn hình n, EMSG/IEM/CFD/SMSG n, ET n, Hình n) do script đánh theo thứ tự xuất hiện; không gõ số tay.
- Tham chiếu chéo bằng `[[UC:key]]`, `[[BR:key]]`, `[[CBR:key]]`, `[[CMUC:key]]`, `[[SCR:key]]`, `[[MSG:key]]`, `[[ET:key]]`.
  Tham chiếu hỏng làm `--check` thất bại.
- Mỗi use case bắt buộc có: `objective`, `actors`, `trigger`, `pre`, `post`, `trace` (mã UC-xx/AC/BR/NFR/API gốc).
  Các bước đánh số 1..n; mỗi quy tắc nghiệp vụ ghi bước áp dụng `(n)` và bước đó phải tồn tại.
- Dữ liệu sức khỏe là dữ liệu cá nhân nhạy cảm: use case nào đọc/ghi dữ liệu bệnh nhân phải nêu rõ phạm vi quyền,
  hành vi khi bị từ chối và yêu cầu audit (xem `CBR authz`, `CBR audit`, `CBR no-pii`, `CBR ai-safety`).
- Tiêu chí nghiệm thu viết Given/When/Then; mã `AC-…` là khóa để Kế hoạch kiểm thử truy vết test case.
