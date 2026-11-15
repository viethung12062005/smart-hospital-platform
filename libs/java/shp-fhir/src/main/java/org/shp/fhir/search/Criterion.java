package org.shp.fhir.search;

import java.time.Instant;
import java.util.List;

/**
 * Một tiêu chí đã parse, có kiểu. Nhiều tiêu chí trong một {@link SearchQuery} là AND; nhiều giá
 * trị trong một tiêu chí (dấu phẩy) là OR. Store (SHR-10, SHR-11) dịch thẳng sang truy vấn DB.
 */
public sealed interface Criterion {
  String param();

  /** Mã có hoặc không có system; system null = khớp mọi system. */
  record Token(String system, String code) {}

  record TokenCriterion(String param, List<Token> anyOf) implements Criterion {}

  /** Id của resource {@code targetType} (đã bỏ tiền tố {@code Type/}). */
  record ReferenceCriterion(String param, String targetType, List<String> anyOfIds)
      implements Criterion {}

  /**
   * Khoảng thời gian đã chuẩn hóa từ tiền tố + độ chính xác: giá trị khớp khi {@code from ≤ t <
   * to}. Biên null = không giới hạn phía đó.
   */
  record DateCriterion(String param, Instant from, Instant to) implements Criterion {}

  record StringCriterion(String param, String value) implements Criterion {}
}
