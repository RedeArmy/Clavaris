package com.clavaris.identity.domain.model;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * BR-ID-03: a continuous login, opened at first token issuance, closed by explicit revocation. Owns
 * a chain of rotated {@link RefreshToken}s, kept as its own aggregate (domain-model.md §8) since
 * reuse-detection needs the whole chain, not just the active token — distinct from the Redis-backed
 * {@code HttpSession} the hosted UI uses; the two are related but neither implies the other's
 * lifecycle.
 *
 * <p>{@code scopes} is fixed at {@link #open} (RFC 6749 §6). {@code user_agent} stays deliberately
 * unpopulated — rotation/reuse-detection never needed it; the self-service sessions page ({@code
 * AccountSessionsController}) is built on {@code HttpSession} state instead.
 *
 * <p>PMD suppressions below: coding-standards.md §3a.
 */
@SuppressWarnings({
  "PMD.AvoidFieldNameMatchingMethodName",
  "PMD.ShortVariable",
  "PMD.ShortMethodName"
})
public final class Session {

  private final UUID id;
  private final AccountId accountId;
  private final List<String> scopes;
  private final Instant createdAt;
  private Instant lastSeenAt;
  private Instant revokedAt;

  private Session(
      final UUID id,
      final AccountId accountId,
      final List<String> scopes,
      final Instant createdAt,
      final Instant lastSeenAt,
      final Instant revokedAt) {
    this.id = Objects.requireNonNull(id, "id must not be null");
    this.accountId = Objects.requireNonNull(accountId, "accountId must not be null");
    this.scopes = List.copyOf(Objects.requireNonNull(scopes, "scopes must not be null"));
    this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
    this.lastSeenAt = Objects.requireNonNull(lastSeenAt, "lastSeenAt must not be null");
    this.revokedAt = revokedAt;
  }

  /**
   * Opens a brand-new session — the interactive grant that mints the first {@link RefreshToken}.
   */
  public static Session open(final AccountId accountId, final List<String> scopes) {
    final Instant now = Instant.now();
    return new Session(UUID.randomUUID(), accountId, scopes, now, now, null);
  }

  public static Session reconstitute(
      final UUID id,
      final AccountId accountId,
      final List<String> scopes,
      final Instant createdAt,
      final Instant lastSeenAt,
      final Instant revokedAt) {
    return new Session(id, accountId, scopes, createdAt, lastSeenAt, revokedAt);
  }

  /**
   * Called on every successful rotation — an activity signal, not a security boundary by itself.
   */
  public void touch() {
    this.lastSeenAt = Instant.now();
  }

  /**
   * BR-ID-03: called as part of the reuse-detection cascade (every session for the account, not
   * just the one the reused token belonged to) — idempotent, since a session may already be revoked
   * by an unrelated prior action.
   */
  public void revoke() {
    this.revokedAt = Instant.now();
  }

  public boolean isActive() {
    return revokedAt == null;
  }

  public UUID id() {
    return id;
  }

  public AccountId accountId() {
    return accountId;
  }

  public List<String> scopes() {
    return scopes;
  }

  public Instant createdAt() {
    return createdAt;
  }

  public Instant lastSeenAt() {
    return lastSeenAt;
  }

  public Optional<Instant> revokedAt() {
    return Optional.ofNullable(revokedAt);
  }
}
