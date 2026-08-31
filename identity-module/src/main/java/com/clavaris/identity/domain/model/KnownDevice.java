package com.clavaris.identity.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A device (identified by its raw {@code User-Agent} string, not hashed — it isn't a secret, see
 * this class's own use case for the full reasoning) this {@link Account} has successfully logged in
 * from before. Outlives any single {@code HttpSession} on purpose — that store expires every 30
 * minutes and gets a brand-new id on every login even from the same physical browser, so it can't
 * answer "have we seen this device before" the way this aggregate does.
 *
 * <p>Deliberately not what {@code Session.java}'s own long-standing Javadoc comment refers to (the
 * domain {@code Session}/BR-ID-03 aggregate still has no {@code user_agent} column, and still
 * doesn't need one) — this is a separate, purpose-built aggregate for the "new device login"
 * notification, keyed by {@code (accountId, userAgent)}, not tied to any one refresh-token chain.
 *
 * <p>PMD's AvoidFieldNameMatchingMethodName/ShortVariable/ShortMethodName rules flag this class for
 * the same reason {@code Session}/{@code Account} suppress them — the deliberate record-style
 * accessor convention used throughout this codebase's value objects.
 */
@SuppressWarnings({
  "PMD.AvoidFieldNameMatchingMethodName",
  "PMD.ShortVariable",
  "PMD.ShortMethodName"
})
public final class KnownDevice {

  private final UUID id;
  private final AccountId accountId;
  private final String userAgent;
  private final Instant firstSeenAt;
  private Instant lastSeenAt;

  private KnownDevice(
      final UUID id,
      final AccountId accountId,
      final String userAgent,
      final Instant firstSeenAt,
      final Instant lastSeenAt) {
    this.id = Objects.requireNonNull(id, "id must not be null");
    this.accountId = Objects.requireNonNull(accountId, "accountId must not be null");
    this.userAgent = Objects.requireNonNull(userAgent, "userAgent must not be null");
    this.firstSeenAt = Objects.requireNonNull(firstSeenAt, "firstSeenAt must not be null");
    this.lastSeenAt = Objects.requireNonNull(lastSeenAt, "lastSeenAt must not be null");
  }

  /** First-ever login from this (accountId, userAgent) pair — the moment worth notifying about. */
  public static KnownDevice recognize(final AccountId accountId, final String userAgent) {
    final Instant now = Instant.now();
    return new KnownDevice(UUID.randomUUID(), accountId, userAgent, now, now);
  }

  public static KnownDevice reconstitute(
      final UUID id,
      final AccountId accountId,
      final String userAgent,
      final Instant firstSeenAt,
      final Instant lastSeenAt) {
    return new KnownDevice(id, accountId, userAgent, firstSeenAt, lastSeenAt);
  }

  /** Called on every subsequent login from an already-known device — no notification, just this. */
  public void touch() {
    this.lastSeenAt = Instant.now();
  }

  public UUID id() {
    return id;
  }

  public AccountId accountId() {
    return accountId;
  }

  public String userAgent() {
    return userAgent;
  }

  public Instant firstSeenAt() {
    return firstSeenAt;
  }

  public Instant lastSeenAt() {
    return lastSeenAt;
  }
}
