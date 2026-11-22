package org.shp.clinical.api;

import java.util.List;

/**
 * Body of C1 (09 §2). Field names follow the design document; everything is checked by {@link
 * org.shp.clinical.app.RegistrationService} and the Patient profile.
 */
public record RegistrationRequest(
    Name name,
    String phone,
    String email,
    String gender,
    String birthDate,
    Address address,
    String language) {

  public record Name(String family, List<String> given) {}

  public record Address(String city, String district) {}
}
