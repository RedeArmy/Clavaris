package com.clavaris.identity.application.usecase.revokeaccountsession;

import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.clavaris.identity.application.usecase.listactivesessionsforaccount.AccountActiveSessionsRepository;
import com.clavaris.identity.application.usecase.listactivesessionsforaccount.ActiveAccountSession;
import com.clavaris.identity.domain.model.AccountId;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RevokeAccountSessionServiceTest {

  private final AccountActiveSessionsRepository activeSessions =
      mock(AccountActiveSessionsRepository.class);
  private final RevokeAccountSessionService service =
      new RevokeAccountSessionService(activeSessions);

  @Test
  void revokesASessionTheAccountActuallyOwns() {
    AccountId accountId = AccountId.newId();
    ActiveAccountSession session =
        new ActiveAccountSession("real-session", "UA", "1.2.3.4", Instant.now(), Instant.now());
    when(activeSessions.findByAccountIdAndSessionId(accountId, "real-session"))
        .thenReturn(Optional.of(session));

    service.handle(new RevokeAccountSessionCommand(accountId, "real-session"));

    verify(activeSessions).revoke("real-session");
  }

  @Test
  void rejectsASessionThatDoesNotResolveForThisAccountWithoutRevokingAnything() {
    // Covers both "doesn't exist" and "belongs to a different Account" — this repository lookup
    // can't distinguish the two, by design (SessionNotFoundException's own Javadoc).
    AccountId accountId = AccountId.newId();
    when(activeSessions.findByAccountIdAndSessionId(accountId, "someone-elses-session"))
        .thenReturn(Optional.empty());
    RevokeAccountSessionCommand command =
        new RevokeAccountSessionCommand(accountId, "someone-elses-session");

    assertThatExceptionOfType(SessionNotFoundException.class)
        .isThrownBy(() -> service.handle(command));

    verify(activeSessions, never()).revoke(any());
  }
}
