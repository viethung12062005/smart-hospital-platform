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
}
