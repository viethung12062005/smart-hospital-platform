package org.shp.messaging;

import org.shp.events.CloudEvent;

/**
 * Inbox that prevents duplicate processing by the service ({@code PostgresInbox::handleOnce} or
 * {@code MongoInbox::handleOnce}): runs {@code handler} exactly once for each (consumer, event id).
 */
@FunctionalInterface
public interface Inbox {
  boolean handleOnce(String consumer, CloudEvent event, Runnable handler);
}
