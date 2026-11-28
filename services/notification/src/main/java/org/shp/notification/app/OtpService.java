package org.shp.notification.app;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.bson.Document;
import org.shp.notification.app.OtpChannel.OtpDeliveryException;
import org.shp.notification.infra.MongoOtpStore;
import org.shp.web.errors.ShpException;

/**
 * One-time codes (N8/N9, 11 N8/N9): 6 random digits, only an HMAC is stored, valid for 5 minutes,
 * single use; 5 wrong attempts lock the challenge and its destination for 15 minutes (every
 * challenge of that destination is then refused); at most 5 codes per hour per destination and 20
 * per hour per IP. Only one challenge per destination and purpose is live: a new code makes the
 * earlier ones unusable. Phone numbers are normalized before hashing, so one handset has one
 * destination hash. The code and the destination are never logged.
 */
public class OtpService {
  public static final Duration TTL = Duration.ofMinutes(5);
  public static final Duration LOCK = Duration.ofMinutes(15);
  public static final Duration WINDOW = Duration.ofHours(1);
  public static final int MAX_ATTEMPTS = 5;
  public static final int MAX_PER_DESTINATION = 5;
  public static final int MAX_PER_IP = 20;

  static final Set<String> PURPOSES =
      Set.of("register", "claim-record", "link-relative", "change-phone");
  static final Set<String> HINTS = Set.of("phone", "email");

  /** Result of N8. */
  public record Sent(String challengeId, String channel, Instant expiresAt) {}

  private final MongoOtpStore store;
  private final List<OtpChannel> channels;
  private final Clock clock;
  private final byte[] key;
  private final SecureRandom random = new SecureRandom();

  public OtpService(MongoOtpStore store, List<OtpChannel> channels, Clock clock, byte[] key) {
    this.store = store;
    this.channels = List.copyOf(channels);
    this.clock = clock;
    this.key = key.clone();
  }

  /** N8. {@code clientIp} may be null (then only the per-destination limit applies). */
  public Sent send(
      String purpose, String channelHint, String destination, String locale, String clientIp) {
    if (purpose == null || !PURPOSES.contains(purpose)) {
      throw new ShpException("validation-failed", "Unknown purpose");
    }
    if (channelHint == null || !HINTS.contains(channelHint)) {
      throw new ShpException("validation-failed", "channelHint must be phone or email");
    }
    if (destination == null || destination.isBlank() || destination.length() > 254) {
      throw new ShpException("validation-failed", "destination is required");
    }
    String dest =
        "email".equals(channelHint)
            ? destination.strip().toLowerCase(Locale.ROOT)
            : normalizePhone(destination);
    String lang = "en".equals(locale) ? "en" : "vi";
    Instant now = clock.instant();
    String destHash = hmac("dest:" + dest);
    String ipHash = clientIp == null || clientIp.isBlank() ? null : hmac("ip:" + clientIp.strip());

    if (store.isLocked(destHash, now)) {
      throw new ShpException("otp-locked", null, LOCK);
    }
    // Log first, then count: concurrent requests cannot all slip under the limit.
    Object logId = store.logSend(destHash, ipHash, now);
    Instant since = now.minus(WINDOW);
    if (store.countSends("destHash", destHash, since) > MAX_PER_DESTINATION
        || (ipHash != null && store.countSends("ipHash", ipHash, since) > MAX_PER_IP)) {
      store.removeSend(logId);
      throw new ShpException("otp-rate-limited", null, WINDOW);
    }

    String id = UUID.randomUUID().toString();
    String code = String.format("%06d", random.nextInt(1_000_000));
    Instant expires = now.plus(TTL);
    store.insertChallenge(
        new Document("_id", id)
            .append("purpose", purpose)
            .append("destHash", destHash)
            .append("codeHash", hmac(id + ":" + code))
            .append("attempts", 0)
            .append("consumed", false)
            .append("createdAt", Date.from(now))
            .append("expiresAt", Date.from(expires))
            .append("purgeAt", Date.from(expires)));

    for (OtpChannel channel : channels) {
      if (!channel.handles(channelHint)) {
        continue;
      }
      try {
        channel.send(dest, code, purpose, lang);
        // Single live challenge per destination and purpose: older codes stop working.
        store.consumeOthers(destHash, purpose, id, now);
        return new Sent(id, channel.name(), expires);
      } catch (OtpDeliveryException e) {
        // Try the next adapter (Zalo, then SMS). The exception holds no code or number.
      }
    }
    store.removeChallenge(id);
    throw new ShpException("dependency-unavailable", "The code could not be delivered");
  }

  /** N9: true when the code is correct; throws otp-invalid (422) or otp-locked (429). */
  public boolean verify(String challengeId, String code) {
    if (challengeId == null || code == null || code.isBlank()) {
      throw new ShpException("validation-failed", "challengeId and code are required");
    }
    Instant now = clock.instant();
    Document c =
        store.findChallenge(challengeId).orElseThrow(() -> new ShpException("otp-invalid"));
    Date lockedUntil = c.getDate("lockedUntil");
    if (lockedUntil != null && lockedUntil.toInstant().isAfter(now)) {
      throw new ShpException("otp-locked", null, LOCK);
    }
    // A lock on any challenge of the destination covers all of them, so several challenges for
    // one number cannot multiply the attempt limit.
    String destHash = c.getString("destHash");
    if (destHash != null && store.isLocked(destHash, now)) {
      throw new ShpException("otp-locked", null, LOCK);
    }
    if (c.getInteger("attempts", 0) >= MAX_ATTEMPTS) {
      // The attempt that reached the limit may have stopped before it stored the lock.
      throw new ShpException("otp-locked", null, LOCK);
    }
    if (Boolean.TRUE.equals(c.getBoolean("consumed"))
        || !c.getDate("expiresAt").toInstant().isAfter(now)) {
      throw new ShpException("otp-invalid");
    }
    boolean match =
        MessageDigest.isEqual(
            hmac(challengeId + ":" + code.strip()).getBytes(StandardCharsets.UTF_8),
            c.getString("codeHash").getBytes(StandardCharsets.UTF_8));
    if (match) {
      if (!store.consume(challengeId, MAX_ATTEMPTS, now)) {
        throw new ShpException("otp-invalid");
      }
      return true;
    }
    if (store.failAttempt(challengeId, MAX_ATTEMPTS, LOCK, now)) {
      throw new ShpException("otp-locked", null, LOCK);
    }
    throw new ShpException("otp-invalid");
  }

  /**
   * Normalizes a phone number to E.164 so that {@code +84912345678}, {@code 84912345678}, {@code
   * 0912345678} and {@code 0912 345 678} give one destination: spaces, dots, dashes and parentheses
   * are dropped; a leading {@code 0}, {@code 84} or {@code +84} becomes {@code +84}; any other
   * number must already carry a {@code +} country code. The result is {@code +} and 8 to 15 digits,
   * else validation-failed.
   */
  static String normalizePhone(String raw) {
    String s = raw.strip().replaceAll("[\\s.\\-()]", "");
    String digits;
    if (s.startsWith("+")) {
      digits = s.substring(1);
    } else if (s.startsWith("0")) {
      digits = "84" + s.substring(1);
    } else if (s.startsWith("84")) {
      digits = s;
    } else {
      throw new ShpException("validation-failed", "destination is not a valid phone number");
    }
    if (!digits.matches("\\d{8,15}")) {
      throw new ShpException("validation-failed", "destination is not a valid phone number");
    }
    return "+" + digits;
  }

  private String hmac(String value) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key, "HmacSHA256"));
      return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("HmacSHA256 is unavailable", e);
    }
  }
}
