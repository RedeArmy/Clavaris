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

import com.clavaris.identity.application.usecase.deferplatformsessionclosure.PendingPlatformSessionClosureRepository;
import com.clavaris.identity.domain.model.PlatformAccountId;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;

// TD-ARCH-063: suspending an operator or resetting its password must not fail because the session
// store is down.
class DeferringPlatformAccountSessionRevokerTest {

  private static final Instant NOW = Instant.parse("2026-11-18T10:00:00Z");

  private final PlatformAccountSessionRevokerBridge bridge =
      mock(PlatformAccountSessionRevokerBridge.class);
  private final PendingPlatformSessionClosureRepository pending =
      mock(PendingPlatformSessionClosureRepository.class);
  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
  private final PlatformAccountId account = new PlatformAccountId(UUID.randomUUID());

  private DeferringPlatformAccountSessionRevoker revoker(final long pauseMillis) {
    return new DeferringPlatformAccountSessionRevoker(
        bridge,
        pending,
        meters,
        Clock.fixed(NOW, ZoneOffset.UTC),
        new SessionStoreCircuit(pauseMillis));
  }

  @Test
  void closesTheSessionsAndRecordsNothingWhenTheStoreAnswers() {
    revoker(60_000).revokeAllSessionsFor(account);

    verify(bridge).revokeAllSessionsFor(account);
    verify(pending, never()).add(any(), any());
    assertThat(meters.find("clavaris.platform_session.closure.deferred").counter()).isNull();
  }

  @Test
  void recordsTheAccountInsteadOfFailingWhenTheStoreIsUnreachable() {
    doThrow(new RedisConnectionFailureException("down")).when(bridge).revokeAllSessionsFor(account);

    revoker(60_000).revokeAllSessionsFor(account);

    verify(pending).add(account, NOW);
    assertThat(meters.get("clavaris.platform_session.closure.deferred").counter().count())
        .isEqualTo(1.0);
  }

  @Test
  void aCommandTimeoutIsTheSameCase() {
    doThrow(new QueryTimeoutException("timeout")).when(bridge).revokeAllSessionsFor(account);

    revoker(60_000).revokeAllSessionsFor(account);

    verify(pending).add(account, NOW);
  }

  @Test
  void doesNotWaitForAnotherTimeoutPerAccountAfterTheFirstFailure() {
    PlatformAccountId second = new PlatformAccountId(UUID.randomUUID());
    doThrow(new RedisConnectionFailureException("down")).when(bridge).revokeAllSessionsFor(account);
    DeferringPlatformAccountSessionRevoker revoker = revoker(60_000);

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
    DeferringPlatformAccountSessionRevoker revoker = revoker(20);

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

    DeferringPlatformAccountSessionRevoker revoker = revoker(60_000);

    assertThatThrownBy(() -> revoker.revokeAllSessionsFor(account))
        .isInstanceOf(IllegalStateException.class);
    verify(pending, never()).add(any(), any());
  }

  // Redis being down is one fact: the tenant tier finding it out spares the operator tier its own
  // command timeout (ADR-0045, the shared SessionStoreCircuit).
  @Test
  void aFailureFoundByAnotherCallerAlsoPausesThisOne() {
    SessionStoreCircuit shared = new SessionStoreCircuit(60_000);
    shared.trip();
    DeferringPlatformAccountSessionRevoker revoker =
        new DeferringPlatformAccountSessionRevoker(
            bridge, pending, meters, Clock.fixed(NOW, ZoneOffset.UTC), shared);

    revoker.revokeAllSessionsFor(account);

    verify(bridge, never()).revokeAllSessionsFor(any());
    verify(pending).add(account, NOW);
  }
}
