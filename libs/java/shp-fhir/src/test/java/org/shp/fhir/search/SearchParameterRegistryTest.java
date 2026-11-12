package org.shp.fhir.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** Registry khớp các bảng tham số tìm kiếm của tài liệu API 08–10. */
class SearchParameterRegistryTest {
  final SearchParameterRegistry registry = SearchParameterRegistry.standard();

  @Test
  void standardParamsMatchTheApiDesign() {
    assertThat(types("Schedule"))
        .containsExactlyInAnyOrderEntriesOf(
            Map.of(
                "actor", SearchParamType.REFERENCE,
                "specialty", SearchParamType.TOKEN,
                "active", SearchParamType.TOKEN));
    assertThat(types("Slot"))
        .containsExactlyInAnyOrderEntriesOf(
            Map.of(
                "status", SearchParamType.TOKEN,
                "specialty", SearchParamType.TOKEN,
                "practitioner", SearchParamType.REFERENCE,
                "schedule", SearchParamType.REFERENCE,
                "start", SearchParamType.DATE));
    assertThat(types("Appointment"))
        .containsExactlyInAnyOrderEntriesOf(
            Map.of(
                "patient", SearchParamType.REFERENCE,
                "practitioner", SearchParamType.REFERENCE,
                "status", SearchParamType.TOKEN,
                "date", SearchParamType.DATE,
                "specialty", SearchParamType.TOKEN));
    assertThat(types("Patient"))
        .containsExactlyInAnyOrderEntriesOf(
            Map.of(
                "name", SearchParamType.STRING,
                "phone", SearchParamType.TOKEN,
                "identifier", SearchParamType.TOKEN,
                "birthdate", SearchParamType.DATE));
    assertThat(types("Observation"))
        .containsOnlyKeys("patient", "encounter", "category", "code", "date");
    assertThat(types("DocumentReference"))
        .containsOnlyKeys("patient", "encounter", "type", "date", "status");
    assertThat(registry.params("Encounter")).isEmpty();
  }

  @Test
  void referencesPointToTheRightResourceType() {
    assertThat(registry.find("Slot", "schedule"))
        .hasValue(SearchParamDef.reference("schedule", "Schedule"));
    assertThat(registry.find("Appointment", "patient").orElseThrow().target()).isEqualTo("Patient");
    assertThat(registry.find("Observation", "encounter").orElseThrow().target())
        .isEqualTo("Encounter");
    assertThat(registry.find("Slot", "nope")).isEmpty();
  }

  @Test
  void everyRegisteredTypeIsInScope() {
    for (String type : SearchParameterRegistry.RESOURCE_TYPES) {
      assertThat(registry.params(type)).isNotNull();
    }
    assertThatThrownBy(() -> registry.params("Medication"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void builderRejectsDuplicateReservedOrOutOfScopeDefinitions() {
    var b = SearchParameterRegistry.builder();
    assertThatThrownBy(
            () -> b.add("Slot", SearchParamDef.token("status"), SearchParamDef.token("status")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> b.add("Slot", SearchParamDef.token("_count")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> b.add("Medication", SearchParamDef.token("code")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new SearchParamDef("x", SearchParamType.TOKEN, "Patient"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new SearchParamDef("x", SearchParamType.REFERENCE, null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void customRegistryIsUsedByTheParser() {
    var custom =
        SearchParameterRegistry.builder().add("Encounter", SearchParamDef.token("status")).build();
    var parser = new SearchParser(custom, SearchParser.DEFAULT_ZONE);
    assertThat(
            parser.parse("Encounter", Map.of("status", java.util.List.of("finished"))).criteria())
        .hasSize(1);
    assertThat(SearchParamDef.string("name").type()).isEqualTo(SearchParamType.STRING);
    assertThat(SearchParamDef.date("date").target()).isNull();
  }

  private Map<String, SearchParamType> types(String resourceType) {
    var out = new java.util.LinkedHashMap<String, SearchParamType>();
    registry.params(resourceType).forEach((k, v) -> out.put(k, v.type()));
    return out;
  }
}
