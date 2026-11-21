package org.shp.scheduling.app;

import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.hl7.fhir.r4.model.Appointment;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Bundle.BundleType;
import org.hl7.fhir.r4.model.Bundle.HTTPVerb;
import org.shp.fhir.FhirJson;
import org.shp.scheduling.domain.ResourceHistoryRecord;
import org.shp.scheduling.infra.AppointmentHistoryRepository;
import org.shp.web.errors.ShpException;
import org.shp.web.precondition.ETags;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Use case for S17: retrieve all stored versions of an {@code Appointment} as a FHIR Bundle. */
@Service
public class AppointmentHistoryService {

  private final AppointmentHistoryRepository repository;

  public AppointmentHistoryService(AppointmentHistoryRepository repository) {
    this.repository = Objects.requireNonNull(repository, "repository must not be null");
  }

  /**
   * Return every version of {@code appointmentId} in a FHIR {@code history} Bundle, newest first.
   *
   * @throws ShpException {@code not-found} (404) when no history exists for the appointment
   */
  @Transactional(readOnly = true)
  public Bundle getHistory(UUID appointmentId) {
    Objects.requireNonNull(appointmentId, "appointmentId must not be null");
    List<ResourceHistoryRecord> versions = repository.findHistory(appointmentId);
    if (versions.isEmpty()) {
      throw new ShpException("not-found");
    }

    Bundle bundle = new Bundle();
    bundle.setType(BundleType.HISTORY);
    bundle.setTotal(versions.size());

    for (ResourceHistoryRecord h : versions) {
      boolean created = "create".equals(h.operation());
      Appointment resource = FhirJson.parse(h.content(), Appointment.class);
      resource.setId(appointmentId.toString());
      resource
          .getMeta()
          .setVersionId(Integer.toString(h.versionId()))
          .setLastUpdated(Date.from(h.lastUpdated()))
          .setSource(h.changedBy());

      var entry = bundle.addEntry();
      entry.setFullUrl("Appointment/" + appointmentId + "/_history/" + h.versionId());
      entry.setResource(resource);
      entry
          .getRequest()
          .setMethod(created ? HTTPVerb.POST : HTTPVerb.PUT)
          .setUrl(created ? "Appointment" : "Appointment/" + appointmentId);
      entry
          .getResponse()
          .setStatus(created ? "201" : "200")
          .setEtag(ETags.format(h.versionId()))
          .setLastModified(Date.from(h.lastUpdated()));
    }
    return bundle;
  }
}
