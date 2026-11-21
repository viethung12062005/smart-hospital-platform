package org.shp.notification.api;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.shp.authz.Principal;
import org.shp.authz.ResourceContext;
import org.shp.notification.infra.MongoDeviceTokenStore;
import org.shp.web.authz.ResourceContextResolver;
import org.shp.web.errors.ShpException;
import org.springframework.stereotype.Component;

/** Owner facts of a device token, so the matrix can decide "token owner" (N2). */
@Component("deviceFacts")
class DeviceFacts implements ResourceContextResolver {
  private final MongoDeviceTokenStore store;

  DeviceFacts(MongoDeviceTokenStore store) {
    this.store = store;
  }

  @Override
  public ResourceContext resolve(
      HttpServletRequest request, Map<String, String> pathVariables, Principal principal) {
    var device =
        store.find(pathVariables.get("tokenId")).orElseThrow(() -> new ShpException("not-found"));
    String owner = device.getString("ownerRef");
    String id = owner.substring(owner.indexOf('/') + 1);
    return owner.startsWith("Patient/")
        ? ResourceContext.builder().patientId(id).build()
        : ResourceContext.builder().practitionerId(id).build();
  }
}
