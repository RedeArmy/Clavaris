package com.clavaris.identity.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * BR-ID-03: single-use, rotate-on-use. The bearer value itself is never stored here or anywhere
 * else in this system — only {@link #tokenHash}, the same hash-not-plaintext principle already
 * applied to {@code PasswordCredential} and every other bearer secret this project designed its own
 * schema for (data-model.md §2). This is a deliberate contrast with {@code oauth2_authorization}
 * (TD-SEC-019): that table stores every token's raw value because Spring Authorization Server's own
 * {@code JdbcOAuth2AuthorizationService} requires it for its {@code findByToken} lookup, with no
 * supported way to hash on write — refresh tokens specifically don't inherit that gap, because
 * BR-ID-03's rotation/reuse-detection logic never goes through {@code OAuth2AuthorizationService}
 * at all; it's validated entirely against this table instead.
 *
 * <p>{@link #rotatedFromId} forms the chain domain-model.md §2 calls out by name: "presenting a
 * token whose chain shows it was already superseded triggers revocation of the entire session's
 * token family." In this implementation that check collapses to one field, not a chain walk: the
 * only two things that ever set {@link #revokedAt} on a refresh token are (a) rotating it away —
 * the exact moment its successor is issued — or (b) the reuse-detection cascade itself revoking
 * every token for the account. Both cases mean "this value must never work again," so a presented
 * token whose own row already has {@code revokedAt} set is reuse, full stop — {@code rotatedFromId}
 * is kept as the audit trail for why and when a row was superseded (walkable for investigation,
 * same spirit as {@code SigningKey.retiredAt}), not as a second source of truth the reuse check
 * itself needs to consult.
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
public final class RefreshToken {

  private final UUID id;
  private final UUID sessionId;
  private final AccountId accountId;
  private final String tokenHash;
  private final UUID rotatedFromId;
  private final Instant issuedAt;
  private final Instant expiresAt;
  private Instant revokedAt;

  // One parameter per persisted column — same rationale as OAuthClient's own S107 suppression: a
  // rehydration factory for an 8-column aggregate takes 8 parameters, not a sign this constructor
  // does too much. A synthetic parameter-object purely to dodge the threshold would add
  // indirection without removing any real complexity.
  @SuppressWarnings("java:S107")
  private RefreshToken(
      final UUID id,
      final UUID sessionId,
      final AccountId accountId,
      final String tokenHash,
      final UUID rotatedFromId,
      final Instant issuedAt,
      final Instant expiresAt,
      final Instant revokedAt) {
    this.id = Objects.requireNonNull(id, "id must not be null");
    this.sessionId = Objects.requireNonNull(sessionId, "sessionId must not be null");
    this.accountId = Objects.requireNonNull(accountId, "accountId must not be null");
    this.tokenHash = Objects.requireNonNull(tokenHash, "tokenHash must not be null");
    this.rotatedFromId = rotatedFromId;
    this.issuedAt = Objects.requireNonNull(issuedAt, "issuedAt must not be null");
    this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt must not be null");
    this.revokedAt = revokedAt;
  }

  /** The first token in a brand-new {@link Session}'s chain — {@link #rotatedFromId} is empty. */
  public static RefreshToken issue(
      final UUID sessionId,
      final AccountId accountId,
      final String tokenHash,
      final Instant expiresAt) {
    return new RefreshToken(
        UUID.randomUUID(), sessionId, accountId, tokenHash, null, Instant.now(), expiresAt, null);
  }

  /**
   * Issues the next token in {@code supersededToken}'s chain — does NOT mark {@code
   * supersededToken} itself revoked; the caller (the use case orchestrating the rotation) is
   * responsible for calling {@link #revoke()} on it as its own explicit step, then persisting both,
   * mirroring {@code ActivateSigningKeyForOrganizationService}'s own retire-then-activate shape.
   */
  public static RefreshToken rotatedFrom(
      final RefreshToken supersededToken, final String newTokenHash, final Instant newExpiresAt) {
    return new RefreshToken(
        UUID.randomUUID(),
        supersededToken.sessionId,
        supersededToken.accountId,
        newTokenHash,
        supersededToken.id,
        Instant.now(),
        newExpiresAt,
        null);
  }

  @SuppressWarnings("java:S107") // same rationale as the private constructor's own suppression
  public static RefreshToken reconstitute(
      final UUID id,
      final UUID sessionId,
      final AccountId accountId,
      final String tokenHash,
      final UUID rotatedFromId,
      final Instant issuedAt,
      final Instant expiresAt,
      final Instant revokedAt) {
    return new RefreshToken(
        id, sessionId, accountId, tokenHash, rotatedFromId, issuedAt, expiresAt, revokedAt);
  }

  /** Rotating away, explicit revocation (logout), or the BR-ID-03 reuse cascade — all the same. */
  public void revoke() {
    this.revokedAt = Instant.now();
  }

  /** Not revoked and not naturally expired — the only state a rotation request may succeed from. */
  public boolean isActive() {
    return revokedAt == null && expiresAt.isAfter(Instant.now());
  }

  /** Distinguishes "already used/revoked" (BR-ID-03 reuse) from ordinary natural expiry. */
  public boolean isRevoked() {
    return revokedAt != null;
  }

  public UUID id() {
    return id;
  }

  public UUID sessionId() {
    return sessionId;
  }

  public AccountId accountId() {
    return accountId;
  }

  public String tokenHash() {
    return tokenHash;
  }

  public Optional<UUID> rotatedFromId() {
    return Optional.ofNullable(rotatedFromId);
  }

  public Instant issuedAt() {
    return issuedAt;
  }

  public Instant expiresAt() {
    return expiresAt;
  }

  public Optional<Instant> revokedAt() {
    return Optional.ofNullable(revokedAt);
  }
}
