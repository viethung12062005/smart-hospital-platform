package org.shp.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The TOTP helper never hands out a code that is about to expire (no Docker needed). */
class KeycloakTotpStepTest {
  static final long STEP = 30_000;

  @Test
  void waitsForTheNextStepOnlyWhenLessThanTheSafeMarginRemains() {
    assertThat(KeycloakTestSupport.totpWaitMillis(10 * STEP)).isZero();
    assertThat(KeycloakTestSupport.totpWaitMillis(10 * STEP + 19_999)).isZero();
    assertThat(KeycloakTestSupport.totpWaitMillis(10 * STEP + 20_000)).isZero();
    assertThat(KeycloakTestSupport.totpWaitMillis(10 * STEP + 20_001)).isEqualTo(9_999);
    assertThat(KeycloakTestSupport.totpWaitMillis(10 * STEP + 29_999)).isEqualTo(1);
  }

  @Test
  void aWaitAlwaysLandsOnTheStartOfTheNextStep() {
    for (long offset = 0; offset < STEP; offset += 137) {
      long now = 7 * STEP + offset;
      long after = now + KeycloakTestSupport.totpWaitMillis(now);
      assertThat(after % STEP == 0 || after - now == 0).isTrue();
      assertThat(STEP - (after % STEP)).isGreaterThanOrEqualTo(KeycloakTestSupport.TOTP_SAFE_MS);
    }
  }
}
