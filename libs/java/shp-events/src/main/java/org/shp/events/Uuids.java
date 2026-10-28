package org.shp.events;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.UUID;

/**
 * UUIDv7 (RFC 9562): 48 bit thời gian ms + ngẫu nhiên — tăng theo thời gian, thân thiện với B-tree
 * (quy ước ID của thiết kế).
 */
public final class Uuids {
  private static final SecureRandom RANDOM = new SecureRandom();

  private Uuids() {}

  public static UUID v7() {
    return v7(Clock.systemUTC());
  }

  public static UUID v7(Clock clock) {
    long ms = clock.millis();
    long randA = RANDOM.nextInt(1 << 12);
    long msb = (ms << 16) | (0x7L << 12) | randA;
    long lsb = (RANDOM.nextLong() & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;
    return new UUID(msb, lsb);
  }
}
