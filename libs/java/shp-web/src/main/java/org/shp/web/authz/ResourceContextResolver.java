package org.shp.web.authz;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.shp.authz.Principal;
import org.shp.authz.ResourceContext;

/**
 * Tra dữ kiện về tài nguyên (chủ sở hữu, quan hệ điều trị, trạng thái...) từ request. Ném
 * ShpException("not-found") nếu không có.
 */
@FunctionalInterface
public interface ResourceContextResolver {
  ResourceContext resolve(
      HttpServletRequest request, Map<String, String> pathVariables, Principal principal);
}
