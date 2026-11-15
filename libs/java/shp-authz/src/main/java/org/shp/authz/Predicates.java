package org.shp.authz;

/** Predicate names used in the matrix (match the {@code predicates} keys in matrix.yaml). */
public final class Predicates {
  public static final String OWN = "own";
  public static final String LINKED = "linked";
  public static final String LINKED_RECORDS = "linked-records";
  public static final String GUARDIAN = "guardian";
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
