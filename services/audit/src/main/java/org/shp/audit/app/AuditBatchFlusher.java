package org.shp.audit.app;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.bson.Document;
import org.shp.audit.domain.AuditBatch;
import org.shp.audit.infra.AuditSpool;
import org.shp.audit.infra.WormAuditLog;

/**
 * Moves spooled records into the WORM log in batches of at most {@code maxRecords}, when that many
 * are waiting or the oldest has waited {@code maxAge} (05-storage-audit-blob §1.2).
 *
 * <p>Order of a flush: remember the chosen record ids (flush plan), append the batch, delete the
 * records and the plan in one transaction. A failure at any point leaves the plan, and the next
 * call finishes the same batch: if the chain head already equals the hash of that batch the blob
 * was written completely and only the clean-up is left, otherwise the same records are appended
 * again. The head only counts a blob whose content verifies, so a blob left incomplete by a crash
 * is skipped and its records are rewritten instead of being deleted. So nothing is lost and nothing
 * is written twice.
 */
public class AuditBatchFlusher {

  private final AuditSpool spool;
  private final WormAuditLog worm;
  private final String instance;
  private final int maxRecords;
  private final Duration maxAge;
  private final Clock clock;
  private final AuditSinkShipper shipper;

  public AuditBatchFlusher(
      AuditSpool spool,
      WormAuditLog worm,
      String instance,
      int maxRecords,
      Duration maxAge,
      Clock clock) {
    this(spool, worm, instance, maxRecords, maxAge, clock, null);
  }

  /** With a shipper, no record leaves the spool before the query sink has accepted it. */
  public AuditBatchFlusher(
      AuditSpool spool,
      WormAuditLog worm,
      String instance,
      int maxRecords,
      Duration maxAge,
      Clock clock,
      AuditSinkShipper shipper) {
    this.spool = spool;
    this.worm = worm;
    this.instance = instance;
    this.maxRecords = maxRecords;
    this.maxAge = maxAge;
    this.clock = clock;
    this.shipper = shipper;
  }

  /**
   * Writes at most one batch if it is due.
   *
   * @return the receipt of the batch written, empty if nothing was due or only a clean-up was done
   */
  public synchronized Optional<WormAuditLog.Receipt> flushIfDue() {
    Optional<AuditSpool.Plan> pending = spool.plan(instance);
    if (pending.isPresent()) {
      return finish(pending.get());
    }
    long waiting = spool.count(instance);
    if (waiting == 0) {
      return Optional.empty();
    }
    Instant oldest = spool.oldest(instance).orElseThrow();
    boolean due = waiting >= maxRecords || !oldest.plus(maxAge).isAfter(clock.instant());
    if (!due) {
      return Optional.empty();
    }
    List<String> ids =
        spool.next(instance, maxRecords).stream().map(d -> d.getString("_id")).toList();
    AuditSpool.Plan plan = new AuditSpool.Plan(ids, worm.headHash());
    spool.savePlan(instance, plan);
    return finish(plan);
  }

  private Optional<WormAuditLog.Receipt> finish(AuditSpool.Plan plan) {
    List<Document> docs = spool.byIds(plan.ids());
    if (docs.size() != plan.ids().size()) {
      throw new IllegalStateException(
          "Flush plan lists "
              + plan.ids().size()
              + " records but "
              + docs.size()
              + " are in the spool");
    }
    List<String> lines = docs.stream().map(d -> d.getString("line")).toList();
    // An earlier run may have written the blob and stopped before the clean-up: then the chain head
    // is exactly the hash of this batch on top of the head recorded in the plan.
    if (worm.headHash().equals(AuditBatch.hash(plan.headBefore(), lines))) {
      shipBeforeRemoval(plan);
      spool.complete(instance, plan.ids());
      return Optional.empty();
    }
    WormAuditLog.Receipt receipt = worm.append(lines);
    shipBeforeRemoval(plan);
    spool.complete(instance, plan.ids());
    return Optional.of(receipt);
  }

  /** A sink error throws here: the plan stays and the next call ships again, then cleans up. */
  private void shipBeforeRemoval(AuditSpool.Plan plan) {
    if (shipper != null) {
      shipper.shipPending(plan.ids());
    }
  }
}
