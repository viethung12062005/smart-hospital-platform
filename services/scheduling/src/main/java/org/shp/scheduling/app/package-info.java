/**
 * Use cases: the only place that opens a transaction (@Transactional); writes data and
 * outbox.add(...) in the same transaction.
 */
package org.shp.scheduling.app;
