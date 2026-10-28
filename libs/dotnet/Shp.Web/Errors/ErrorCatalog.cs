using System.Collections.Frozen;

namespace Shp.Web.Errors;

/// <summary>Một mã lỗi chuẩn (docs/03-architecture/detailed-design/07-api-conventions.md §5).</summary>
public sealed record ErrorInfo(string Code, int Status, string FhirIssueType, string MessageVi, string MessageEn);

/// <summary>Danh mục mã lỗi duy nhất của hệ thống. Thêm mã mới ở đây VÀ trong bảng §5 của tài liệu; test đối chiếu hai nơi.</summary>
public static class ErrorCatalog
{
    private static readonly FrozenDictionary<string, ErrorInfo> ByCode = new ErrorInfo[]
    {
        new("validation-failed", 400, "invalid", "Dữ liệu gửi lên không hợp lệ.", "The request is invalid."),
        new("unsupported-parameter", 400, "not-supported", "Tham số tìm kiếm không được hỗ trợ.", "Unsupported search parameter."),
        new("unauthenticated", 401, "login", "Bạn cần đăng nhập.", "Authentication is required."),
        new("forbidden", 403, "forbidden", "Bạn không có quyền thực hiện thao tác này.", "You are not allowed to do this."),
        new("no-care-relationship", 403, "forbidden", "Bạn không có quan hệ điều trị với bệnh nhân này.", "No care relationship with this patient."),
        new("break-glass-required", 403, "forbidden", "Cần truy cập khẩn cấp (break-glass) và nêu lý do.", "Break-glass access is required."),
        new("not-found", 404, "not-found", "Không tìm thấy.", "Not found."),
        new("slot-unavailable", 409, "conflict", "Khung giờ này vừa có người đặt. Vui lòng chọn khung giờ khác.", "This slot has just been taken."),
        new("slot-has-appointment", 409, "conflict", "Khung giờ đã có lịch hẹn.", "The slot already has an appointment."),
        new("invalid-state-transition", 409, "conflict", "Trạng thái hiện tại không cho phép thao tác này.", "The current state does not allow this."),
        new("version-conflict", 412, "conflict", "Dữ liệu đã được thay đổi bởi người khác. Hãy tải lại.", "The resource was modified; reload it."),
        new("file-too-large", 413, "too-costly", "Tệp quá lớn.", "The file is too large."),
        new("invalid-file-type", 415, "invalid", "Loại tệp không được phép.", "File type not allowed."),
        new("max-future-appointments", 422, "business-rule", "Bạn đã đạt số lịch hẹn tương lai tối đa.", "Maximum future appointments reached."),
        new("outside-booking-window", 422, "business-rule", "Ngoài khoảng thời gian cho phép đặt lịch.", "Outside the booking window."),
        new("cancellation-too-late", 422, "business-rule", "Đã quá hạn hủy lịch (ít hơn 2 giờ trước giờ khám).", "Too late to cancel."),
        new("idempotency-key-reused", 422, "business-rule", "Idempotency-Key đã dùng cho yêu cầu khác.", "Idempotency-Key was used with a different request."),
        new("duplicate-patient-suspected", 422, "business-rule", "Nghi ngờ trùng hồ sơ bệnh nhân.", "A duplicate patient is suspected."),
        new("upload-incomplete", 422, "business-rule", "Tệp chưa được tải lên đầy đủ.", "The upload is incomplete."),
        new("confirmation-expired", 422, "business-rule", "Yêu cầu xác nhận đã hết hạn.", "The confirmation has expired."),
        new("precondition-required", 428, "invalid", "Thiếu If-Match.", "If-Match header is required."),
        new("idempotency-key-required", 428, "invalid", "Thiếu Idempotency-Key.", "Idempotency-Key header is required."),
        new("rate-limited", 429, "throttled", "Bạn gửi quá nhiều yêu cầu. Vui lòng thử lại sau.", "Too many requests."),
        new("dependency-unavailable", 503, "transient", "Hệ thống tạm thời không khả dụng. Vui lòng thử lại.", "A dependency is unavailable."),
        new("ai-unavailable", 503, "transient", "Tính năng AI tạm thời không khả dụng.", "The AI feature is unavailable."),
        new("ai-budget-exceeded", 503, "transient", "Đã hết hạn mức AI trong ngày.", "The daily AI budget is exhausted."),
        new("internal-error", 500, "exception", "Đã xảy ra lỗi hệ thống.", "An internal error occurred."),
    }.ToFrozenDictionary(e => e.Code);

    public static IReadOnlyCollection<ErrorInfo> All => ByCode.Values;

    public static ErrorInfo Get(string code) =>
        ByCode.TryGetValue(code, out var info) ? info : throw new ArgumentException($"Mã lỗi chưa khai báo: {code}", nameof(code));
}
