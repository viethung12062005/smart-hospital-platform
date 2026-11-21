package org.shp.clinical.app;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The vital-signs table of 01 §3 (LOINC code → UCUM unit) with the plausible range of the SRS
 * business rule BR vital-ranges (UC-04 "Record vital signs", 31-uc-clinical.yaml) — outside it the
 * value is rejected, 422 {@code value-out-of-range} — and the adult reference range the server uses
 * for {@code interpretation} when the Observation carries none (01 §4.7).
 *
 * <p>The SRS is authoritative for the plausible ranges: temperature 30–43 °C, pulse 20–250 /min,
 * SpO2 50–100 %, systolic 50–260 and diastolic 30–160 mm[Hg], weight 0.5–300 kg, height 30–250 cm.
 * Respiratory rate and BMI are in the value set but not in that rule, so they keep wide
 * physiological bounds (4–80 /min, 5–100 kg/m2). A measurement without a reference range (weight,
 * height) gets no interpretation.
 */
public final class VitalSigns {
  public static final String LOINC = "http://loinc.org";
  public static final String UCUM = "http://unitsofmeasure.org";

  public static final String BLOOD_PRESSURE_PANEL = "85354-9";
  public static final String SYSTOLIC = "8480-6";
  public static final String DIASTOLIC = "8462-4";

  /**
   * One measured value.
   *
   * @param referenceLow lower bound of the normal range, {@code null} = no interpretation
   * @param referenceHigh upper bound of the normal range, {@code null} = no interpretation
   */
  public record Vital(
      String code,
      String unit,
      BigDecimal plausibleMin,
      BigDecimal plausibleMax,
      BigDecimal referenceLow,
      BigDecimal referenceHigh) {

    public boolean plausible(BigDecimal value) {
      return value.compareTo(plausibleMin) >= 0 && value.compareTo(plausibleMax) <= 0;
    }

    public boolean hasReference() {
      return referenceLow != null && referenceHigh != null;
    }
  }

  // code, UCUM unit, plausible min, max, reference low, high (plausible: BR vital-ranges in the
  // SRS)
  private static final Map<String, Vital> MEASURES =
      Stream.of(
              vital("8867-4", "/min", "20", "250", "60", "100"), // heart rate
              vital("8310-5", "Cel", "30", "43", "36.0", "37.5"), // body temperature
              vital("9279-1", "/min", "4", "80", "12", "20"), // respiratory rate (not in the SRS)
              vital("59408-5", "%", "50", "100", "92", "100"), // SpO2
              vital("29463-7", "kg", "0.5", "300", null, null), // body weight
              vital("8302-2", "cm", "30", "250", null, null), // body height
              vital("39156-5", "kg/m2", "5", "100", "18.5", "24.9"), // BMI (not in the SRS)
              vital(SYSTOLIC, "mm[Hg]", "50", "260", "90", "139"),
              vital(DIASTOLIC, "mm[Hg]", "30", "160", "60", "89"))
          .collect(Collectors.toUnmodifiableMap(Vital::code, Function.identity()));

  private VitalSigns() {}

  /**
   * The measured value of a vital-signs code: every code of the value set except the blood pressure
   * panel, whose values are its two components.
   */
  public static Optional<Vital> measure(String code) {
    return Optional.ofNullable(MEASURES.get(code));
  }

  private static Vital vital(
      String code, String unit, String min, String max, String low, String high) {
    return new Vital(
        code,
        unit,
        new BigDecimal(min),
        new BigDecimal(max),
        low == null ? null : new BigDecimal(low),
        high == null ? null : new BigDecimal(high));
  }
}
