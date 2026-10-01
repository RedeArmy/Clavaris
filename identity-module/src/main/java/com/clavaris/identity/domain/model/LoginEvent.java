package com.clavaris.identity.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * TD-FUT-034, Clerk "View Profile" activity heatmap parity — one durable row per successful tenant
 * sign-in, written by {@code RecordLoginEventService} from {@code AuthenticatedSessionCompletion}'s
 * own chokepoint plus the two direct-call sign-in paths that bypass it (email-link sign-in, social
 * login). An immutable fact, never updated after insert — unlike {@link KnownDevice}, there is no
 * {@code touch()}/{@code reconstitute} pair here, since nothing ever reads a single row back by id
 * or mutates one; {@code GetLoginActivityForAccountService} only ever reads day-bucketed counts.
 */
// PMD.ShortVariable: id names exactly what it is — same convention KnownDevice's own identical
// suppression already documents for this same constructor parameter.
@SuppressWarnings("PMD.ShortVariable")
public record LoginEvent(
    UUID id, AccountId accountId, OrganizationId organizationId, Instant occurredAt) {

  public LoginEvent {
    Objects.requireNonNull(id, "id must not be null");
    Objects.requireNonNull(accountId, "accountId must not be null");
    Objects.requireNonNull(organizationId, "organizationId must not be null");
    Objects.requireNonNull(occurredAt, "occurredAt must not be null");
  }

  public static LoginEvent occurNow(
      final AccountId accountId, final OrganizationId organizationId) {
    return new LoginEvent(UUID.randomUUID(), accountId, organizationId, Instant.now());
  }
}
