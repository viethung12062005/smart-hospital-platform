package org.shp.fhir.search;

/** Kiểu tham số tìm kiếm FHIR mà SHP hỗ trợ (ADR-0003, 07 §4). */
public enum SearchParamType {
  /** {@code code} hoặc {@code system|code}; dấu phẩy = OR. */
  TOKEN,
  /** {@code Type/id} hoặc {@code id}; dấu phẩy = OR. */
  REFERENCE,
  /**
   * Ngày/giờ có tiền tố {@code eq|ge|gt|le|lt}; độ chính xác quyết định khoảng (năm, tháng, ngày…).
   */
  DATE,
  /** Chuỗi tự do (ví dụ tên); chuẩn hóa so khớp do service làm. */
  STRING
}
