package org.shp.notification.app;

import java.time.Clock;
import java.util.Set;
import org.shp.authz.Principal;
import org.shp.notification.infra.MongoDeviceTokenStore;
import org.shp.web.errors.ShpException;
import org.springframework.stereotype.Service;

/** Register and unregister push tokens (N1, N2). The owner always comes from the identity. */
@Service
public class DeviceService {
  static final Set<String> PLATFORMS = Set.of("ios", "android", "web");
  static final int MAX_TOKEN_LENGTH = 4096;

  private final MongoDeviceTokenStore store;
  private final Clock clock;

  public DeviceService(MongoDeviceTokenStore store, Clock clock) {
    this.store = store;
    this.clock = clock;
  }

  public static String ownerRef(Principal principal) {
    String type = principal.roles().contains("patient") ? "Patient" : "Practitioner";
    return type + "/" + principal.fhirUserId();
  }

  /** Returns the token id; a known token is refreshed, not duplicated. */
  public String register(Principal principal, String platform, String token) {
    if (platform == null || !PLATFORMS.contains(platform)) {
      throw new ShpException("validation-failed", "platform must be ios, android or web");
    }
    if (token == null || token.isBlank() || token.length() > MAX_TOKEN_LENGTH) {
      throw new ShpException(
          "validation-failed", "token must be 1 to " + MAX_TOKEN_LENGTH + " characters");
    }
    return store.upsert(token, platform, ownerRef(principal), clock.instant());
  }

  public void unregister(String tokenId) {
    if (!store.delete(tokenId)) {
      throw new ShpException("not-found");
    }
  }
}
