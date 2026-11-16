package org.shp.scheduling.api;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.UUID;
import org.shp.authz.Principal;
import org.shp.authz.ResourceContext;
import org.shp.scheduling.infra.ScheduleRepository;
import org.shp.web.authz.ResourceContextResolver;
import org.shp.web.errors.ShpException;
import org.springframework.stereotype.Component;

/**
 * Resolves facts about a {@code Schedule} resource for matrix-based authorization. Provides {@code
 * practitionerId} to evaluate predicate {@code self-practitioner}.
 */
@Component("scheduleFacts")
public class ScheduleFactsResolver implements ResourceContextResolver {

  private final ScheduleRepository repository;

  public ScheduleFactsResolver(ScheduleRepository repository) {
    this.repository = repository;
  }

  @Override
  public ResourceContext resolve(
      HttpServletRequest request, Map<String, String> pathVariables, Principal principal) {
    String idStr = pathVariables.get("id");
    if (idStr == null || idStr.isBlank()) {
      return ResourceContext.empty();
    }
    UUID id;
    try {
      id = UUID.fromString(idStr);
    } catch (IllegalArgumentException e) {
      throw new ShpException("not-found");
    }

    var record = repository.findById(id).orElseThrow(() -> new ShpException("not-found"));
    return ResourceContext.builder().practitionerId(record.practitionerId().toString()).build();
  }
}
