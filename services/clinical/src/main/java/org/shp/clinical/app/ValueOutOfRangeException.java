package org.shp.clinical.app;

import org.shp.web.errors.ErrorInfo;

/**
 * A vital-signs value outside its plausible range (09 C16) → 422 {@code value-out-of-range}. The
 * code is not yet in the shared error catalog (07 §5, SA-owned); the Observation endpoint (C16)
 * writes {@link #INFO} with the field path as detail. The message names the field, never the value.
 */
public class ValueOutOfRangeException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  public static final ErrorInfo INFO =
      new ErrorInfo(
          "value-out-of-range",
          422,
          "business-rule",
          "Giá trị nằm ngoài khoảng hợp lý.",
          "The value is outside the plausible range.");

  public ValueOutOfRangeException(String path) {
    super(path + " is outside the plausible range.");
  }
}
