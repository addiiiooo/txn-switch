/* SPDX-License-Identifier: MIT */
package com.txnswitch.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

import com.txnswitch.application.port.AuthorizationRepository;
import com.txnswitch.domain.authorization.Authorization;
import com.txnswitch.domain.authorization.AuthorizationEvent;
import com.txnswitch.domain.authorization.AuthorizationStatus;
import com.txnswitch.testsupport.Fixtures;
import com.txnswitch.testsupport.IntegrationTest;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** The scheduled entry point, which is a different bean from the transactional one on purpose. */
class MaintenanceSchedulerIT extends IntegrationTest {

  @Autowired private MaintenanceScheduler scheduler;
  @Autowired private AuthorizationRepository authorizations;

  @Test
  void oneSweepExpiresLapsedHoldsAndPublishesTheUnresolvedCount() {
    Authorization lapsed =
        Fixtures.inStatus(AuthorizationStatus.AUTHORIZED, Instant.now().minusSeconds(120));
    inTransaction(
        () ->
            authorizations.insert(
                lapsed,
                AuthorizationEvent.created(
                    AuthorizationStatus.AUTHORIZED, Instant.now(), "created"),
                "test"));

    scheduler.sweep();

    assertThat(
            authorizations.find(lapsed.id().value(), Fixtures.MERCHANT_ID).orElseThrow().status())
        .as("the transaction really was applied, which it would not be on a self-invocation")
        .isEqualTo(AuthorizationStatus.EXPIRED);
    assertThat(rest.getForEntity("/actuator/prometheus", String.class).getBody())
        .contains("txnswitch_unresolved_attempts");
  }

  @Test
  void aSweepWithNothingToDoIsHarmless() {
    assertThatNoException().isThrownBy(scheduler::sweep);
  }
}
