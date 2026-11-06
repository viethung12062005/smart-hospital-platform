package org.shp.messaging;

import org.shp.events.CloudEvent;

/**
 * Inbox chống xử lý trùng của service ({@code PostgresInbox::handleOnce} hoặc {@code
 * MongoInbox::handleOnce}): chạy {@code handler} đúng một lần cho mỗi (consumer, id event).
 */
@FunctionalInterface
public interface Inbox {
  boolean handleOnce(String consumer, CloudEvent event, Runnable handler);
}
