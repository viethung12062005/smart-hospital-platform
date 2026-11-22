package org.shp.clinical.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** CLN-03: search normalization of 03 §2.3. Fake names only. */
class PatientNormalizerTest {
  @Test
  void removesDiacriticsAndLowercases() {
    assertThat(PatientNormalizer.nameTokens("Nguyễn")).containsExactly("nguyen");
    assertThat(PatientNormalizer.nameTokens("Nguyễn Văn An"))
        .containsExactly("nguyen", "van", "an");
  }

  @Test
  void convertsDStrokeInBothCases() {
    assertThat(PatientNormalizer.nameTokens("Đặng Đình")).containsExactly("dang", "dinh");
  }

  @Test
  void handlesComposedAndDecomposedInput() {
    String decomposed = "Nguyễn"; // NFD form of "Nguyễn"
    assertThat(PatientNormalizer.nameTokens(decomposed)).containsExactly("nguyen");
  }

  @Test
  void splitsOnAnyWhitespaceAndIgnoresEdges() {
    assertThat(PatientNormalizer.nameTokens("  Tran \t Thi   Binh "))
        .containsExactly("tran", "thi", "binh");
  }

  @Test
  void blankNameHasNoTokens() {
    assertThat(PatientNormalizer.nameTokens(null)).isEqualTo(List.of());
    assertThat(PatientNormalizer.nameTokens("   ")).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "0901234567",
        "+84901234567",
        "84901234567",
        "090 123 4567",
        "090.123.4567",
        "(090) 123-4567"
      })
  void normalizesVietnameseMobileNumbersToE164(String input) {
    assertThat(PatientNormalizer.normalizePhone(input)).hasValue("+84901234567");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"", "abc", "0101234567", "090123456", "09012345678", "+15551234567", "+849012345"})
  void rejectsNumbersThatAreNotVietnameseMobile(String input) {
    assertThat(PatientNormalizer.normalizePhone(input)).isEmpty();
  }

  @Test
  void nullPhoneIsRejected() {
    assertThat(PatientNormalizer.normalizePhone(null)).isEmpty();
  }
}
