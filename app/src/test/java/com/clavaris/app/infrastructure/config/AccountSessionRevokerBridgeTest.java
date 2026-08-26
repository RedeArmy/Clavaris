package com.clavaris.app.infrastructure.config;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.clavaris.identity.domain.model.AccountId;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;

class AccountSessionRevokerBridgeTest {

  private final SessionRegistry sessionRegistry = mock(SessionRegistry.class);
  private final AccountSessionRevokerBridge bridge =
      new AccountSessionRevokerBridge(sessionRegistry);

  @Test
  void expiresEveryActiveSessionRegisteredForTheAccount() {
    AccountId accountId = AccountId.newId();
    SessionInformation first = mock(SessionInformation.class);
    SessionInformation second = mock(SessionInformation.class);
    when(sessionRegistry.getAllSessions(accountId.value().toString(), false))
        .thenReturn(List.of(first, second));

    bridge.revokeAllSessionsFor(accountId);

    verify(first).expireNow();
    verify(second).expireNow();
  }
}
