package com.clavaris.app.infrastructure.adapter.out.bridge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.clavaris.identity.application.usecase.deferaccountsessionclosure.PendingSessionClosureRepository;
import com.clavaris.identity.domain.model.AccountId;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;

// TD-ARCH-057: a suspension or a deletion must not fail because the session store is down.
class DeferringAccountSessionRevokerTest {

  private static final Instant NOW = Instant.parse("2026-11-18T10:00:00Z");

  private final AccountSessionRevokerBridge bridge = mock(AccountSessionRevokerBridge.class);
  private final PendingSessionClosureRepository pending =
      mock(PendingSessionClosureRepository.class);
  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
  private final AccountId account = new AccountId(UUID.randomUUID());

  private DeferringAccountSessionRevoker revoker(final long pauseMillis) {
    return new DeferringAccountSessionRevoker(
        bridge, pending, meters, Clock.fixed(NOW, ZoneOffset.UTC), pauseMillis);
  }

  @Test
  void closesTheSessionsAndRecordsNothingWhenTheStoreAnswers() {
    revoker(60_000).revokeAllSessionsFor(account);

    verify(bridge).revokeAllSessionsFor(account);
    verify(pending, never()).add(any(), any());
    assertThat(meters.find("clavaris.session.closure.deferred").counter()).isNull();
  }

  @Test
  void recordsTheAccountInsteadOfFailingWhenTheStoreIsUnreachable() {
    doThrow(new RedisConnectionFailureException("down")).when(bridge).revokeAllSessionsFor(account);

    revoker(60_000).revokeAllSessionsFor(account);

    verify(pending).add(account, NOW);
    assertThat(meters.get("clavaris.session.closure.deferred").counter().count()).isEqualTo(1.0);
  }

  @Test
  void aCommandTimeoutIsTheSameCase() {
    doThrow(new QueryTimeoutException("timeout")).when(bridge).revokeAllSessionsFor(account);

    revoker(60_000).revokeAllSessionsFor(account);

    verify(pending).add(account, NOW);
  }

  @Test
  void doesNotWaitForAnotherTimeoutPerAccountAfterTheFirstFailure() {
    AccountId second = new AccountId(UUID.randomUUID());
    doThrow(new RedisConnectionFailureException("down")).when(bridge).revokeAllSessionsFor(account);
    DeferringAccountSessionRevoker revoker = revoker(60_000);

    revoker.revokeAllSessionsFor(account);
    revoker.revokeAllSessionsFor(second);

    verify(bridge, times(1)).revokeAllSessionsFor(any());
    verify(pending).add(second, NOW);
  }

  @Test
  void triesTheStoreAgainOnceThePauseHasPassed() {
    doThrow(new RedisConnectionFailureException("down"))
        .doNothing()
        .when(bridge)
        .revokeAllSessionsFor(any());
    DeferringAccountSessionRevoker revoker = revoker(20);

    revoker.revokeAllSessionsFor(account);
    verify(pending, times(1)).add(any(), any());

    // Calls made while the pause lasts are deferred; one made after it reaches the store again.
    await()
        .atMost(Duration.ofSeconds(2))
        .untilAsserted(
            () -> {
              revoker.revokeAllSessionsFor(account);
              verify(bridge, atLeast(2)).revokeAllSessionsFor(account);
            });
  }

  @Test
  void aFailureThatIsNotTheStoreIsNotSwallowed() {
    doThrow(new IllegalStateException("a defect")).when(bridge).revokeAllSessionsFor(account);

    DeferringAccountSessionRevoker revoker = revoker(60_000);

    assertThatThrownBy(() -> revoker.revokeAllSessionsFor(account))
        .isInstanceOf(IllegalStateException.class);
    verify(pending, never()).add(any(), any());
  }
}
