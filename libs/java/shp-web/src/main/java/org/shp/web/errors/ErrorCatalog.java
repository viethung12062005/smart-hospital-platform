package org.shp.web.errors;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Danh mục mã lỗi duy nhất của hệ thống. Thêm mã mới ở đây VÀ trong bảng §5 của 07; test đối chiếu
 * hai nơi.
 */
public final class ErrorCatalog {
  private static final Map<String, ErrorInfo> BY_CODE = new LinkedHashMap<>();

  static {
    List.of(
            e(
                "validation-failed",
                400,
                "invalid",
                "Dữ liệu gửi lên không hợp lệ.",
                "The request is invalid."),
            e(
                "unsupported-parameter",
                400,
                "not-supported",
                "Tham số tìm kiếm không được hỗ trợ.",
                "Unsupported search parameter."),
            e("unauthenticated", 401, "login", "Bạn cần đăng nhập.", "Authentication is required."),
            e(
                "forbidden",
                403,
                "forbidden",
                "Bạn không có quyền thực hiện thao tác này.",
                "You are not allowed to do this."),
            e(
                "no-care-relationship",
                403,
                "forbidden",
                "Bạn không có quan hệ điều trị với bệnh nhân này.",
                "No care relationship with this patient."),
            e(
                "break-glass-required",
                403,
                "forbidden",
                "Cần truy cập khẩn cấp (break-glass) và nêu lý do.",
                "Break-glass access is required."),
            e("not-found", 404, "not-found", "Không tìm thấy.", "Not found."),
            e(
                "slot-unavailable",
                409,
                "conflict",
                "Khung giờ này vừa có người đặt. Vui lòng chọn khung giờ khác.",
                "This slot has just been taken."),
            e(
                "slot-has-appointment",
                409,
                "conflict",
                "Khung giờ đã có lịch hẹn.",
                "The slot already has an appointment."),
            e(
                "invalid-state-transition",
                409,
                "conflict",
                "Trạng thái hiện tại không cho phép thao tác này.",
                "The current state does not allow this."),
            e(
                "version-conflict",
                412,
                "conflict",
                "Dữ liệu đã được thay đổi bởi người khác. Hãy tải lại.",
                "The resource was modified; reload it."),
            e("file-too-large", 413, "too-costly", "Tệp quá lớn.", "The file is too large."),
            e(
                "invalid-file-type",
                415,
                "invalid",
                "Loại tệp không được phép.",
                "File type not allowed."),
            e(
                "max-future-appointments",
                422,
                "business-rule",
                "Bạn đã đạt số lịch hẹn tương lai tối đa.",
                "Maximum future appointments reached."),
            e(
                "outside-booking-window",
                422,
                "business-rule",
                "Ngoài khoảng thời gian cho phép đặt lịch.",
                "Outside the booking window."),
            e(
                "cancellation-too-late",
                422,
                "business-rule",
                "Đã quá hạn hủy lịch (ít hơn 2 giờ trước giờ khám).",
                "Too late to cancel."),
            e(
                "idempotency-key-reused",
                422,
                "business-rule",
                "Idempotency-Key đã dùng cho yêu cầu khác.",
                "Idempotency-Key was used with a different request."),
            e(
                "duplicate-patient-suspected",
                422,
                "business-rule",
                "Nghi ngờ trùng hồ sơ bệnh nhân.",
                "A duplicate patient is suspected."),
            e(
                "upload-incomplete",
                422,
                "business-rule",
                "Tệp chưa được tải lên đầy đủ.",
                "The upload is incomplete."),
            e(
                "confirmation-expired",
                422,
                "business-rule",
                "Yêu cầu xác nhận đã hết hạn.",
                "The confirmation has expired."),
            e(
                "precondition-required",
                428,
                "invalid",
                "Thiếu If-Match.",
                "If-Match header is required."),
            e(
                "idempotency-key-required",
                428,
                "invalid",
                "Thiếu Idempotency-Key.",
                "Idempotency-Key header is required."),
            e(
                "rate-limited",
                429,
                "throttled",
                "Bạn gửi quá nhiều yêu cầu. Vui lòng thử lại sau.",
                "Too many requests."),
            e(
                "dependency-unavailable",
                503,
                "transient",
                "Hệ thống tạm thời không khả dụng. Vui lòng thử lại.",
                "A dependency is unavailable."),
            e(
                "ai-unavailable",
                503,
                "transient",
                "Tính năng AI tạm thời không khả dụng.",
                "The AI feature is unavailable."),
            e(
                "ai-budget-exceeded",
                503,
                "transient",
                "Đã hết hạn mức AI trong ngày.",
                "The daily AI budget is exhausted."),
            e(
                "internal-error",
                500,
                "exception",
                "Đã xảy ra lỗi hệ thống.",
                "An internal error occurred."))
        .forEach(i -> BY_CODE.put(i.code(), i));
  }

  private ErrorCatalog() {}

  private static ErrorInfo e(String code, int status, String fhir, String vi, String en) {
    return new ErrorInfo(code, status, fhir, vi, en);
  }

  public static Collection<ErrorInfo> all() {
    return BY_CODE.values();
  }

  public static ErrorInfo get(String code) {
    ErrorInfo info = BY_CODE.get(code);
    if (info == null) {
      throw new IllegalArgumentException("Mã lỗi chưa khai báo: " + code);
    }
    return info;
  }
}
