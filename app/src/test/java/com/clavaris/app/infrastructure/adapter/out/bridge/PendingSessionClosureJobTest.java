package com.clavaris.app.infrastructure.adapter.out.bridge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.clavaris.identity.application.usecase.deferaccountsessionclosure.PendingSessionClosureRepository;
import com.clavaris.identity.domain.model.AccountId;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;

// TD-ARCH-057: the sessions of a suspended account are closed once Redis is back.
class PendingSessionClosureJobTest {

  private final PendingSessionClosureRepository pending =
      mock(PendingSessionClosureRepository.class);
  private final AccountSessionRevokerBridge bridge = mock(AccountSessionRevokerBridge.class);
  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
  private final PendingSessionClosureJob job =
      new PendingSessionClosureJob(pending, bridge, meters, 100);
  private final AccountId first = new AccountId(UUID.randomUUID());
  private final AccountId second = new AccountId(UUID.randomUUID());

  @Test
  void closesEachPendingAccountAndForgetsIt() {
    when(pending.oldest(100)).thenReturn(List.of(first, second));

    job.closePendingSessions();

    verify(bridge).revokeAllSessionsFor(first);
    verify(bridge).revokeAllSessionsFor(second);
    verify(pending).remove(first);
    verify(pending).remove(second);
    assertThat(meters.get("clavaris.session.closure.completed").counter().count()).isEqualTo(2.0);
  }

  @Test
  void keepsTheAccountsAndStopsWhileTheStoreIsStillDown() {
    when(pending.oldest(100)).thenReturn(List.of(first, second));
    doThrow(new RedisConnectionFailureException("down")).when(bridge).revokeAllSessionsFor(first);

    job.closePendingSessions();

    verify(bridge, never()).revokeAllSessionsFor(second);
    verify(pending, never()).remove(first);
    verify(pending, never()).remove(second);
    assertThat(meters.find("clavaris.session.closure.completed").counter()).isNull();
  }

  @Test
  void keepsTheAccountThatFailedButClosesThoseBeforeIt() {
    when(pending.oldest(100)).thenReturn(List.of(first, second));
    doThrow(new RedisConnectionFailureException("down")).when(bridge).revokeAllSessionsFor(second);

    job.closePendingSessions();

    verify(pending).remove(first);
    verify(pending, never()).remove(second);
  }

  @Test
  void doesNothingWhenNothingIsPending() {
    when(pending.oldest(100)).thenReturn(List.of());

    job.closePendingSessions();

    verify(bridge, never()).revokeAllSessionsFor(first);
  }
}
