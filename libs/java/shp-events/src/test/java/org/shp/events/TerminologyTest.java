package org.shp.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** Danh mục nhúng trong jar khớp contracts/terminology (01-domain-model §3). */
class TerminologyTest {
  @Test
  void loadsEveryValueSetFromTheContract() {
    assertThat(Terminology.names())
        .contains(
            "specialty",
            "cancel-reason",
            "booking-channel",
            "loinc-vital-signs",
            "loinc-document-types",
            "icd10-subset");
  }

  @Test
  void icd10SubsetCoversCommonDiagnosesOnly() {
    assertThat(Terminology.concepts("icd10-subset")).hasSizeGreaterThanOrEqualTo(300);
    assertThat(Terminology.contains("icd10-subset", "I10")).isTrue();
    assertThat(Terminology.contains("icd10-subset", "E11")).isTrue();
    assertThat(Terminology.display("icd10-subset", "I10"))
        .isEqualTo("Essential (primary) hypertension");
    // Mã chỉ có ở ICD-10 của WHO, không đối chiếu được với bản chính thức đã dùng: không có trong
    // danh mục
    assertThat(Terminology.contains("icd10-subset", "E14")).isFalse();
    assertThat(Terminology.contains("icd10-subset", null)).isFalse();
    assertThat(Terminology.url("icd10-subset")).isEqualTo("http://hl7.org/fhir/sid/icd-10");
  }

  @Test
  void loincAndShpCodeSystemsKeepTheirLabels() {
    assertThat(Terminology.contains("loinc-vital-signs", "8867-4")).isTrue();
    assertThat(Terminology.contains("loinc-document-types", "8867-4")).isFalse();
    assertThat(Terminology.display("specialty", "cardiology")).isEqualTo("Tim mạch");
    assertThat(Terminology.url("specialty"))
        .isEqualTo("https://shp.example.org/fhir/CodeSystem/specialty");
  }

  @Test
  void unknownValueSetIsAProgrammingError() {
    assertThatThrownBy(() -> Terminology.contains("icd-11", "BA00"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
