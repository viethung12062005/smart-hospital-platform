package org.shp.scheduling.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class SpecialtyCatalogTest {
  // Maven runs tests with the module directory as the working directory.
  private static final Path CATALOGUE = Path.of("../../contracts/terminology/dist/specialty.json");

  @Test
  @DisplayName("the embedded catalogue is exactly contracts/terminology/dist/specialty.json")
  void catalogueMatchesTheContractFile() throws Exception {
    JsonNode contract = JsonMapper.builder().build().readTree(Files.readString(CATALOGUE));
    assertThat(SpecialtyCatalog.SYSTEM).isEqualTo(contract.get("url").asString());
    assertThat(SpecialtyCatalog.codes())
        .containsExactlyInAnyOrderElementsOf(
            new HashSet<>(contract.get("concepts").propertyNames()));
  }

  @Test
  void validatesCodesCaseInsensitivelyAndRejectsUnknown() {
    assertThat(SpecialtyCatalog.isValid("cardiology")).isTrue();
    assertThat(SpecialtyCatalog.isValid("  Cardiology ")).isTrue();
    assertThat(SpecialtyCatalog.isValid("astrology")).isFalse();
    assertThat(SpecialtyCatalog.isValid(null)).isFalse();
  }
}
