# Nhật ký dựng Pha 4 (Platform & DevOps) — để review và làm lại

> Chi tiết từng bước theo thứ tự thời gian: [progress-log.md](progress-log.md).
> Mục đích: ghi lại **từng bước đã làm xong**, lệnh để làm lại, cách kiểm chứng và việc còn lại. Cập nhật mỗi khi xong một bước.
> Mọi dữ liệu là dữ liệu giả. Không có secret nào trong 4 repo (đăng nhập Azure bằng OIDC, mật khẩu local sinh ngẫu nhiên trên máy).
> Chưa chạy `terraform apply` hay tạo tài nguyên cloud nào.

## 0. Bản đồ repo (ADR-0010)
| Repo (bản scratch) | Nhánh | Nội dung |
|---|---|---|
| `smart-hospital-platform-scratch` | `work/initial-baseline` | tài liệu, kế hoạch, code service, bộ công cụ local `dev/` |
| `shp-platform-templates-scratch` | 〃 | chart Helm `microservice`, reusable workflow CI/CD |
| `shp-infra-scratch` | 〃 | Terraform bootstrap + môi trường uat/prod, workflow provision/destroy |
| `shp-gitops-scratch` | 〃 | (chưa có nội dung — bước 9) |

Khi chuyển sang repo thật: copy nội dung, rồi sửa tham chiếu `viethung12062005/shp-platform-templates-scratch@work/initial-baseline`
trong `.github/workflows/svc-*.yml`, `tools/scaffold/*.py`, `dev/scripts/lib.sh` thành repo thật và tag phiên bản (ví dụ `@v0.1.0`).

## 1. Pha 1–3: phân tích, yêu cầu, thiết kế (đã duyệt G1–G3 ngày 2026-10-26)
| Bước | Kết quả | Commit |
|---|---|---|
| BA, FR/NFR, ma trận phân quyền, workload, truy vết | `docs/01-*`, `docs/02-requirements/` | trước 3cace72 |
| 11 ADR + kiến trúc | `docs/03-architecture/` | — |
| Thiết kế chi tiết 01–16 (DB, API, event, authz, MLOps) | `docs/03-architecture/detailed-design/` | 3cace72, 4826847 |
| 74 user story, 317 task, phân theo người | `docs/02-requirements/user-stories.md`, `docs/05-plan/` | c378bfc |

Làm lại kế hoạch: `python3 -I tools/plan/build.py` (tự kiểm: mọi AC có story, mọi API/event có task, task dev ≤ 4h, thứ tự phụ thuộc, tải ≤ 30h/tuần).
Kiểm chứng đã làm: DDL chạy trên PostgreSQL 16 (50 lượt đặt đồng thời → đúng 1 thành công), validator/index/transaction trên MongoDB 8, sơ đồ mermaid parse được.

## 2. ADR-0011: chỉ 2 môi trường cloud (uat, prod) + dev trên kind — commit 3a72b78

## 3. Runbook GitHub — commit 63d873b
`docs/04-devops/github-setup.md`. **Việc của bạn (cần quyền admin GitHub)**: ruleset `main`, CODEOWNERS, Environments
(`deploy-uat`, `deploy-prod`, `infra-*`), GitHub App bot GitOps, Variables. Chưa làm.

## 4. Repo templates — commits c352248, ab2a514 (+ bước 7 dưới)
- `charts/microservice`: Deployment hoặc Argo Rollout, HPA/KEDA, PDB, NetworkPolicy, HTTPRoute, Workload Identity, Key Vault CSI, `values.schema.json`.
- Workflows: `dotnet-ci`, `python-ci`, `container` (hadolint, Trivy, SBOM, cosign keyless, push ACR qua OIDC), `helm-validate`, `terraform-checks`, `pr-checks`, `gitops-promote`, `release-chart`.
- Action ghim theo commit SHA; Trivy chạy bằng image ghim digest.

Làm lại kiểm chứng:
```bash
helm lint charts/microservice && for f in charts/microservice/ci/*.yaml; do helm template t charts/microservice -f $f | kubeconform -strict -summary; done
actionlint
```

## 5. Repo infra — commit 3918ddc
`bootstrap/` (state, ACR, managed identity + federated credential, budget), `modules/{network,monitoring,keyvault,aks,environment}`, `envs/{uat,prod}`,
workflow `env-provision` / `env-destroy` (chờ duyệt), `cost-guard`.
Kiểm chứng: `terraform init -backend=false && terraform validate`, `tflint`, `trivy config` (0 High/Critical). **Không apply.**
**Việc của bạn**: chạy bootstrap một lần bằng tài khoản Azure của bạn, điền GitHub Variables theo `bootstrap/README.md`.

## 6. Bộ công cụ local `dev/` — commit 365405e (+ bước 8)
Cụm kind + chart `shp-local-deps` (PostgreSQL, MongoDB rs, Redis, RabbitMQ, Azurite, Keycloak realm `shp`). Hướng dẫn cho dev: [`dev/README.md`](../../dev/README.md).

## 7. Khung code cho 8 service (walking skeleton) — commit của phase này
| Thành phần | Đường dẫn |
|---|---|
| Cấu hình .NET chung | `global.json` (SDK 10.0.100+), `Directory.Build.props` (nullable, warning = lỗi, analyzer), `Directory.Packages.props` (phiên bản tập trung), `.editorconfig` |
| Thư viện nền | `libs/dotnet/Shp.ServiceDefaults`: ProblemDetails, `/health/live`, `/health/ready`, `/info`, OpenTelemetry (OTLP khi có `OTEL_EXPORTER_OTLP_ENDPOINT`) |
| 6 service .NET | `services/{gateway,scheduling,notification,clinical,document,audit}`: `.slnx`, `src/…Api`, `tests/…Tests`, `Dockerfile` (chiseled, non-root), `deploy/values-local.yaml`, README |
| Gateway | YARP, bảng định tuyến đúng `07-api-conventions.md §1`, `/internal/*` không có route (có test) |
| 2 service Python | `services/{ai,ml}`: FastAPI, `pyproject.toml` (ruff, mypy strict, pytest-cov, pip-audit), Dockerfile non-root |
| Pipeline từng service | `.github/workflows/svc-<svc>.yml`: CI → image (quét/SBOM/ký) → promote uat (chờ duyệt) |
| Bộ sinh | `tools/scaffold/dotnet_service.py`, `tools/scaffold/python_service.py` (không ghi đè file đã có) |

Sửa ở repo templates: `gitops-promote` tự gắn Environment `deploy-<env>` (job gọi reusable workflow không khai báo được `environment`, nên cổng duyệt phải nằm trong workflow được gọi).

Làm lại / kiểm chứng:
```bash
python3 -I tools/scaffold/dotnet_service.py && python3 -I tools/scaffold/python_service.py
for s in gateway scheduling notification clinical document audit; do (cd services/$s && dotnet format --verify-no-changes && dotnet test -c Release); done
for s in ai ml; do (cd services/$s && pip install -e ".[dev]" && ruff check . && ruff format --check . && mypy . && pytest --cov --cov-fail-under=70); done
docker build -f services/scheduling/Dockerfile .   # context = gốc repo cho .NET
docker build services/ml                            # context = thư mục service cho Python
```
Kết quả: 6 × 3 test .NET + 3 test routing gateway xanh; 2 × 3 test Python xanh, ruff/mypy sạch; image scheduling và ml build và chạy (`/info` 200, user không phải root).

## 8. Chạy toàn hệ thống trên máy dev bằng một lệnh — commit của phase này
| Thành phần | Việc |
|---|---|
| `dev/scripts/install-tools.sh` (`make tools`) | tải kind v0.29.0, kubectl v1.33.4, helm v3.18.6 vào `dev/.local/bin`, kiểm sha256, chạy lại không tải lại |
| `dev/scripts/deploy.sh` (`make deploy SVC=<svc|all>`) | build image → `kind load` → `helm upgrade --install` chart `microservice` với `dev/chart/app-values-local.yaml` + values của service; tạo Secret chuỗi kết nối trong cụm |
| `make quickstart` | tools → up → seed-users → deploy all |
| `make apps / app-logs / undeploy / port-forward` | vận hành service trong namespace `shp-apps`; gateway ở `localhost:18000` |
| Azurite | tài khoản `shp` với khóa sinh ngẫu nhiên (`AZURITE_ACCOUNTS`), không dùng khóa emulator công khai |
| Image Python | base `mirror.gcr.io/library/python:3.12-slim` (tránh Docker Hub 429) |

Kiểm chứng đã làm: shellcheck sạch; `install-tools.sh` chạy thật 2 lần; chart render cho 8 service + `kubeconform --strict`.
**Chưa chạy được** `make quickstart` trọn vẹn trên kind (môi trường dựng không chạy được kubelet) → người đầu tiên chạy trên máy thật báo lỗi.

## 8b. Quyết định ngày 2026-10-27
- ADR-0012 **Accepted**: giữ .NET cho 6 service nghiệp vụ, Python cho ai/ml.
- Local dùng **Azurite** thay MinIO: cùng Azure Blob SDK với cloud. Đã sửa LOC-01, DOC-02, AUD-08 (WORM kiểm trên UAT), ADR-0008/0011.

## 9. Việc còn lại
| Việc | Ai | Ghi chú |
|---|---|---|
| Cài đặt GitHub (bước 3), bootstrap Azure (bước 5) | Bạn | cần quyền admin GitHub / Azure |
| Chuyển repo templates/infra sang private | Bạn | đang public |
| Repo GitOps: Argo CD bootstrap, AppProject, ApplicationSet, add-on, Kyverno, `envs/{uat,prod}` | SA | kế tiếp |
| ENV-01: cài Argo CD trong `env-provision` | SA | sau repo GitOps |
| Hợp đồng: `contracts/authz/matrix.yaml`, JSON schema event, OpenAPI, terminology | SA | ARC-06/07/19/20 |
| Thư viện dùng chung cho dev: auth (JWT + role + identity link), outbox/MassTransit, FHIR helper, test fixture Testcontainers | SA | để dev chỉ viết logic |
| Profile local `full` (LOC-08), realm MFA (LOC-02) | SA | |
| Xoay key bị lộ ở repo PoC cũ + xóa lịch sử | Bạn | ngoài phạm vi các repo dự án |
