package org.shp.web.authz;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.shp.authz.Principal;
import org.shp.authz.ResourceContext;

/**
 * Look up facts about the resource (owner, care relationship, state...) from the request. Throws
 * ShpException("not-found") if absent.
 */
@FunctionalInterface
public interface ResourceContextResolver {
  ResourceContext resolve(
      HttpServletRequest request, Map<String, String> pathVariables, Principal principal);
}
