package org.shp.web.errors;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Đổi mọi lỗi thành định dạng chuẩn. Lỗi lạ → 500 {@code internal-error} và KHÔNG lộ nội dung
 * exception (có thể chứa PII).
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ShpExceptionHandler {
  private static final Logger LOG = LoggerFactory.getLogger(ShpExceptionHandler.class);

  @ExceptionHandler(ShpException.class)
  void shp(ShpException ex, HttpServletRequest req, HttpServletResponse res) throws IOException {
    ErrorWriter.write(req, res, ex.info(), ex.detail(), ex.retryAfter());
  }

  @ExceptionHandler({
    HttpMessageNotReadableException.class,
    MethodArgumentTypeMismatchException.class,
    MissingServletRequestParameterException.class,
    MissingRequestHeaderException.class
  })
  void badRequest(Exception ex, HttpServletRequest req, HttpServletResponse res)
      throws IOException {
    ErrorWriter.write(req, res, ErrorCatalog.get("validation-failed"), null, null);
  }

  @ExceptionHandler(NoResourceFoundException.class)
  void notFound(HttpServletRequest req, HttpServletResponse res) throws IOException {
    ErrorWriter.write(req, res, ErrorCatalog.get("not-found"), null, null);
  }

  @ExceptionHandler({
    HttpRequestMethodNotSupportedException.class,
    HttpMediaTypeNotSupportedException.class
  })
  void unsupported(HttpServletRequest req, HttpServletResponse res) throws IOException {
    ErrorWriter.write(req, res, ErrorCatalog.get("validation-failed"), null, null);
  }

  @ExceptionHandler(Exception.class)
  void unexpected(Exception ex, HttpServletRequest req, HttpServletResponse res)
      throws IOException {
    LOG.error("Unhandled exception", ex);
    ErrorWriter.write(req, res, ErrorCatalog.get("internal-error"), null, null);
  }
}
