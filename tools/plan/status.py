"""Trạng thái hoàn thành task (SA ghi lại khi xong; build.py đọc để vẽ ✓ trong docs/05-plan/by-person).
state: "done" = đạt đúng tiêu chí "Xong khi"; "partial" = làm một phần, `note` nói rõ còn thiếu gì.
commit: commit (repo smart-hospital-platform) chứa phần việc, để truy vết bằng `git show --stat <commit>`."""

STATUS = {
    "ARC-07": ("done", "cd8c789", "11 schema event + ví dụ + envelope; validator khớp danh mục E1–E11"),
    "ARC-06": ("partial", "cd8c789", "Có khung 83 operation cho 7 service, validator OpenAPI; CHƯA có schema thân request/response (dev thêm khi làm endpoint)"),
    "ARC-19": ("done", "cd8c789", "64 rule, validator bất biến an toàn, test của validator"),
    "ARC-20": ("partial", "cd8c789", "Có 12 chuyên khoa, 6 lý do hủy, 10 LOINC sinh hiệu, 6 loại tài liệu, 12 ICD-10; CHƯA đủ ~300 mã ICD-10 và chưa đối chiếu nguồn chính thức"),
    "SHR-22": ("done", "4bf82ef", "libs/dotnet/Shp.Authorization/AuthzMatrix.cs"),
    "SHR-23": ("done", "4bf82ef", "Predicates trong AuthorizationEngine (care/breakglass nhận qua ResourceContext, không gọi mạng)"),
    "SHR-24": ("done", "4bf82ef", "AuthorizationEngine.Decide: default-deny, fields, purpose, 236 test"),
    "SHR-02": ("done", "55c56a5", "libs/dotnet/Shp.Web/Errors: ErrorCatalog (27 mã, test đối chiếu bảng §5 của 07), ShpException, ErrorWriter (OperationOutcome / Problem Details), middleware"),
    "SHR-20": ("partial", "55c56a5", "libs/dotnet/Shp.Web/Auth: JWT Bearer + map roles + MFA nhân viên; test bằng token ký cục bộ; CHƯA thử với Keycloak/Entra thật"),
    "SHR-21": ("done", "55c56a5", "IdentityResolver (cache 60s, miss 5s) + HttpIdentityLinkClient + middleware; chưa có endpoint nội bộ phía clinical (task của dev)"),
    "SHR-06": ("done", "c81d3ec", "libs/dotnet/Shp.Events: CloudEvent + CloudEvents.Create (uuidv7, traceparent, ≤64KB) + EventSchemas.Validate (schema nhúng từ contracts/events); 11 ví dụ hợp đồng được kiểm"),
    "SHR-09": ("partial", "c81d3ec", "AuditRecord/AuditRecordBuilder (che IP, chặn giá trị trong resource) + IAuditSink; CHƯA có sink ghi outbox (SHR-07) và chưa có middleware audit riêng cho FHIR search/read ngoài RequireShpAuthz"),
    "SHR-25": ("done", "c81d3ec", "Shp.Web: RequireShpAuthz (filter) + FindUnprotectedEndpoints (test chặn quên phân quyền); 403/404 theo quy ước"),
    "SHR-27": ("done", "c81d3ec", "AuthzAudit.FromDecision + filter ghi audit cho cho-phép, từ-chối, lỗi; mọi hành động ma trận có ánh xạ audit"),
    "SHR-07": ("partial", "b74e54a", "Shp.Data.Postgres: PostgresOutbox.AddAsync + OutboxRelay (SKIP LOCKED, backoff, đa replica); CHƯA có bản MongoDB và CHƯA có IEventPublisher thật (MassTransit)"),
    "SHR-08": ("partial", "b74e54a", "PostgresInbox.HandleOnceAsync (đúng một lần, rollback cả dấu inbox khi lỗi); CHƯA có MassTransit RabbitMQ/Service Bus, retry/DLQ, bản MongoDB"),
    "SHR-28": ("done", "b74e54a", "PostgresIdempotency: RequireKey, HashBody chuẩn hóa, ClaimAsync (replay, 422 khi khác body, song song, hết hạn 24h); 25 test với PostgreSQL thật"),
}
