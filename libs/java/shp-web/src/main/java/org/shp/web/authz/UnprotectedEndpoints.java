package org.shp.web.authz;

import java.util.List;
import org.springframework.context.ApplicationContext;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Endpoint without a declared permission (missing {@link RequireShpAuthz} and {@link
 * ShpPublicEndpoint}). Actuator endpoints (/health, /info) are not in the list. Used in each
 * service's tests: {@code assertThat(UnprotectedEndpoints.find(ctx)).isEmpty()}.
 */
public final class UnprotectedEndpoints {
  private UnprotectedEndpoints() {}

  public static List<String> find(ApplicationContext ctx) {
    return ctx.getBeansOfType(RequestMappingHandlerMapping.class).values().stream()
        .flatMap(m -> m.getHandlerMethods().entrySet().stream())
        .filter(
            e ->
                !e.getValue().hasMethodAnnotation(RequireShpAuthz.class)
                    && !e.getValue().hasMethodAnnotation(ShpPublicEndpoint.class)
                    && !e.getValue().getBeanType().isAnnotationPresent(ShpPublicEndpoint.class)
                    && !e.getValue().getBeanType().getName().startsWith("org.springframework."))
        .flatMap(e -> e.getKey().getPatternValues().stream())
        .sorted()
        .toList();
  }
}
