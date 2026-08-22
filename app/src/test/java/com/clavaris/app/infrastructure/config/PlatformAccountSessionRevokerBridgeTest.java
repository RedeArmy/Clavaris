package com.clavaris.app.infrastructure.config;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.clavaris.identity.domain.model.PlatformAccountId;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;

class PlatformAccountSessionRevokerBridgeTest {

  private final SessionRegistry sessionRegistry = mock(SessionRegistry.class);
  private final PlatformAccountSessionRevokerBridge bridge =
      new PlatformAccountSessionRevokerBridge(sessionRegistry);

  @Test
  void expiresEveryActiveSessionRegisteredForThePlatformAccount() {
    PlatformAccountId platformAccountId = PlatformAccountId.newId();
    SessionInformation first = mock(SessionInformation.class);
    SessionInformation second = mock(SessionInformation.class);
    when(sessionRegistry.getAllSessions(platformAccountId.value().toString(), false))
        .thenReturn(List.of(first, second));

    bridge.revokeAllSessionsFor(platformAccountId);

    verify(first).expireNow();
    verify(second).expireNow();
  }
}
