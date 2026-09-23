/* SPDX-License-Identifier: MIT */
package com.txnswitch.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drives {@link MaintenanceService} on a timer.
 *
 * <p>Separate from the service because {@code @Transactional} is applied by a proxy: a scheduled
 * method calling a transactional method on the same bean would silently run without a transaction.
 */
@Component
class MaintenanceScheduler {

  private static final Logger log = LoggerFactory.getLogger(MaintenanceScheduler.class);

  private final MaintenanceService maintenance;

  MaintenanceScheduler(MaintenanceService maintenance) {
    this.maintenance = maintenance;
  }

  @Scheduled(
      initialDelayString = "${txnswitch.maintenance.initial-delay:PT30S}",
      fixedDelayString = "${txnswitch.maintenance.interval:PT60S}")
  void sweep() {
    try {
      int expired = maintenance.expireLapsedHolds();
      int purged = maintenance.purgeExpiredIdempotencyRecords();
      long unresolved = maintenance.reportUnresolvedAttempts();
      if (expired > 0 || purged > 0 || unresolved > 0) {
        log.info(
            "Maintenance: expired {} holds, purged {} idempotency records, {} unresolved attempts",
            expired,
            purged,
            unresolved);
      }
    } catch (RuntimeException e) {
      // A failed sweep must not kill the schedule: the next run picks up the same rows.
      log.error("Maintenance sweep failed", e);
    }
  }
}
