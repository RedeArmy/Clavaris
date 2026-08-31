package com.clavaris.identity.application.usecase.listactivesessionsforaccount;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.clavaris.identity.domain.model.AccountId;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ListActiveSessionsForAccountServiceTest {

  private final AccountActiveSessionsRepository activeSessions =
      mock(AccountActiveSessionsRepository.class);
  private final ListActiveSessionsForAccountService service =
      new ListActiveSessionsForAccountService(activeSessions);

  @Test
  void returnsTheRepositorysOwnSessionsMostRecentlyActiveFirst() {
    AccountId accountId = AccountId.newId();
    ActiveAccountSession older =
        new ActiveAccountSession(
            "session-older", "UA-1", "1.2.3.4", Instant.now(), Instant.now().minusSeconds(600));
    ActiveAccountSession newer =
        new ActiveAccountSession("session-newer", "UA-2", "5.6.7.8", Instant.now(), Instant.now());
    when(activeSessions.findAllByAccountId(accountId)).thenReturn(List.of(older, newer));

    List<ActiveAccountSession> result =
        service.handle(new ListActiveSessionsForAccountQuery(accountId));

    assertThat(result).containsExactly(newer, older);
  }

  @Test
  void returnsAnEmptyListWhenTheAccountHasNoLiveSessions() {
    AccountId accountId = AccountId.newId();
    when(activeSessions.findAllByAccountId(accountId)).thenReturn(List.of());

    assertThat(service.handle(new ListActiveSessionsForAccountQuery(accountId))).isEmpty();
  }
}
