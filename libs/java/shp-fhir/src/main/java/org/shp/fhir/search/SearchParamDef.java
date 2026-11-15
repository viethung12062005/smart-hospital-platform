package org.shp.fhir.search;

/**
 * One search parameter of one resource type.
 *
 * @param target the referenced resource type (only for {@link SearchParamType#REFERENCE}), null for
 *     other types
 */
public record SearchParamDef(String name, SearchParamType type, String target) {
  public SearchParamDef {
    if ((type == SearchParamType.REFERENCE) != (target != null)) {
      throw new IllegalArgumentException(
          "target is only valid (and required) for REFERENCE: " + name);
    }
  }

  public static SearchParamDef token(String name) {
    return new SearchParamDef(name, SearchParamType.TOKEN, null);
  }

  public static SearchParamDef reference(String name, String target) {
    return new SearchParamDef(name, SearchParamType.REFERENCE, target);
  }

  public static SearchParamDef date(String name) {
    return new SearchParamDef(name, SearchParamType.DATE, null);
  }

  public static SearchParamDef string(String name) {
    return new SearchParamDef(name, SearchParamType.STRING, null);
  }
}
