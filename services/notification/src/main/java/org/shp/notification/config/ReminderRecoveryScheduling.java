package org.shp.notification.config;

import org.shp.notification.app.ReminderWorkLoop;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Configuration
@EnableScheduling
class ReminderRecoveryScheduling {}

@Component
class ReminderRecoveryJob {

  private final ReminderWorkLoop workLoop;

  ReminderRecoveryJob(ReminderWorkLoop workLoop) {
    this.workLoop = workLoop;
  }

  @Scheduled(fixedDelayString = "${shp.notification.reminders.recovery-interval-ms:60000}")
  public void recoverStuckReminders() {
    workLoop.recoverStuckReminders();
  }
}
