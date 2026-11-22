package org.shp.clinical.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** The vault refuses to start without its keys unless the dev or test profile is active. */
class NationalIdVaultTest {
  private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

  private static MockEnvironment env(String... profiles) {
    MockEnvironment env = new MockEnvironment();
    env.setActiveProfiles(profiles);
    return env;
  }

  @Test
  void missingKeysFailTheStartupInAProductionLikeProfile() {
    assertThatThrownBy(() -> new NationalIdVault("", "", env("prod")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("SHP_CLINICAL_NATIONAL_ID_ENC_KEY");
    assertThatThrownBy(() -> new NationalIdVault("", "", env()))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> new NationalIdVault(KEY, " ", env("uat")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("SHP_CLINICAL_NATIONAL_ID_HASH_KEY");
  }

  @Test
  void devAndTestProfilesFallBackToARandomKey() {
    for (String profile : new String[] {"dev", "test"}) {
      NationalIdVault vault = new NationalIdVault("", "", env(profile));
      assertThat(vault.decrypt(vault.encrypt("012345678901"))).isEqualTo("012345678901");
    }
  }

  @Test
  void configuredKeysAreUsedInAnyProfile() {
    NationalIdVault vault = new NationalIdVault(KEY, KEY, env("prod"));
    assertThat(vault.decrypt(vault.encrypt("012345678901"))).isEqualTo("012345678901");
    assertThat(vault.hash("012345678901")).isEqualTo(vault.hash("012345678901"));
  }
}
