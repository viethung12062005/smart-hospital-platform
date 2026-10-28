package org.shp.web.errors;

import java.time.Duration;

/**
 * Lỗi nghiệp vụ/giao thức có mã chuẩn. Ném từ bất kỳ đâu; handler đổi thành HTTP đúng quy ước.
 * {@code detail} không chứa PII.
 */
public class ShpException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  private final transient ErrorInfo info;
  private final String detail;
  private final Duration retryAfter;

  public ShpException(String code) {
    this(code, null, null);
  }

  public ShpException(String code, String detail) {
    this(code, detail, null);
  }

  public ShpException(String code, String detail, Duration retryAfter) {
    super(detail != null ? detail : ErrorCatalog.get(code).messageEn());
    this.info = ErrorCatalog.get(code);
    this.detail = detail;
    this.retryAfter = retryAfter;
  }

  public ErrorInfo info() {
    return info;
  }

  /**
   * Thông điệp riêng cho người dùng (thay thông điệp mặc định), không chứa PII; null = dùng mặc
   * định.
   */
  public String detail() {
    return detail;
  }

  public Duration retryAfter() {
    return retryAfter;
  }
}
