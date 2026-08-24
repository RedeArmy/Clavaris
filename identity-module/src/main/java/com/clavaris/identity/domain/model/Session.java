package com.clavaris.identity.domain.model;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * BR-ID-03: a continuous login, opened at the first successful token issuance for an interactive
 * grant and closed by explicit revocation. One {@code Session} owns a chain of rotated {@link
 * RefreshToken}s (domain-model.md §2) — kept as a separate aggregate from {@code RefreshToken}
 * rather than merged into it, per domain-model.md §8's own resolution, because reuse-detection
 * needs to reason about the rotation chain, not just whichever token is currently active.
 *
 * <p>Deliberately distinct from the servlet-container {@code HttpSession} the hosted login/consent
 * UI uses (Redis-backed since TD-ARCH-002 closed) — this is the OAuth2/OIDC-level concept of "one
 * continuous authorization," a business record, not web-tier state. The two are related (a browser
 * login opens both) but neither implies the other's lifecycle.
 *
 * <p>{@code scopes} is fixed at {@link #open}: RFC 6749 §6 forbids a refresh grant from ever
 * requesting more than what was originally authorized, so every {@link RefreshToken} issued under
 * this session's chain is validated against this same, unchanging set.
 *
 * <p><b>Known, deliberate simplification:</b> data-model.md's original sketch of {@code sessions}
 * included a {@code user_agent} column; this implementation doesn't populate one — rotation and
 * reuse detection (BR-ID-03) don't need it, and nothing yet consumes it (no "list your active
 * sessions/devices" feature exists). Add it, and the real HTTP request plumbing to populate it,
 * only once a use case actually needs it — not speculatively ahead of one.
 *
 * <p>PMD's AvoidFieldNameMatchingMethodName/ShortVariable/ShortMethodName rules flag this class for
 * the same reason {@code Account} suppresses them — the deliberate record-style accessor convention
 * used throughout this codebase's value objects.
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
