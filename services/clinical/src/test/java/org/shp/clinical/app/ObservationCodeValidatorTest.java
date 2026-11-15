package org.shp.clinical.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.shp.clinical.infra.TerminologyService;
import org.shp.web.errors.ShpException;

class ObservationCodeValidatorTest {
  @Test
  void knownCodePasses() {
    var terminology = mock(TerminologyService.class);
    when(terminology.isKnown("loinc-vital-signs", "8867-4")).thenReturn(true);
    var validator = new ObservationCodeValidator(terminology);

    assertThatCode(() -> validator.validate("8867-4")).doesNotThrowAnyException();
  }

  @Test
  void unknownCodeIsRejectedWithValidationFailed() {
    var terminology = mock(TerminologyService.class);
    when(terminology.isKnown("loinc-vital-signs", "9999-9")).thenReturn(false);
    var validator = new ObservationCodeValidator(terminology);

    assertThatThrownBy(() -> validator.validate("9999-9"))
        .isInstanceOfSatisfying(
            ShpException.class, e -> assertThat(e.info().code()).isEqualTo("validation-failed"));
  }
}
