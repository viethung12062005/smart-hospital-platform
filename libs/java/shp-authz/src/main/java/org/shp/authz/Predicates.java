package org.shp.authz;

/** Tên predicate dùng trong ma trận (khớp khóa {@code predicates} trong matrix.yaml). */
public final class Predicates {
  public static final String OWN = "own";
  public static final String SELF_PRACTITIONER = "self-practitioner";
  public static final String CARE = "care";
  public static final String BREAK_GLASS = "breakglass";
  public static final String RELEASED = "released";
  public static final String ENCOUNTER_FINISHED = "encounter-finished";
  public static final String UNFINISHED_ENCOUNTER = "unfinished-encounter";
  public static final String BEFORE_2H = "before-2h";
  public static final String NOT_CARE = "not-care";

  private Predicates() {}
}
