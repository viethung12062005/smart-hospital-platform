"""Error code catalog and Problem Details (RFC 9457) for FastAPI (PY-05).

Matches org.shp.web.errors.ErrorCatalog and table 07-api-conventions §5. `detail` is the user-facing message per
Accept-Language and never contains PII or a stack trace.
"""

from dataclasses import dataclass
from typing import Any

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from starlette.exceptions import HTTPException as StarletteHTTPException

from shp_platform.ids import uuid7

FHIR_ERROR_SYSTEM = "https://shp.example.org/fhir/CodeSystem/error"


@dataclass(frozen=True)
class ErrorInfo:
    code: str
    status: int
    fhir_issue_type: str
    message_vi: str
    message_en: str


_CATALOG: dict[str, ErrorInfo] = {
    e.code: e
    for e in (
        ErrorInfo("validation-failed", 400, "invalid", "Dữ liệu gửi lên không hợp lệ.", "The request is invalid."),
        ErrorInfo(
            "unsupported-parameter",
            400,
            "not-supported",
            "Tham số tìm kiếm không được hỗ trợ.",
            "Unsupported search parameter.",
        ),
        ErrorInfo("unauthenticated", 401, "login", "Bạn cần đăng nhập.", "Authentication is required."),
        ErrorInfo(
            "forbidden",
            403,
            "forbidden",
            "Bạn không có quyền thực hiện thao tác này.",
            "You are not allowed to do this.",
        ),
        ErrorInfo(
            "no-care-relationship",
            403,
            "forbidden",
            "Bạn không có quan hệ điều trị với bệnh nhân này.",
            "No care relationship with this patient.",
        ),
        ErrorInfo(
            "break-glass-required",
            403,
            "forbidden",
            "Cần truy cập khẩn cấp (break-glass) và nêu lý do.",
            "Break-glass access is required.",
        ),
        ErrorInfo("not-found", 404, "not-found", "Không tìm thấy.", "Not found."),
        ErrorInfo(
            "invalid-state-transition",
            409,
            "conflict",
            "Trạng thái hiện tại không cho phép thao tác này.",
            "The current state does not allow this.",
        ),
        ErrorInfo(
            "slot-unavailable",
            409,
            "conflict",
            "Khung giờ này vừa có người đặt. Vui lòng chọn khung giờ khác.",
            "This slot has just been taken.",
        ),
        ErrorInfo(
            "slot-has-appointment",
            409,
            "conflict",
            "Khung giờ đã có lịch hẹn.",
            "The slot already has an appointment.",
        ),
        ErrorInfo(
            "version-conflict",
            412,
            "conflict",
            "Dữ liệu đã được thay đổi bởi người khác. Hãy tải lại.",
            "The resource was modified; reload it.",
        ),
        ErrorInfo("file-too-large", 413, "too-costly", "Tệp quá lớn.", "The file is too large."),
        ErrorInfo("invalid-file-type", 415, "invalid", "Loại tệp không được phép.", "File type not allowed."),
        ErrorInfo(
            "idempotency-key-reused",
            422,
            "business-rule",
            "Idempotency-Key đã dùng cho yêu cầu khác.",
            "Idempotency-Key was used with a different request.",
        ),
        ErrorInfo(
            "confirmation-expired",
            422,
            "business-rule",
            "Yêu cầu xác nhận đã hết hạn.",
            "The confirmation has expired.",
        ),
        ErrorInfo(
            "idempotency-key-required", 428, "invalid", "Thiếu Idempotency-Key.", "Idempotency-Key header is required."
        ),
        ErrorInfo(
            "rate-limited",
            429,
            "throttled",
            "Bạn gửi quá nhiều yêu cầu. Vui lòng thử lại sau.",
            "Too many requests.",
        ),
        ErrorInfo(
            "dependency-unavailable",
            503,
            "transient",
            "Hệ thống tạm thời không khả dụng. Vui lòng thử lại.",
            "A dependency is unavailable.",
        ),
        ErrorInfo(
            "ai-unavailable",
            503,
            "transient",
            "Tính năng AI tạm thời không khả dụng.",
            "The AI feature is unavailable.",
        ),
        ErrorInfo(
            "ai-budget-exceeded", 503, "transient", "Đã hết hạn mức AI trong ngày.", "The daily AI budget is exhausted."
        ),
        ErrorInfo("internal-error", 500, "exception", "Đã xảy ra lỗi hệ thống.", "An internal error occurred."),
    )
}


def error_info(code: str) -> ErrorInfo:
    info = _CATALOG.get(code)
    if info is None:
        raise ValueError(f"Unknown error code: {code}")
    return info


def all_errors() -> list[ErrorInfo]:
    return list(_CATALOG.values())


class ShpError(Exception):
    """Business error with a code from the catalog. `detail` (optional) replaces the default message; do not include
    PII."""

    def __init__(self, code: str, detail: str | None = None, retry_after_seconds: int | None = None) -> None:
        self.info = error_info(code)
        self.detail = detail
        self.retry_after_seconds = retry_after_seconds
        super().__init__(code)


def _wants_english(request: Request) -> bool:
    return request.headers.get("accept-language", "vi").strip().lower().startswith("en")


def problem_response(
    request: Request, info: ErrorInfo, detail: str | None = None, retry_after_seconds: int | None = None
) -> JSONResponse:
    trace_id = request.headers.get("x-request-id") or uuid7().hex
    message = detail or (info.message_en if _wants_english(request) else info.message_vi)
    headers = {"X-Request-Id": trace_id}
    if retry_after_seconds is not None or info.status in (429, 503):
        headers["Retry-After"] = str(retry_after_seconds if retry_after_seconds is not None else 5)
    body: dict[str, Any] = {
        "type": f"https://shp.example.org/errors/{info.code}",
        "title": info.code,
        "status": info.status,
        "code": info.code,
        "detail": message,
        "traceId": trace_id,
    }
    return JSONResponse(body, status_code=info.status, headers=headers, media_type="application/problem+json")


_STATUS_CODE = {
    400: "validation-failed",
    401: "unauthenticated",
    403: "forbidden",
    404: "not-found",
    405: "validation-failed",
    413: "file-too-large",
    415: "invalid-file-type",
    429: "rate-limited",
}


def install_error_handlers(app: FastAPI) -> None:
    """Every error returns Problem Details. Unexpected errors return internal-error without exposing the original
    message."""

    async def on_shp(request: Request, exc: Exception) -> JSONResponse:
        assert isinstance(exc, ShpError)
        return problem_response(request, exc.info, exc.detail, exc.retry_after_seconds)

    async def on_validation(request: Request, exc: Exception) -> JSONResponse:
        # Do not echo submitted values (which may be PII); report only the position of the bad field.
        assert isinstance(exc, RequestValidationError)
        fields = sorted({".".join(str(p) for p in err.get("loc", ())) for err in exc.errors()})
        detail = None if not fields else ("Trường không hợp lệ: " + ", ".join(fields))
        return problem_response(request, error_info("validation-failed"), detail)

    async def on_http(request: Request, exc: Exception) -> JSONResponse:
        assert isinstance(exc, StarletteHTTPException)
        code = _STATUS_CODE.get(exc.status_code, "internal-error")
        return problem_response(request, error_info(code))

    async def on_unexpected(request: Request, exc: Exception) -> JSONResponse:
        return problem_response(request, error_info("internal-error"))

    app.add_exception_handler(ShpError, on_shp)
    app.add_exception_handler(RequestValidationError, on_validation)
    app.add_exception_handler(StarletteHTTPException, on_http)
    app.add_exception_handler(Exception, on_unexpected)
