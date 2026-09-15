package com.clavaris.identity.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * BR-ID-03: single-use, rotate-on-use. Only {@link #tokenHash} is stored, never the bearer value —
 * same hash-not-plaintext principle as {@code PasswordCredential} (data-model.md §2). Unlike {@code
 * oauth2_authorization} (TD-SEC-019, which stores raw values because Spring Authorization Server's
 * own lookup requires it), this table never goes through that service at all.
 *
 * <p>{@link #rotatedFromId} is an audit trail, not the reuse check's own source of truth: the only
 * two things that ever set {@link #revokedAt} are rotating away or the reuse-detection cascade —
 * either way, a presented token whose row already has {@code revokedAt} set is reuse, full stop.
 *
 * <p>PMD suppressions below: coding-standards.md §3a.
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
