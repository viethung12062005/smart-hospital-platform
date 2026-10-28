package org.shp.web.authz;

import java.util.List;
import org.springframework.context.ApplicationContext;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Endpoint chưa khai báo quyền (thiếu {@link RequireShpAuthz} và {@link ShpPublicEndpoint}).
 * Endpoint actuator (/health, /info) không nằm trong danh sách. Dùng trong test của từng service:
 * {@code assertThat(UnprotectedEndpoints.find(ctx)).isEmpty()}.
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
