package org.shp.clinical.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * A doctor-patient care relationship (15 §4; 03 §2.2 {@code care_relationships}). Each source is an
 * appointment or an encounter; a source keeps care for 365 days after its time ({@code start} of
 * the appointment, {@code period.start} of the encounter). {@code validUntil} is the latest of
 * those among the sources that still count.
 */
public record CareRelationship(String practitionerId, String patientId, List<Source> sources) {

  public static final Duration VALIDITY = Duration.ofDays(365);

  public static final String APPOINTMENT = "appointment";
  public static final String ENCOUNTER = "encounter";

  /** Source statuses that no longer give care: a cancelled or missed appointment. */
  public static final Set<String> ENDED = Set.of("cancelled", "noshow");

  /**
   * One source. {@code version} is the version of the resource that last changed it (13 §1:
   * consumers skip an event whose version is not newer than the one already applied).
   */
  public record Source(String type, String id, String status, Instant at, long version) {
    public Source {
      Objects.requireNonNull(type, "type");
      Objects.requireNonNull(id, "id");
      Objects.requireNonNull(status, "status");
      Objects.requireNonNull(at, "at");
    }

    boolean counts() {
      return !ENDED.contains(status);
    }
  }

  public CareRelationship {
    sources = List.copyOf(sources);
  }

  public static CareRelationship empty(String practitionerId, String patientId) {
    return new CareRelationship(practitionerId, patientId, List.of());
  }

  /** {@code _id} of the document (03 §2.2). */
  public String id() {
    return practitionerId + "|" + patientId;
  }

  public Optional<Source> source(String type, String id) {
    return sources.stream().filter(s -> s.type().equals(type) && s.id().equals(id)).findFirst();
  }

  /**
   * Adds or replaces the source with the same type and id. Returns this relationship unchanged when
   * the stored source already has the same or a newer version (duplicate or out-of-order event).
   */
  public CareRelationship with(Source source) {
    var current = source(source.type(), source.id());
    if (current.isPresent() && current.get().version() >= source.version()) {
      return this;
    }
    List<Source> next = new ArrayList<>();
    for (Source s : sources) {
      if (!(s.type().equals(source.type()) && s.id().equals(source.id()))) {
        next.add(s);
      }
    }
    next.add(source);
    next.sort(Comparator.comparing(Source::at).thenComparing(Source::id));
    return new CareRelationship(practitionerId, patientId, next);
  }

  /**
   * Sets the status of an existing source (cancel, no-show, fulfilled). Unknown source or a version
   * that is not newer: unchanged.
   */
  public CareRelationship withStatus(String type, String id, String status, long version) {
    return source(type, id)
        .map(s -> with(new Source(s.type(), s.id(), status, s.at(), version)))
        .orElse(this);
  }

  /**
   * Ends a source whatever its version: E3 replaces the old appointment but carries the version of
   * the NEW one, which says nothing about the old one's version. Unknown source: unchanged.
   */
  public CareRelationship ended(String type, String id, String status) {
    if (!ENDED.contains(status)) {
      throw new IllegalArgumentException("not an ending status: " + status);
    }
    return source(type, id)
        .map(s -> replace(new Source(s.type(), s.id(), status, s.at(), s.version())))
        .orElse(this);
  }

  private CareRelationship replace(Source source) {
    List<Source> next = new ArrayList<>();
    for (Source s : sources) {
      next.add(s.type().equals(source.type()) && s.id().equals(source.id()) ? source : s);
    }
    return new CareRelationship(practitionerId, patientId, next);
  }

  /** {@code max(at) + 365 days} over the sources that still count; empty = no care. */
  public Optional<Instant> validUntil() {
    return sources.stream()
        .filter(Source::counts)
        .map(Source::at)
        .max(Comparator.naturalOrder())
        .map(at -> at.plus(VALIDITY));
  }
}
