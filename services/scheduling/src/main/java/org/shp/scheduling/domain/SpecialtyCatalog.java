package org.shp.scheduling.domain;

import java.util.Locale;
import java.util.Set;

/** Valid medical specialty codes per {@code https://shp.example.org/fhir/CodeSystem/specialty}. */
public final class SpecialtyCatalog {
  public static final String SYSTEM = "https://shp.example.org/fhir/CodeSystem/specialty";

  private static final Set<String> CODES =
      Set.of(
          "cardiology",
          "dermatology",
          "endocrinology",
          "ent",
          "gastroenterology",
          "general-surgery",
          "internal-medicine",
          "neurology",
          "obstetrics-gynecology",
          "ophthalmology",
          "orthopedics",
          "pediatrics");

  private SpecialtyCatalog() {}

  public static boolean isValid(String code) {
    return code != null && CODES.contains(code.strip().toLowerCase(Locale.ROOT));
  }
}
