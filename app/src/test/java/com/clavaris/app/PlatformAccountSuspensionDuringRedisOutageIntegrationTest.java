package com.clavaris.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.clavaris.app.infrastructure.adapter.out.bridge.PendingPlatformSessionClosureJob;
import com.clavaris.app.support.PlatformApiTestSupport;
import com.clavaris.app.support.RedisBackedIntegrationTest;
import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.identity.application.usecase.registerplatformaccount.PlatformAccountRepository;
import com.clavaris.identity.application.usecase.suspendplatformaccount.SuspendPlatformAccountCommand;
import com.clavaris.identity.application.usecase.suspendplatformaccount.SuspendPlatformAccountUseCase;
import com.clavaris.identity.domain.model.PlatformAccountId;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * TD-ARCH-063 / ADR-0045: suspending an operator account while Redis is unreachable used to fail
 * with a 500, because closing its dashboard sessions reads the session index in Redis inside the
 * same transaction. Now the suspension is applied, the account is recorded as waiting for its
 * sessions to be closed, and the job closes them once Redis answers. Against a real Redis that is
 * paused, as ADR-0045's tests for the tenant tier do.
 */
@SpringBootTest
@Testcontainers
// BR-ID-33: a Redis outage never stops an account from being suspended, banned or deleted.
class PlatformAccountSuspensionDuringRedisOutageIntegrationTest extends RedisBackedIntegrationTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

  @Autowired private SuspendPlatformAccountUseCase suspend;
  @Autowired private PlatformAccountRepository platformAccounts;
  @Autowired private PendingPlatformSessionClosureJob job;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void suspendingAnOperatorWhileRedisIsDownAppliesItAndClosesTheSessionsAfterwards() {
    UUID id = PlatformApiTestSupport.registerAPlatformAccount(platformAccounts);

    pauseRedis();
    try {
      suspend.handle(
          new SuspendPlatformAccountCommand(
              new PlatformAccountId(id), AuditActor.platformClient("test-platform-client")));
      assertThat(
              jdbcTemplate.queryForObject(
                  "select status from platform_accounts where id = ?", String.class, id))
          .as("the suspension is applied")
          .isEqualTo("SUSPENDED");
      assertThat(waitingFor(id))
          .as("and the account waits for its sessions to be closed")
          .isEqualTo(1);
    } finally {
      unpauseRedis();
    }

    await()
        .atMost(Duration.ofSeconds(30))
        .pollInterval(Duration.ofMillis(500))
        .untilAsserted(
            () -> {
              job.closePendingSessions();
              assertThat(waitingFor(id))
                  .as("once Redis answers the job closes the sessions and forgets the account")
                  .isZero();
            });
  }

  private int waitingFor(final UUID id) {
    return jdbcTemplate.queryForObject(
        "select count(*) from pending_platform_session_closures where platform_account_id = ?",
        Integer.class,
        id);
  }
}
