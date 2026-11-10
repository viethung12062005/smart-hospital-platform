package org.shp.web.errors;

import java.time.Duration;

/**
 * Business/protocol error with a standard code. Throw from anywhere; the handler turns it into the
 * conventional HTTP response. {@code detail} must not contain PII.
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
   * Custom user-facing message (replaces the default message), no PII; null = use the default
   * message.
   */
  public String detail() {
    return detail;
  }

  public Duration retryAfter() {
    return retryAfter;
  }
}
