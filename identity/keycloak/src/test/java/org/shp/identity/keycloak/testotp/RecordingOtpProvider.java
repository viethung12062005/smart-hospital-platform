package org.shp.identity.keycloak.testotp;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.shp.identity.keycloak.otp.OtpChallenge;
import org.shp.identity.keycloak.otp.OtpProvider;
import org.shp.identity.keycloak.otp.OtpRateLimitedException;
import org.shp.identity.keycloak.otp.OtpRequest;
import org.shp.identity.keycloak.otp.OtpVerification;

/**
 * TEST ONLY, packaged in {@code shp-keycloak-test-otp.jar} and never in the image: plays the code
 * service inside the Keycloak container of the integration tests. It writes the last code of each
 * destination to {@value #DIR} so the test can read it, and keeps the code service's rules that the
 * sign-up depends on: single use and a lock after five wrong codes. A destination whose local part
 * starts with {@code rate-limited} is refused as if over the limit.
 */
final class RecordingOtpProvider implements OtpProvider {
  static final String DIR = "/opt/keycloak/data/test-otp";
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final Map<String, Pending> PENDING = new ConcurrentHashMap<>();

  private record Pending(String code, int failures) {}

  @Override
  public OtpChallenge send(OtpRequest request) {
    if (request.destination().startsWith("rate-limited")) {
      throw new OtpRateLimitedException("test: over the limit");
    }
    String id = UUID.randomUUID().toString();
    String code = String.format("%06d", RANDOM.nextInt(1_000_000));
    PENDING.put(id, new Pending(code, 0));
    try {
      Files.createDirectories(Path.of(DIR));
      Files.writeString(
          Path.of(DIR, URLEncoder.encode(request.destination(), StandardCharsets.UTF_8)),
          request.purpose().wire()
              + " "
              + request.channel().wire()
              + " "
              + request.locale()
              + " "
              + code);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return new OtpChallenge(id, Instant.now().plusSeconds(300));
  }

  @Override
  public OtpVerification verify(String challengeId, String code) {
    Pending p = PENDING.get(challengeId);
    if (p == null) {
      return OtpVerification.INVALID;
    }
    if (p.failures() >= 5) {
      return OtpVerification.LOCKED;
    }
    if (p.code().equals(code)) {
      PENDING.remove(challengeId);
      return OtpVerification.VALID;
    }
    PENDING.put(challengeId, new Pending(p.code(), p.failures() + 1));
    return p.failures() + 1 >= 5 ? OtpVerification.LOCKED : OtpVerification.INVALID;
  }
}
