package org.shp.clinical.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.shp.clinical.app.ClinicalReads.CareRow;
import org.shp.clinical.app.ClinicalReads.PatientFacts;
import org.shp.clinical.app.ClinicalReads.RoleView;

/** CLN-12 (09 §4; 15 §4, §5): the answers of the internal API. Fake data only. */
class InternalLookupsTest {
  static final String PRAC = "0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e02";
  static final String PAT = "0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e6f";
  // 2026-11-17 22:00 UTC is already 2026-11-18 in Ho Chi Minh City.
  static final Instant NOW = Instant.parse("2026-11-17T22:00:00Z");

  final ClinicalReads reads = mock(ClinicalReads.class);
  final InternalLookups lookups = new InternalLookups(reads, Clock.fixed(NOW, ZoneOffset.UTC));

  @Test
  void aValidCareRelationshipIsCare() {
    Instant until = Instant.parse("2027-11-20T02:00:00Z");
    when(reads.careRelationship(PRAC, PAT, NOW))
        .thenReturn(Optional.of(new CareRow(until, "encounter")));
    assertThat(lookups.care(PRAC, PAT))
        .isEqualTo(new InternalLookups.CareCheck(true, "encounter", until));
    verify(reads, never()).breakGlass(any(), any(), any());
  }

  @Test
  void anUnexpiredBreakGlassSessionIsCareWhenThereIsNoRelationship() {
    Instant until = NOW.plusSeconds(1800);
    when(reads.careRelationship(PRAC, PAT, NOW)).thenReturn(Optional.empty());
    when(reads.breakGlass(PRAC, PAT, NOW)).thenReturn(Optional.of(until));
    assertThat(lookups.care(PRAC, PAT))
        .isEqualTo(new InternalLookups.CareCheck(true, "break-glass", until));
  }

  @Test
  void neitherSourceIsNoCare() {
    when(reads.careRelationship(PRAC, PAT, NOW)).thenReturn(Optional.empty());
    when(reads.breakGlass(PRAC, PAT, NOW)).thenReturn(Optional.empty());
    assertThat(lookups.care(PRAC, PAT)).isEqualTo(new InternalLookups.CareCheck(false, null, null));
  }

  @Test
  void rolesOfAnUnknownPractitionerAreNotFound() {
    when(reads.practitionerExists(PRAC)).thenReturn(false);
    assertThat(lookups.roles(PRAC)).isEmpty();
    verify(reads, never()).roles(any());
  }

  @Test
  void rolesIncludeInactiveOnesAndMayBeEmpty() {
    var roles =
        List.of(
            new RoleView("r1", "cardiology", "doctor", true),
            new RoleView("r2", "pediatrics", "doctor", false));
    when(reads.practitionerExists(PRAC)).thenReturn(true);
    when(reads.roles(PRAC)).thenReturn(roles);
    assertThat(lookups.roles(PRAC)).contains(roles);

    when(reads.roles(PRAC)).thenReturn(List.of());
    assertThat(lookups.roles(PRAC)).contains(List.of());
  }

  @Test
  void contactCarriesPhoneEmailAndLocale() {
    when(reads.patient(PAT))
        .thenReturn(
            Optional.of(
                new PatientFacts(
                    LocalDate.parse("1985-03-14"), "+84900012345", "an@example.test", "en")));
    assertThat(lookups.contact(PAT))
        .contains(new InternalLookups.Contact("+84900012345", "an@example.test", "en"));
  }

  @Test
  void contactWithoutLanguageDefaultsToVietnamese() {
    when(reads.patient(PAT))
        .thenReturn(Optional.of(new PatientFacts(null, "+84900012345", null, null)));
    assertThat(lookups.contact(PAT))
        .contains(new InternalLookups.Contact("+84900012345", null, "vi"));
  }

  @Test
  void unknownPatientHasNoContactAndNoAgeBand() {
    when(reads.patient(PAT)).thenReturn(Optional.empty());
    assertThat(lookups.contact(PAT)).isEmpty();
    assertThat(lookups.ageBand(PAT)).isEmpty();
  }

  @Test
  void ageBandUsesTheHospitalDate() {
    // Turns 40 on 2026-11-18 local time, while it is still 2026-11-17 in UTC.
    when(reads.patient(PAT))
        .thenReturn(Optional.of(new PatientFacts(LocalDate.parse("1986-11-18"), null, null, null)));
    assertThat(lookups.ageBand(PAT)).contains("40-49");
  }

  @ParameterizedTest
  @CsvSource({
    "2026-11-18, 0-9",
    "2017-11-19, 0-9",
    "2016-11-18, 10-19",
    "1985-03-14, 40-49",
    "1926-11-18, 100-109",
  })
  void ageBandIsTenYearsWide(String birthDate, String band) {
    assertThat(InternalLookups.ageBand(LocalDate.parse(birthDate), LocalDate.parse("2026-11-18")))
        .isEqualTo(band)
        .matches("^\\d{1,3}-\\d{1,3}$");
  }

  @Test
  void missingOrFutureBirthDateIsUnknown() {
    LocalDate today = LocalDate.parse("2026-11-18");
    assertThat(InternalLookups.ageBand(null, today)).isEqualTo("unknown");
    assertThat(InternalLookups.ageBand(today.plusDays(1), today)).isEqualTo("unknown");
  }
}
