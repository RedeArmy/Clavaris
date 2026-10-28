package com.clavaris.app.infrastructure.adapter.out.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;

class AccountSessionCapTest {

  private static final Instant NOW = Instant.parse("2026-10-28T12:00:00Z");

  private final UUID accountId = UUID.randomUUID();
  private final SessionRegistry registry = mock(SessionRegistry.class);

  /** A session last used {@code minutesAgo} minutes ago; the older it is the sooner it goes. */
  private SessionInformation session(final String id, final int minutesAgo) {
    return mock(
        SessionInformation.class,
        invocation -> {
          switch (invocation.getMethod().getName()) {
            case "getSessionId":
              return id;
            case "getLastRequest":
              return Date.from(NOW.minusSeconds(minutesAgo * 60L));
            default:
              return null;
          }
        });
  }

  private void accountHas(final SessionInformation... sessions) {
    when(registry.getAllSessions(accountId.toString(), false)).thenReturn(List.of(sessions));
  }

  @Test
  void anAccountUnderTheCapLosesNothing() {
    SessionInformation a = session("a", 30);
    SessionInformation b = session("b", 20);
    accountHas(a, b);

    new AccountSessionCap(registry, 3).makeRoomFor(accountId, null);

    verify(a, never()).expireNow();
    verify(b, never()).expireNow();
  }

  // The session being established is not saved yet, so with a cap of 3 only 2 others may stay.
  @Test
  void anAccountAtTheCapLosesItsLeastRecentlyUsedSessionToMakeRoom() {
    SessionInformation oldest = session("oldest", 90);
    SessionInformation middle = session("middle", 30);
    SessionInformation newest = session("newest", 5);
    accountHas(newest, oldest, middle);

    new AccountSessionCap(registry, 3).makeRoomFor(accountId, null);

    verify(oldest).expireNow();
    verify(middle, never()).expireNow();
    verify(newest, never()).expireNow();
  }

  @Test
  void anAccountFarOverTheCapIsBroughtBackToIt() {
    SessionInformation s1 = session("s1", 50);
    SessionInformation s2 = session("s2", 40);
    SessionInformation s3 = session("s3", 30);
    SessionInformation s4 = session("s4", 20);
    SessionInformation s5 = session("s5", 10);
    accountHas(s3, s5, s1, s4, s2);

    new AccountSessionCap(registry, 3).makeRoomFor(accountId, null);

    verify(s1).expireNow();
    verify(s2).expireNow();
    verify(s3).expireNow();
    verify(s4, never()).expireNow();
    verify(s5, never()).expireNow();
  }

  // The same browser signing in again replaces its own session; that is not another device.
  @Test
  void theSessionBeingReplacedInTheSameBrowserIsNotCounted() {
    SessionInformation mine = session("mine", 80);
    SessionInformation other = session("other", 20);
    accountHas(mine, other);

    new AccountSessionCap(registry, 2).makeRoomFor(accountId, "mine");

    verify(other, never()).expireNow();
    verify(mine, never()).expireNow();
  }

  @Test
  void aCapOfZeroMeansNoCapAndAsksNothing() {
    new AccountSessionCap(registry, 0).makeRoomFor(accountId, null);

    verify(registry, never()).getAllSessions(accountId.toString(), false);
    assertThat(registry).isNotNull();
  }
}
