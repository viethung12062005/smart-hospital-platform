package org.shp.clinical.domain;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Search normalization rules of 03 §2.3: {@code nameTokens} and the E.164 phone number. Pure
 * functions, no Spring, no I/O.
 */
public final class PatientNormalizer {
  private static final Pattern MARKS = Pattern.compile("\\p{M}+");
  private static final Pattern SPACES = Pattern.compile("\\s+");
  private static final Pattern PHONE_SEPARATORS = Pattern.compile("[\\s.\\-()]");

  /** Vietnamese mobile numbers: national prefix 0, then 3, 5, 7, 8 or 9, then 8 digits. */
  private static final Pattern NATIONAL = Pattern.compile("0([35789]\\d{8})");

  private static final Pattern INTERNATIONAL = Pattern.compile("(?:\\+?84)([35789]\\d{8})");

  private PatientNormalizer() {}

  /**
   * Unicode NFD, remove diacritics, {@code đ} to {@code d}, lowercase, split on whitespace. "Nguyễn
   * Văn An" gives {@code [nguyen, van, an]}.
   */
  public static List<String> nameTokens(String name) {
    if (name == null || name.isBlank()) {
      return List.of();
    }
    String plain =
        MARKS
            .matcher(Normalizer.normalize(name.strip(), Normalizer.Form.NFD))
            .replaceAll("")
            .toLowerCase(Locale.ROOT)
            .replace('đ', 'd');
    return Arrays.stream(SPACES.split(plain)).filter(t -> !t.isEmpty()).toList();
  }

  /**
   * Normalize a Vietnamese mobile number to E.164 ({@code 0901234567} gives {@code +84901234567}).
   * Spaces, dots, dashes and brackets are ignored. Empty when the value is not a valid number.
   */
  public static Optional<String> normalizePhone(String phone) {
    if (phone == null) {
      return Optional.empty();
    }
    String compact = PHONE_SEPARATORS.matcher(phone.strip()).replaceAll("");
    var national = NATIONAL.matcher(compact);
    if (national.matches()) {
      return Optional.of("+84" + national.group(1));
    }
    var international = INTERNATIONAL.matcher(compact);
    return international.matches() ? Optional.of("+84" + international.group(1)) : Optional.empty();
  }
}
