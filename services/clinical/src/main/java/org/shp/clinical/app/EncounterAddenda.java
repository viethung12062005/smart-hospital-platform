package org.shp.clinical.app;

import ca.uhn.fhir.model.api.TemporalPrecisionEnum;
import java.time.Clock;
import java.util.Date;
import java.util.TimeZone;
import org.hl7.fhir.r4.model.DateTimeType;
import org.hl7.fhir.r4.model.Encounter;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.MarkdownType;
import org.hl7.fhir.r4.model.Reference;
import org.shp.clinical.app.Encounters.Stored;
import org.shp.web.errors.ShpException;
import org.springframework.stereotype.Service;

/**
 * 09 C15 {@code $addendum} (US-029, AC-04.2): after the visit ends, a doctor adds a note without
 * changing anything already written. Each addendum is an {@code extension[addendum]} (01 §4.6):
 * {@code author}, {@code recordedAt}, {@code text}.
 */
@Service
public class EncounterAddenda {
  static final int MAX_TEXT = 4_000;

  private final Encounters encounters;
  private final Clock clock;

  public EncounterAddenda(Encounters encounters, Clock clock) {
    this.encounters = encounters;
    this.clock = clock;
  }

  /**
   * Only on a {@code finished} visit (409 otherwise); {@code text} 1–4000 characters. Everything
   * already in the Encounter, earlier addenda included, stays as it is.
   *
   * @param practitionerId the calling doctor ({@code null} for any other caller → 403)
   */
  public EncounterService.View add(
      String id, String text, String practitionerId, String changedBy) {
    if (practitionerId == null) {
      throw new ShpException("forbidden", "Only a doctor can add an addendum.");
    }
    if (text == null || text.isBlank() || text.length() > MAX_TEXT) {
      throw new ShpException("validation-failed", "text must be 1 to 4000 characters.");
    }
    Stored current = encounters.find(id).orElseThrow(() -> new ShpException("not-found"));
    Encounter e = EncounterService.parse(current.resource());
    if (!"finished".equals(e.getStatus().toCode())) {
      throw new ShpException(
          "invalid-state-transition", "An addendum is only for a finished visit; edit it instead.");
    }
    Extension addendum = new Extension(EncounterService.ADDENDUM);
    addendum.addExtension(new Extension("author", new Reference("Practitioner/" + practitionerId)));
    addendum.addExtension(
        new Extension(
            "recordedAt",
            new DateTimeType(
                Date.from(clock.instant()),
                TemporalPrecisionEnum.SECOND,
                TimeZone.getTimeZone("UTC"))));
    addendum.addExtension(new Extension("text", new MarkdownType(text)));
    e.addExtension(addendum);
    return EncounterService.view(
        encounters.update(
            current,
            EncounterService.toDocument(e),
            current.search(),
            current.version(),
            changedBy,
            null));
  }
}
