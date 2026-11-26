package org.shp.scheduling.infra;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import org.shp.scheduling.app.SlotGenerator;
import org.shp.scheduling.domain.SlotRecord;
import org.shp.scheduling.domain.SlotSearchCriteria;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Redis read-through cache for {@link SlotSearchRepository} (S8 / NFR-S2 / SCH-27).
 *
 * <p>Stores normalized query results with a 5-second TTL using epoch-versioned keys so slot-state
 * changes invalidate affected scopes immediately without blocking on {@code KEYS}/{@code SCAN}. Any
 * Redis failure fails open to PostgreSQL and is recorded in Micrometer metrics ({@code
 * shp_slot_cache_requests_total}, {@code shp_slot_cache_hit_ratio}, {@code
 * shp_slot_cache_invalidations_total}).
 */
@Component
public class SlotSearchCache {
  private static final Logger LOG = LoggerFactory.getLogger(SlotSearchCache.class);
  private static final Duration EPOCH_TTL = Duration.ofHours(24);
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final SlotSearchRepository repository;
  private final StringRedisTemplate redis;
  private final boolean enabled;
  private final Duration ttl;
  private final Counter hits;
  private final Counter misses;
  private final Counter errors;
  private final Counter bypasses;
  private final Counter invalidationsOk;
  private final Counter invalidationsError;
  private final HitStats stats = new HitStats(new AtomicLong(), new AtomicLong());

  @Autowired
  public SlotSearchCache(
      SlotSearchRepository repository,
      ObjectProvider<StringRedisTemplate> redisProvider,
      MeterRegistry meters,
      @Value("${shp.slot-cache.enabled:true}") boolean enabled,
      @Value("${shp.slot-cache.ttl:5s}") Duration ttl) {
    this.repository = Objects.requireNonNull(repository, "repository must not be null");
    this.redis = redisProvider.getIfAvailable();
    this.enabled = enabled;
    this.ttl = Objects.requireNonNull(ttl, "ttl must not be null");
    Objects.requireNonNull(meters, "meters must not be null");
    this.hits =
        Counter.builder("shp_slot_cache_requests_total")
            .description("Slot search cache lookup outcomes")
            .tag("outcome", "hit")
            .register(meters);
    this.misses =
        Counter.builder("shp_slot_cache_requests_total")
            .description("Slot search cache lookup outcomes")
            .tag("outcome", "miss")
            .register(meters);
    this.errors =
        Counter.builder("shp_slot_cache_requests_total")
            .description("Slot search cache lookup outcomes")
            .tag("outcome", "error")
            .register(meters);
    this.bypasses =
        Counter.builder("shp_slot_cache_requests_total")
            .description("Slot search cache lookup outcomes")
            .tag("outcome", "bypass")
            .register(meters);
    this.invalidationsOk =
        Counter.builder("shp_slot_cache_invalidations_total")
            .description("Slot search cache invalidation outcomes")
            .tag("outcome", "ok")
            .register(meters);
    this.invalidationsError =
        Counter.builder("shp_slot_cache_invalidations_total")
            .description("Slot search cache invalidation outcomes")
            .tag("outcome", "error")
            .register(meters);
    Gauge.builder("shp_slot_cache_hit_ratio", stats, HitStats::hitRatio)
        .description("Ratio of slot search cache hits to total lookups (hit + miss)")
        .register(meters);
  }

  public double hitRatio() {
    return stats.hitRatio();
  }

  private record HitStats(AtomicLong hits, AtomicLong misses) {
    double hitRatio() {
      long h = hits.get();
      long total = h + misses.get();
      return total == 0 ? 0.0 : (double) h / total;
    }
  }

  public List<SlotRecord> search(
      SlotSearchCriteria criteria, boolean fromDefaultedNow, boolean toDefaultedNow, Instant now) {
    Objects.requireNonNull(criteria, "criteria must not be null");
    Objects.requireNonNull(now, "now must not be null");

    if (!enabled || redis == null) {
      bypasses.increment();
      return repository.search(criteria);
    }

    String queryKey;
    try {
      String epochToken = currentEpochToken(epochKeysFor(criteria));
      queryKey =
          "shp:slot:q:"
              + epochToken
              + ":"
              + queryDigest(criteria, fromDefaultedNow, toDefaultedNow);
      String raw = redis.opsForValue().get(queryKey);
      if (raw != null) {
        CachedSlot[] cached = JSON.readValue(raw, CachedSlot[].class);
        List<SlotRecord> result = new ArrayList<>(cached.length);
        for (CachedSlot entry : cached) {
          SlotRecord r = entry.toRecord();
          if (!fromDefaultedNow || !r.startAt().isBefore(now)) {
            result.add(r);
          }
        }
        hits.increment();
        stats.hits().incrementAndGet();
        return result;
      }
    } catch (RuntimeException e) {
      errors.increment();
      LOG.warn(
          "Redis slot cache read failed ({}); falling back to DB", e.getClass().getSimpleName());
      return repository.search(criteria);
    }

    List<SlotRecord> fresh = repository.search(criteria);
    misses.increment();
    stats.misses().incrementAndGet();
    try {
      List<CachedSlot> payload = fresh.stream().map(CachedSlot::from).toList();
      redis.opsForValue().set(queryKey, JSON.writeValueAsString(payload), ttl);
    } catch (RuntimeException e) {
      LOG.warn("Redis slot cache write failed ({})", e.getClass().getSimpleName());
    }
    return fresh;
  }

  /**
   * Invalidates cached slot queries matching the affected doctor/specialty/schedule/days by bumping
   * their epoch keys. Failures are logged and ignored so write paths never fail.
   */
  public void invalidate(
      UUID practitionerId, String specialty, UUID scheduleId, Set<LocalDate> days) {
    if (!enabled || redis == null) {
      return;
    }
    Set<String> keys = new LinkedHashSet<>();
    Set<LocalDate> safeDays = days == null ? Set.of() : days;
    if (practitionerId != null) {
      keys.add("shp:slot:ep:p:" + practitionerId);
      for (LocalDate d : safeDays) {
        keys.add("shp:slot:ep:p:" + practitionerId + ":" + d);
      }
    }
    if (specialty != null && !specialty.isBlank()) {
      keys.add("shp:slot:ep:sp:" + specialty);
      for (LocalDate d : safeDays) {
        keys.add("shp:slot:ep:sp:" + specialty + ":" + d);
      }
    }
    if (scheduleId != null) {
      keys.add("shp:slot:ep:sc:" + scheduleId);
    } else {
      keys.add("shp:slot:ep:sc-any");
    }
    if (practitionerId == null || specialty == null || specialty.isBlank()) {
      keys.add("shp:slot:ep:all");
    }

    try {
      for (String key : keys) {
        redis.opsForValue().increment(key);
        redis.expire(key, EPOCH_TTL);
      }
      invalidationsOk.increment();
    } catch (RuntimeException e) {
      invalidationsError.increment();
      LOG.warn("Redis slot cache invalidation failed ({})", e.getClass().getSimpleName());
    }
  }

  private String currentEpochToken(List<String> epochKeys) {
    List<String> values = redis.opsForValue().multiGet(epochKeys);
    if (values == null || values.isEmpty()) {
      return epochKeys.stream().map(k -> "0").collect(Collectors.joining(":"));
    }
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < epochKeys.size(); i++) {
      if (i > 0) {
        sb.append(':');
      }
      String val = i < values.size() ? values.get(i) : null;
      sb.append(val != null ? val : "0");
    }
    return sb.toString();
  }

  private static List<String> epochKeysFor(SlotSearchCriteria c) {
    LocalDate singleDay = singleVnDay(c.startFrom(), c.startTo());
    if (c.practitionerId() != null) {
      String scope =
          singleDay != null
              ? "shp:slot:ep:p:" + c.practitionerId() + ":" + singleDay
              : "shp:slot:ep:p:" + c.practitionerId();
      return List.of(scope, "shp:slot:ep:all");
    }
    if (c.specialty() != null) {
      String scope =
          singleDay != null
              ? "shp:slot:ep:sp:" + c.specialty() + ":" + singleDay
              : "shp:slot:ep:sp:" + c.specialty();
      return List.of(scope, "shp:slot:ep:all");
    }
    return List.of("shp:slot:ep:sc:" + c.scheduleId(), "shp:slot:ep:sc-any", "shp:slot:ep:all");
  }

  private static LocalDate singleVnDay(Instant from, Instant to) {
    if (from == null || to == null || !to.isAfter(from)) {
      return null;
    }
    LocalDate startDay = LocalDate.ofInstant(from, SlotGenerator.VN_ZONE);
    LocalDate endDay = LocalDate.ofInstant(to.minusNanos(1), SlotGenerator.VN_ZONE);
    return startDay.equals(endDay) ? startDay : null;
  }

  private String queryDigest(
      SlotSearchCriteria c, boolean fromDefaultedNow, boolean toDefaultedNow) {
    String statuses = String.join(",", new TreeSet<>(c.statuses()));
    String normFrom = normalizeBound(c.startFrom(), fromDefaultedNow);
    String normTo = normalizeBound(c.startTo(), toDefaultedNow);
    String canonical =
        String.join(
            "|",
            statuses,
            c.specialty() != null ? c.specialty() : "",
            c.practitionerId() != null ? c.practitionerId().toString() : "",
            c.scheduleId() != null ? c.scheduleId().toString() : "",
            normFrom,
            normTo,
            c.afterStartAt() != null ? c.afterStartAt().toString() : "",
            c.afterId() != null ? c.afterId().toString() : "",
            Integer.toString(c.limit()));
    return sha256Hex(canonical);
  }

  private String normalizeBound(Instant bound, boolean bucketToTtl) {
    if (bound == null) {
      return "";
    }
    if (!bucketToTtl) {
      return bound.toString();
    }
    long bucketSeconds = Math.max(1L, ttl.toSeconds());
    long floored = (bound.getEpochSecond() / bucketSeconds) * bucketSeconds;
    return Instant.ofEpochSecond(floored).toString();
  }

  private static String sha256Hex(String input) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }

  private record CachedSlot(
      String id,
      int versionId,
      String lastUpdated,
      String scheduleId,
      String practitionerId,
      String specialty,
      String status,
      String startAt,
      String endAt,
      String content) {

    static CachedSlot from(SlotRecord r) {
      return new CachedSlot(
          r.id().toString(),
          r.versionId(),
          r.lastUpdated().toString(),
          r.scheduleId().toString(),
          r.practitionerId().toString(),
          r.specialty(),
          r.status(),
          r.startAt().toString(),
          r.endAt().toString(),
          r.content());
    }

    SlotRecord toRecord() {
      return new SlotRecord(
          UUID.fromString(id),
          versionId,
          Instant.parse(lastUpdated),
          UUID.fromString(scheduleId),
          UUID.fromString(practitionerId),
          specialty,
          status,
          Instant.parse(startAt),
          Instant.parse(endAt),
          content);
    }
  }
}
