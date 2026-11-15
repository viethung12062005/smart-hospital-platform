package org.shp.fhir.search;

/**
 * Một tham số tìm kiếm của một resource type.
 *
 * @param target resource type được tham chiếu (chỉ cho {@link SearchParamType#REFERENCE}), null với
 *     kiểu khác
 */
public record SearchParamDef(String name, SearchParamType type, String target) {
  public SearchParamDef {
    if ((type == SearchParamType.REFERENCE) != (target != null)) {
      throw new IllegalArgumentException("target chỉ dùng (và bắt buộc) cho REFERENCE: " + name);
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
