# Đóng góp code

Áp dụng cho mọi người trong nhóm (SA, D1, D2, D3). Bắt đầu từ [docs/onboarding.md](docs/onboarding.md) nếu bạn mới vào.

## Nguyên tắc không thương lượng
1. **Chỉ dữ liệu giả (synthetic).** Không bao giờ đưa dữ liệu bệnh nhân thật vào repo, test, log hay ảnh chụp màn hình.
2. **Không secret trong repo.**
   - Không có mật khẩu, key, token hay connection string trong code, cấu hình hay log.
   - Ở local, secret được sinh vào `dev/.local/` (đã gitignore); trên cloud, secret nằm ở Key Vault.
3. **Hợp đồng đi trước code.**
   - Hợp đồng gồm API (`contracts/openapi`), event (`contracts/events`), phân quyền (`contracts/authz/matrix.yaml`) và danh mục mã (`contracts/terminology`).
   - Muốn đổi hợp đồng thì sửa trong `contracts/` cùng tài liệu thiết kế, và SA phải đồng ý trước.
4. **Dev chỉ viết logic nghiệp vụ.**
   - Không sửa Dockerfile, `.github/`, `dev/`, Helm values, `libs/java/` hay `config/PlatformConfig.java`.
   - Cần đổi những phần đó thì mở issue cho SA.

## Quy trình một task
1. Lấy task trong `docs/05-plan/by-person/<bạn>.md`. Làm theo thứ tự tuần; task có phụ thuộc chưa xong thì chưa bắt đầu.
   Mỗi task có issue `[ID] ...` trên GitHub (nếu chưa có thì tạo).
2. Tạo nhánh từ `main`: `git switch -c <ID>-mo-ta-ngan`, ví dụ `NTF-02-consumer-booked`. **Một PR cho một task.**
3. Đọc phần thiết kế ghi ở cột "Tài liệu" của task (`docs/03-architecture/detailed-design/`).
4. Viết code kèm test.
5. Chạy kiểm tra giống CI trước khi push (mục dưới).
6. Mở PR theo template, ghi `Closes #<số issue>`. CI phải xanh, cần ít nhất 1 review (CODEOWNERS). Merge kiểu **squash**.

Commit message ngắn, ở thì hiện tại, nói việc làm. Ví dụ: `Create reminders on appointment-booked`.

## Kiểm tra trước khi push
| Phần | Lệnh (chạy ở gốc repo) |
|---|---|
| Service Java | `mvn -B spotless:apply` rồi `mvn -B -pl services/<svc> -am verify` (cần Docker cho Testcontainers) |
| Service Python | trong `services/ai` hoặc `services/ml`: `ruff check . && ruff format --check . && mypy . && pytest --cov` |
| Hợp đồng (nếu sửa `contracts/`) | `pip install -r tools/contracts/requirements.txt && tools/contracts/check_all.sh` |
| Chạy thử trong cụm | `make -C dev deploy SVC=<svc>` (xem `dev/README.md`) |

CI chặn PR khi:
- format sai (Spotless / ruff);
- trình biên dịch có cảnh báo (`-Werror`);
- test đỏ;
- coverage dòng < 70% (JaCoCo / pytest-cov);
- endpoint thiếu `@RequireShpAuthz`;
- hợp đồng không hợp lệ.

## Quy ước code Java
- Bố cục gói: `api/` → `app/` → `domain/`, cùng `infra/`, `messaging/`, `config/`. Xem [overview.md §4](docs/03-architecture/overview.md#4-bên-trong-một-service-java).
- **Transaction chỉ mở ở `app/`.** Thay đổi dữ liệu và `outbox.add(CloudEvents.create(...))` nằm trong cùng một transaction.
- Consumer luôn bọc trong `inbox.handleOnce(...)`, vì một event có thể đến nhiều lần.
- Lỗi nghiệp vụ dùng `throw new ShpException("<mã>")`, với mã có trong danh mục 07 §5. Không tự tạo response lỗi.
- Thời gian dùng `Clock` được inject (test cố định được), lưu UTC. ID dùng `Uuids.v7()`.
- Log không chứa PII. Chỉ log ID, không log tên, số điện thoại, email hay nội dung hồ sơ.
- Test dùng DB thật qua Testcontainers, không mock repository. Domain thuần thì test bằng JUnit.

## Cần giúp
- Câu hỏi thiết kế hoặc hợp đồng: mở issue gắn nhãn `question` và mention SA.
- Lỗi ở `libs/java`, `dev/` hay pipeline: mở issue cho SA, ghi lệnh đã chạy và log lỗi (bỏ secret).
