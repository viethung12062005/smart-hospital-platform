# web — giao diện cho bệnh nhân, bác sĩ, điều dưỡng, quản trị và DPO

Một web app (SPA, PWA) cho cả 5 vai trò: React 18 + TypeScript + Vite + Mantine 7. Quyết định: [ADR-0016](../docs/03-architecture/adr/0016-frontend-web-app.md).
Cần **Node.js 22** (`bash dev/scripts/setup-ubuntu.sh` cài sẵn).

## Chạy

| Muốn                                                                | Lệnh (trong `web/`)                                                                                                            | Cần gì                                                                                                   |
| ------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------ | -------------------------------------------------------------------------------------------------------- |
| Làm giao diện, không cần backend (khuyên dùng khi endpoint chưa có) | `npm ci` rồi `npm run dev:mock` → http://localhost:5173                                                                        | Không. Chọn vai trò ở màn hình đăng nhập; dữ liệu giả từ `src/mocks/`                                    |
| Chạy với backend thật trên cụm local                                | `make -C dev up` + `make -C dev deploy SVC=all` + `make -C dev port-forward` (giữ mở), rồi `npm run dev`                       | Đăng nhập Keycloak bằng người dùng thử (`make -C dev seed-users`, mật khẩu trong `dev/.local/users.env`) |
| Kiểm tra như CI                                                     | `npm run format:check && npm run lint -- --max-warnings=0 && npm run typecheck && npm run test -- --coverage && npm run build` |                                                                                                          |
| Tự định dạng code                                                   | `npm run format`                                                                                                               |                                                                                                          |

`npm run dev` chuyển `/fhir` và `/api` tới gateway `http://localhost:18000` (đổi bằng biến `SHP_GATEWAY_URL`), nên trình duyệt gọi cùng origin và không cần CORS.
Cổng 5173 là cố định vì đó là redirect URI đã khai báo cho client `shp-web` trong realm Keycloak.

## Cấu trúc

| Thư mục                                     | Nội dung                                                                                                             | Ai sửa                                   |
| ------------------------------------------- | -------------------------------------------------------------------------------------------------------------------- | ---------------------------------------- |
| `src/pages/<nhóm>/`                         | Màn hình (patient, staff, clinical)                                                                                  | **Dev** (task WEB-03 trở đi)             |
| `src/components/`                           | Thành phần dùng chung: `PageHeader`, `Card`, `StatusChip`, `KpiTile`, `EmptyState`, `ProblemAlert`, `PatientSummary` | Dev thêm được; báo SA khi đổi cái có sẵn |
| `src/api/`                                  | Lớp gọi API (`client.ts`) và hàm theo service (`scheduling.ts`, `clinical.ts`), danh mục mã (`terminology.ts`)       | Dev thêm hàm mới; **`client.ts` do SA**  |
| `src/auth/`, `src/layout/`, `src/config.ts` | Đăng nhập OIDC, vai trò, menu, khung giao diện, cấu hình lúc chạy                                                    | **SA**                                   |
| `src/i18n/`                                 | Chuỗi giao diện `vi.ts` (mặc định) và `en.ts`                                                                        | Dev (thêm khóa ở cả hai file)            |
| `src/styles/tokens.css`, `src/theme.ts`     | Hệ thiết kế: màu, bảng, chip, KPI, slot, lịch tuần                                                                   | SA (dev đề xuất qua PR)                  |
| `src/mocks/`                                | Máy chủ giả MSW dùng cho `dev:mock` và test                                                                          | Dev thêm handler khi thêm API            |
| `Dockerfile`, `nginx.conf`                  | Image nginx non-root                                                                                                 | **SA**                                   |

## Quy tắc khi viết màn hình

1. **Gọi API qua hàm trong `src/api/`**, không gọi `fetch` trực tiếp: client đã gắn token, `Accept-Language`, đọc lỗi chuẩn.
2. **Thao tác đổi dữ liệu**: tạo `Idempotency-Key` bằng `newIdempotencyKey()` **một lần cho mỗi lần người dùng bấm**, dùng lại khi thử lại cùng nội dung;
   gửi `If-Match` (ETag của bản đang hiển thị) cho thao tác đổi trạng thái. Xem `BookAppointmentPage.tsx`, `MyAppointmentsPage.tsx`.
3. **Lỗi**: hiển thị bằng `<ProblemAlert error={...} />`. Máy chủ đã dịch thông điệp theo ngôn ngữ; xử lý riêng theo `error.code` khi cần (ví dụ `slot-unavailable`).
4. **Chuỗi giao diện**: luôn qua `t('...')`, thêm khóa vào `vi.ts` và `en.ts`. Không viết cứng chữ trong JSX.
5. **Danh mục mã** (chuyên khoa, lý do hủy...): dùng `src/api/terminology.ts` (đọc từ `contracts/terminology`), không tự gõ danh sách.
6. **Quyền**: thêm route trong `src/App.tsx` với `RequireRole`, thêm link trong `src/layout/menus.tsx`. Đây chỉ là điều hướng; backend vẫn kiểm quyền thật.
7. **Dữ liệu**: chỉ dữ liệu giả; không log dữ liệu bệnh nhân ra console; không lưu dữ liệu bệnh nhân vào `localStorage`.
8. **Test**: mỗi màn hình có test luồng chính trong `*.test.tsx` (xem `src/pages/screens.test.tsx`, dùng `renderApp(path, role)`); coverage dòng ≥ 70%.

## Cấu hình theo môi trường

`public/config.json` (local) hoặc ConfigMap trên cụm (`shp-gitops/envs/<env>/web/values.yaml`):
`authority` (issuer OIDC), `clientId`, `scope` (scope mặc định của realm Keycloak đã gắn audience API), `apiBase` (rỗng = cùng origin).

## CI/CD

`.github/workflows/svc-web.yml`: `node-ci` (định dạng, lint, kiểu, test + coverage, build, `npm audit`) → `container` (hadolint, Trivy, SBOM, ký)
→ `gitops-promote` (uat). Trên cụm, HTTPRoute `/` vào web, `/fhir` và `/api` vào gateway.

## Nguồn gốc

Hệ thiết kế và bố cục màn hình kế thừa từ portal PoC trước đây (chép có chọn lọc). Khung `src/layout/` chuyển thể từ Medplum (Apache-2.0): xem [NOTICE](NOTICE).
