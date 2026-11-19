package org.shp.notification.api;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.shp.notification.app.DeviceService;
import org.shp.notification.app.PreferenceService;
import org.shp.notification.infra.MongoPreferenceStore;
import org.shp.web.auth.IdentityInterceptor;
import org.shp.web.authz.RequireShpAuthz;
import org.shp.web.errors.ShpException;
import org.shp.web.precondition.PreconditionInterceptor;
import org.shp.web.precondition.Versioned;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Devices and preferences (11 §1, N1-N4). Identity comes from the token, never from the body. */
@RestController
@RequestMapping("/api/v1/notifications")
class NotificationController {
  record DeviceRequest(String platform, String token) {}

  record QuietHours(String start, String end) {}

  record PreferencesBody(List<String> channels, String locale, QuietHours quietHours) {}

  /** Response of N3/N4; {@code version} also becomes the ETag and is the next If-Match. */
  record PreferencesView(List<String> channels, String locale, QuietHours quietHours, long version)
      implements Versioned {}

  private final DeviceService devices;
  private final PreferenceService preferences;

  NotificationController(DeviceService devices, PreferenceService preferences) {
    this.devices = devices;
    this.preferences = preferences;
  }

  /** N1: register or refresh a device token. */
  @PutMapping("/devices")
  @RequireShpAuthz(resource = "Device", action = "create")
  Map<String, String> registerDevice(
      @RequestBody(required = false) DeviceRequest body, HttpServletRequest req) {
    if (body == null) {
      throw new ShpException("validation-failed", "A request body is required");
    }
    var principal = IdentityInterceptor.principal(req);
    return Map.of("tokenId", devices.register(principal, body.platform(), body.token()));
  }

  /** N2: unregister; only the token owner reaches this (404 for anyone else). */
  @DeleteMapping("/devices/{tokenId}")
  @RequireShpAuthz(
      resource = "Device",
      action = "delete",
      context = "deviceFacts",
      denyAsNotFound = true)
  void unregisterDevice(@PathVariable String tokenId) {
    devices.unregister(tokenId);
  }

  /** N3: view the caller's own preferences. */
  @GetMapping("/preferences")
  @RequireShpAuthz(resource = "NotificationPreference", action = "read")
  PreferencesView getPreferences(HttpServletRequest req) {
    var principal = IdentityInterceptor.principal(req);
    return toView(preferences.get(principal.fhirUserId()));
  }

  /** N4: replace the caller's own preferences; If-Match is the version, {@code W/"0"} to create. */
  @PutMapping("/preferences")
  @RequireShpAuthz(resource = "NotificationPreference", action = "update")
  PreferencesView changePreferences(
      @RequestBody(required = false) PreferencesBody body, HttpServletRequest req) {
    if (body == null) {
      throw new ShpException("validation-failed", "A request body is required");
    }
    var principal = IdentityInterceptor.principal(req);
    QuietHours quiet = body.quietHours();
    return toView(
        preferences.change(
            principal.fhirUserId(),
            PreconditionInterceptor.expectedVersion(req),
            body.channels(),
            body.locale(),
            quiet == null ? null : quiet.start(),
            quiet == null ? null : quiet.end()));
  }

  private static PreferencesView toView(MongoPreferenceStore.Stored stored) {
    var p = stored.preferences();
    return new PreferencesView(
        p.channels(),
        p.locale(),
        p.hasQuietHours() ? new QuietHours(p.quietStart(), p.quietEnd()) : null,
        stored.version());
  }
}
