package org.shp.audit.messaging;

import java.time.Clock;
import java.util.Set;
import java.util.function.Consumer;
import org.shp.audit.app.AuditBatchFlusher;
import org.shp.audit.app.AuditIntake;
import org.shp.audit.app.AuditSinkShipper;
import org.shp.audit.config.AuditBatchProperties;
import org.shp.audit.config.AuditBlobProperties;
import org.shp.audit.config.AuditSinkProperties;
import org.shp.audit.infra.AuditSink;
import org.shp.audit.infra.AuditSpool;
import org.shp.audit.infra.WormAuditLog;
import org.shp.data.mongo.MongoInbox;
import org.shp.messaging.EventConsumers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.messaging.Message;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Audit pipeline (05-storage-audit-blob §1.2, 13-events E11): the consumer validates each {@code
 * shp.audit.recorded.v1} event against its schema, deduplicates by eventId through the inbox and
 * spools the record in the same transaction; a timer writes the spooled records to the WORM log in
 * batches of at most 5,000 records or 60 seconds.
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(AuditBatchProperties.class)
class AuditConsumerConfig {

  private static final Logger log = LoggerFactory.getLogger(AuditConsumerConfig.class);

  @Bean
  AuditSpool auditSpool(MongoTemplate mongo, Clock clock) {
    return new AuditSpool(mongo, clock);
  }

  @Bean
  AuditIntake auditIntake(AuditSpool spool, AuditBlobProperties blob) {
    return new AuditIntake(spool, blob.instance());
  }

  @Bean
  Consumer<Message<byte[]>> auditEvents(MongoInbox inbox, AuditIntake intake) {
    return EventConsumers.idempotent(
        AuditIntake.CONSUMER, Set.of(AuditIntake.TYPE), inbox::handleOnce, intake::onRecorded);
  }

  @Bean
  ApplicationRunner auditCollections(AuditSpool spool, AuditBlobProperties blob) {
    return args -> {
      spool.ensureCollections();
      if (!blob.configured()) {
        log.warn(
            "Audit blob storage is not configured: records are kept in the spool but no batch is"
                + " written to the WORM log");
      }
    };
  }

  @Bean
  AuditFlushJob auditFlushJob(
      AuditSpool spool,
      ObjectProvider<WormAuditLog> worm,
      ObjectProvider<AuditSink> sink,
      AuditSinkProperties sinkProps,
      AuditBlobProperties blob,
      AuditBatchProperties batch,
      Clock clock) {
    return new AuditFlushJob(spool, worm, sink, sinkProps, blob, batch, clock);
  }

  /** Checks every {@code poll-interval} whether a batch is due. */
  static class AuditFlushJob {
    private final AuditSpool spool;
    private final ObjectProvider<WormAuditLog> worm;
    private final ObjectProvider<AuditSink> sink;
    private final AuditSinkProperties sinkProps;
    private final AuditBlobProperties blob;
    private final AuditBatchProperties batch;
    private final Clock clock;
    private AuditBatchFlusher flusher;
    private AuditSinkShipper shipper;

    AuditFlushJob(
        AuditSpool spool,
        ObjectProvider<WormAuditLog> worm,
        ObjectProvider<AuditSink> sink,
        AuditSinkProperties sinkProps,
        AuditBlobProperties blob,
        AuditBatchProperties batch,
        Clock clock) {
      this.spool = spool;
      this.worm = worm;
      this.sink = sink;
      this.sinkProps = sinkProps;
      this.blob = blob;
      this.batch = batch;
      this.clock = clock;
    }

    /** Ships new records to the query sink every poll interval (NFR-SEC5: queryable in 1 min). */
    @Scheduled(fixedDelayString = "${shp.audit-batch.poll-interval:1s}")
    void ship() {
      if (!sinkProps.enabled()) {
        return;
      }
      try {
        sinkShipper().shipDue();
      } catch (RuntimeException e) {
        // Nothing is marked shipped and the records stay in the spool: the next run retries.
        log.error("Audit sink shipping failed, will retry: {}", e.toString());
      }
    }

    private AuditSinkShipper sinkShipper() {
      if (shipper == null) {
        shipper =
            new AuditSinkShipper(spool, sink.getObject(), blob.instance(), batch.maxRecords());
      }
      return shipper;
    }

    @Scheduled(fixedDelayString = "${shp.audit-batch.poll-interval:1s}")
    void run() {
      if (!blob.configured()) {
        return;
      }
      try {
        if (flusher == null) {
          flusher =
              new AuditBatchFlusher(
                  spool,
                  worm.getObject(),
                  blob.instance(),
                  batch.maxRecords(),
                  batch.maxAge(),
                  clock,
                  sinkProps.enabled() ? sinkShipper() : null);
        }
        flusher
            .flushIfDue()
            .ifPresent(
                r -> log.info("Audit batch {} written ({} records)", r.blobName(), r.count()));
      } catch (RuntimeException e) {
        // Records stay in the spool and the flush plan is kept: the next run retries the same
        // batch.
        log.error("Audit batch write failed, will retry: {}", e.toString());
      }
    }
  }
}
