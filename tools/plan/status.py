"""Trạng thái hoàn thành task (SA ghi lại khi xong; build.py đọc để vẽ ✓ trong docs/05-plan/by-person).
state: "done" = đạt đúng tiêu chí "Xong khi"; "partial" = làm một phần, `note` nói rõ còn thiếu gì.
commit: commit (repo smart-hospital-platform) chứa phần việc, để truy vết bằng `git show --stat <commit>`."""

STATUS = {
    "ARC-07": ("done", "cd8c789", "11 schema event + ví dụ + envelope; validator khớp danh mục E1–E11"),
    "ARC-06": ("partial", "cd8c789", "Có khung 83 operation cho 7 service, validator OpenAPI; CHƯA có schema thân request/response (dev thêm khi làm endpoint)"),
    "ARC-19": ("done", "cd8c789", "64 rule, validator bất biến an toàn, test của validator"),
    "ARC-20": ("partial", "cd8c789", "Có 12 chuyên khoa, 6 lý do hủy, 10 LOINC sinh hiệu, 6 loại tài liệu, 12 ICD-10; CHƯA đủ ~300 mã ICD-10 và chưa đối chiếu nguồn chính thức"),
    "SHR-22": ("done", "95d2f74", "libs/java/shp-authz: AuthzMatrix (Java)"),
    "SHR-23": ("done", "95d2f74", "Predicates trong AuthorizationEngine (care/breakglass nhận qua ResourceContext, không gọi mạng)"),
    "SHR-24": ("done", "95d2f74", "AuthorizationEngine.decide (Java): default-deny, fields, purpose, 238 test"),
    "SHR-02": ("done", "5e423ab", "libs/java/shp-web/errors: ErrorCatalog (27 mã, test đối chiếu 07 §5), ShpException, ErrorWriter, ShpExceptionHandler"),
    "SHR-20": ("partial", "5e423ab", "shp-web ShpJwt + SecurityFilterChain (issuer, audience, hạn, MFA nhân viên); test bằng token HMAC cục bộ; CHƯA thử với Keycloak/Entra thật"),
    "SHR-21": ("done", "5e423ab", "shp-web IdentityResolver (cache 60s/5s) + HttpIdentityLinkClient + IdentityInterceptor; endpoint nội bộ phía clinical là task dev"),
    "SHR-06": ("done", "5e423ab", "libs/java/shp-events: CloudEvent + CloudEvents.create (UUIDv7, traceparent, ≤64KB) + EventSchemas.validate (schema nhúng)"),
    "SHR-09": ("partial", "5e423ab", "shp-events AuditRecordBuilder (che IP, chặn giá trị trong resource) + AuditSink; CHƯA có sink ghi outbox"),
    "SHR-25": ("done", "5e423ab", "shp-web @RequireShpAuthz + AuthzInterceptor + UnprotectedEndpoints.find (test chặn quên phân quyền)"),
    "SHR-27": ("done", "5e423ab", "AuthzAudit.fromDecision + interceptor ghi audit cho cho-phép, từ-chối, lỗi 5xx"),
    "SHR-07": ("partial", "3764d57", "shp-data-postgres + shp-data-mongo: outbox ghi cùng transaction + relay (SKIP LOCKED / findAndModify có khóa hết hạn); CHƯA có EventPublisher thật (Spring Cloud Stream)"),
    "SHR-08": ("partial", "3764d57", "PostgresInbox + MongoInbox (đúng một lần, rollback cả dấu inbox); CHƯA có consumer Spring Cloud Stream, retry/DLQ"),
    "SHR-28": ("done", "3764d57", "shp-data-postgres PostgresIdempotency + shp-web IdempotencyKeys; 28 test với PostgreSQL thật"),
    "SHR-12": ("done", "3764d57", "shp-data-mongo MongoIdempotency + MongoMessaging.inTransaction (thử lại khi xung đột); 21 test với MongoDB thật"),
    "LOC-03": ("partial", "c909d5b", "6 service Java: health/info/prometheus, OTel, Dockerfile distroless non-root (đã build + chạy thử gateway, scheduling); CHƯA chạy qua pipeline GitHub Actions thật"),
    "LOC-05": ("partial", "c909d5b", "deploy.sh + app-values-local cho Java (SPRING_*, JWKS trong cụm); chart render cho từng service; CHƯA chạy make quickstart trên kind thật"),
    "GW-01": ("partial", "e2558f0", "Spring Cloud Gateway: 7 upstream, chặn /internal, JWT ở biên, Dockerfile c909d5b; CHƯA kiểm truyền trace context qua upstream"),
    "SCF-01": ("partial", "c909d5b", "scheduling: khung Spring Boot + Flyway + outbox/inbox/idempotency PostgreSQL, Dockerfile, svc-scheduling.yml, values-local; CHƯA nối bus (Spring Cloud Stream), values uat/prod ở repo GitOps"),
    "SCF-02": ("partial", "c909d5b", "notification: khung + Mongo outbox/inbox, collection do make up tạo; CHƯA nối bus, values uat/prod"),
    "SCF-03": ("partial", "c909d5b", "clinical: khung + Mongo + MongoIdentityLinkClient; CHƯA nối bus, values uat/prod"),
    "SCF-04": ("partial", "c909d5b", "document: khung + Mongo; CHƯA Blob client, nối bus, values uat/prod"),
    "SCF-05": ("partial", "c909d5b", "audit: khung + Mongo; CHƯA Blob WORM/Log Analytics, nối bus, values uat/prod"),
}
