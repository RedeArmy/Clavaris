package com.clavaris.app.infrastructure.adapter.out.security;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.stereotype.Component;

/**
 * TD-PERF-051: bounds the live browser sessions of one Account. The hosted login registered every
 * sign-in as a new session and none ever left until it expired, so an Account that signs in often
 * (or a script that does) could hold thousands, and every operation over "all of this Account's
 * sessions" grew with it: measured on a real Redis, listing 200 of them cost 201 commands and
 * revoking them on a password change 1,600 (8 each), about 3.7 s of one request. Spring Session
 * offers no cheaper bulk operation (its {@code deleteById} is worse, 28 commands each), so the
 * count is what has to be bounded.
 *
 * <p>When a sign-in would take the Account past {@code clavaris.session.max-per-account} (default
 * 10; {@code 0} means no cap) the least recently used sessions are ended first, the way most
 * services behave when a person signs in on one device too many. The session being established is
 * not saved yet, so the room made is for it. Only the tenant Accounts' browser sessions are
 * limited: OAuth tokens, refresh tokens and the platform operators' sessions are separate and
 * untouched.
 */
@Component
class AccountSessionCap implements AccountSessionLimiter {

  private static final Logger LOG = LoggerFactory.getLogger(AccountSessionCap.class);

  private final SessionRegistry sessionRegistry;
  private final int maxPerAccount;

  /* package */ AccountSessionCap(
      final SessionRegistry sessionRegistry,
      @Value("${clavaris.session.max-per-account:10}") final int maxPerAccount) {
    this.sessionRegistry = sessionRegistry;
    this.maxPerAccount = maxPerAccount;
  }

  @Override
  public void makeRoomFor(final UUID accountId, final String replacedSessionId) {
    if (maxPerAccount <= 0) {
      return;
    }
    final List<SessionInformation> others =
        sessionRegistry.getAllSessions(accountId.toString(), false).stream()
            .filter(session -> !Objects.equals(session.getSessionId(), replacedSessionId))
            .sorted(Comparator.comparing(SessionInformation::getLastRequest))
            .toList();
    final int excess = others.size() - (maxPerAccount - 1);
    if (excess <= 0) {
      return;
    }
    others.stream().limit(excess).forEach(SessionInformation::expireNow);
    LOG.info(
        "event=account_sessions_capped accountId={} ended={} max={}",
        accountId,
        excess,
        maxPerAccount);
  }
}
