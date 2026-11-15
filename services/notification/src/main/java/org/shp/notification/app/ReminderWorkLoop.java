package org.shp.notification.app;

import java.util.function.Consumer;
import org.bson.Document;
import org.shp.notification.infra.MongoReminderWorkQueue;
import org.springframework.stereotype.Service;

@Service
public class ReminderWorkLoop {

  private static final int MAX_BATCH_SIZE = 100;

  private final MongoReminderWorkQueue workQueue;

  public ReminderWorkLoop(MongoReminderWorkQueue workQueue) {
    this.workQueue = workQueue;
  }

  /**
   * Claims and hands reminders to the supplied processor. If processing stops unexpectedly, the
   * lock expires and recovery makes the reminder available again.
   */
  public int runBatch(Consumer<Document> processor) {
    int processed = 0;

    while (processed < MAX_BATCH_SIZE) {
      var reminder = workQueue.claimNext();

      if (reminder.isEmpty()) {
        break;
      }

      processor.accept(reminder.get());
      processed++;
    }

    return processed;
  }

  public long recoverStuckReminders() {
    return workQueue.recoverExpiredClaims();
  }
}
