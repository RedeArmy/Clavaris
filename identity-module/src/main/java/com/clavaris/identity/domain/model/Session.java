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
 * <p>PMD suppressions below: coding-standards.md §3a. TooManyMethods: an aggregate whose methods
 * are its two factories, three state changes and one accessor per field; the new {@code
 * authenticatedAt} field is what tipped it over, and splitting accessors off would only scatter it.
 */
@SuppressWarnings({
  "PMD.AvoidFieldNameMatchingMethodName",
  "PMD.ShortVariable",
  "PMD.ShortMethodName",
  "PMD.TooManyMethods"
})
public final class Session {

  private final UUID id;
  private final AccountId accountId;
  private final List<String> scopes;
  private final Instant createdAt;
  private final Instant authenticatedAt;
  private Instant lastSeenAt;
  private Instant revokedAt;

  private Session(
      final UUID id,
      final AccountId accountId,
      final List<String> scopes,
      final Instant createdAt,
      final Instant authenticatedAt,
      final Instant lastSeenAt,
      final Instant revokedAt) {
    this.id = Objects.requireNonNull(id, "id must not be null");
    this.accountId = Objects.requireNonNull(accountId, "accountId must not be null");
    this.scopes = List.copyOf(Objects.requireNonNull(scopes, "scopes must not be null"));
    this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
    this.authenticatedAt =
        Objects.requireNonNull(authenticatedAt, "authenticatedAt must not be null");
    this.lastSeenAt = Objects.requireNonNull(lastSeenAt, "lastSeenAt must not be null");
    this.revokedAt = revokedAt;
  }

  /**
   * Opens a brand-new session — the interactive grant that mints the first {@link RefreshToken}.
   */
  public static Session open(final AccountId accountId, final List<String> scopes) {
    return open(accountId, scopes, null);
  }

  /**
   * OIDC Core §2 / §12.2: {@code authenticatedAt} is when the End-User actually authenticated (the
   * login, or the re-authentication a {@code prompt=login} / {@code max_age} forced), which is
   * earlier than this Session's {@code createdAt} (opened later, when the code is exchanged). A
   * refreshed ID token must keep reporting it as {@code auth_time}. Absent, or claimed to be later
   * than now, it falls back to the moment the Session opens, never a future instant.
   */
  public static Session open(
      final AccountId accountId, final List<String> scopes, final Instant authenticatedAt) {
    final Instant now = Instant.now();
    final Instant authTime =
        authenticatedAt == null || authenticatedAt.isAfter(now) ? now : authenticatedAt;
    return new Session(UUID.randomUUID(), accountId, scopes, now, authTime, now, null);
  }

  public static Session reconstitute(
      final UUID id,
      final AccountId accountId,
      final List<String> scopes,
      final Instant createdAt,
      final Instant authenticatedAt,
      final Instant lastSeenAt,
      final Instant revokedAt) {
    return new Session(id, accountId, scopes, createdAt, authenticatedAt, lastSeenAt, revokedAt);
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

  public Instant authenticatedAt() {
    return authenticatedAt;
  }

  public Instant lastSeenAt() {
    return lastSeenAt;
  }

  public Optional<Instant> revokedAt() {
    return Optional.ofNullable(revokedAt);
  }
}
