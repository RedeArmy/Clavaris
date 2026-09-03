package com.clavaris.identity.application.usecase.listactivesessionsforplatformaccount;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.clavaris.identity.domain.model.PlatformAccountId;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** TD-FUT-026: platform-tier mirror of {@code ListActiveSessionsForAccountServiceTest}. */
class ListActiveSessionsForPlatformAccountServiceTest {

  @Test
  void returnsSessionsMostRecentlyActiveFirst() {
    PlatformAccountActiveSessionsRepository activeSessions =
        mock(PlatformAccountActiveSessionsRepository.class);
    PlatformAccountId platformAccountId = PlatformAccountId.newId();
    ActivePlatformAccountSession older =
        new ActivePlatformAccountSession(
            "older", "UA", "1.2.3.4", Instant.now(), Instant.parse("2026-01-01T00:00:00Z"));
    ActivePlatformAccountSession newer =
        new ActivePlatformAccountSession(
            "newer", "UA", "1.2.3.4", Instant.now(), Instant.parse("2026-06-01T00:00:00Z"));
    when(activeSessions.findAllByPlatformAccountId(platformAccountId))
        .thenReturn(List.of(older, newer));
    ListActiveSessionsForPlatformAccountService service =
        new ListActiveSessionsForPlatformAccountService(activeSessions);

    List<ActivePlatformAccountSession> result =
        service.handle(new ListActiveSessionsForPlatformAccountQuery(platformAccountId));

    assertThat(result).containsExactly(newer, older);
  }
}
