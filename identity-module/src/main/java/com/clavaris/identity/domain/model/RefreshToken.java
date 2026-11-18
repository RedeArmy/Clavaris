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
 * <p>Several things set {@link #revokedAt}: rotating away, the reuse-detection cascade, and the
 * revocations that are not about the token at all (a sign-out, a revoked session, a password
 * change, a suspension). Only the first is a replay when presented again, and it is the only one
 * that has a successor, a token whose {@link #rotatedFromId} is this one. So "revoked" alone is not
 * the reuse signal: "revoked and already exchanged for another token" is (TD-SEC-091).
 *
 * <p>RFC 6749 §6: a refresh token is bound to the client it was issued to, and only that client may
 * redeem it. {@link #registeredClientId} carries that binding, and every successor inherits it. It
 * is empty only on rows issued before the binding existed; the first rotation of such a row adopts
 * the client that presents it (see {@link #rotatedFrom(RefreshToken, String, Instant, Instant,
 * String)}).
 *
 * <p>PMD suppressions below: coding-standards.md §3a.
 */
public final class RefreshToken {

  private final UUID id;
  private final UUID sessionId;
  private final AccountId accountId;
  private final String tokenHash;
  private final UUID rotatedFromId;
  private final String registeredClientId;
  private final Instant issuedAt;
  private final Instant expiresAt;
  private Instant revokedAt;

  // One parameter per persisted column — same rationale as OAuthClient's own S107 suppression: a
  // rehydration factory for a 9-column aggregate takes 8 parameters, not a sign this constructor
  // does too much. A synthetic parameter-object purely to dodge the threshold would add
  // indirection without removing any real complexity.
  private RefreshToken(
      final UUID id,
      final UUID sessionId,
      final AccountId accountId,
      final String tokenHash,
      final UUID rotatedFromId,
      final String registeredClientId,
      final Instant issuedAt,
      final Instant expiresAt,
      final Instant revokedAt) {
    this.id = Objects.requireNonNull(id, "id must not be null");
    this.sessionId = Objects.requireNonNull(sessionId, "sessionId must not be null");
    this.accountId = Objects.requireNonNull(accountId, "accountId must not be null");
    this.tokenHash = Objects.requireNonNull(tokenHash, "tokenHash must not be null");
    this.rotatedFromId = rotatedFromId;
    this.registeredClientId = registeredClientId;
    this.issuedAt = Objects.requireNonNull(issuedAt, "issuedAt must not be null");
    this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt must not be null");
    this.revokedAt = revokedAt;
  }

  /**
   * The first token in a brand-new {@link Session}'s chain — {@link #rotatedFromId} is empty —
   * bound to the client it is issued to.
   */
  public static RefreshToken issue(
      final UUID sessionId,
      final AccountId accountId,
      final String tokenHash,
      final Instant issuedAt,
      final Instant expiresAt,
      final String registeredClientId) {
    return new RefreshToken(
        UUID.randomUUID(),
        sessionId,
        accountId,
        tokenHash,
        null,
        Objects.requireNonNull(registeredClientId, "registeredClientId must not be null"),
        issuedAt,
        expiresAt,
        null);
  }

  /**
   * A token with no client binding, the shape of every row issued before the binding existed. Kept
   * for tests of the parts of the lifecycle that do not involve the client; production issuance
   * always goes through the bound overload.
   */
  public static RefreshToken issue(
      final UUID sessionId,
      final AccountId accountId,
      final String tokenHash,
      final Instant issuedAt,
      final Instant expiresAt) {
    return new RefreshToken(
        UUID.randomUUID(), sessionId, accountId, tokenHash, null, null, issuedAt, expiresAt, null);
  }

  /**
   * Issues the next token in {@code supersededToken}'s chain — does NOT mark {@code
   * supersededToken} itself revoked; the caller (the use case orchestrating the rotation) is
   * responsible for calling {@link #revoke()} on it as its own explicit step, then persisting both,
   * mirroring {@code ActivateSigningKeyForOrganizationService}'s own retire-then-activate shape.
   */
  public static RefreshToken rotatedFrom(
      final RefreshToken supersededToken,
      final String newTokenHash,
      final Instant issuedAt,
      final Instant newExpiresAt) {
    return rotatedFrom(
        supersededToken, newTokenHash, issuedAt, newExpiresAt, supersededToken.registeredClientId);
  }

  /**
   * As above, with the client the successor is bound to stated by the caller: the presenting client
   * when {@code supersededToken} predates the binding and so carries none, otherwise the same
   * client the superseded token was bound to.
   */
  public static RefreshToken rotatedFrom(
      final RefreshToken supersededToken,
      final String newTokenHash,
      final Instant issuedAt,
      final Instant newExpiresAt,
      final String registeredClientId) {
    return new RefreshToken(
        UUID.randomUUID(),
        supersededToken.sessionId,
        supersededToken.accountId,
        newTokenHash,
        supersededToken.id,
        registeredClientId,
        issuedAt,
        newExpiresAt,
        null);
  }

  public static RefreshToken reconstitute(
      final UUID id,
      final UUID sessionId,
      final AccountId accountId,
      final String tokenHash,
      final UUID rotatedFromId,
      final Instant issuedAt,
      final Instant expiresAt,
      final Instant revokedAt) {
    return reconstitute(
        id, sessionId, accountId, tokenHash, rotatedFromId, null, issuedAt, expiresAt, revokedAt);
  }

  public static RefreshToken reconstitute(
      final UUID id,
      final UUID sessionId,
      final AccountId accountId,
      final String tokenHash,
      final UUID rotatedFromId,
      final String registeredClientId,
      final Instant issuedAt,
      final Instant expiresAt,
      final Instant revokedAt) {
    return new RefreshToken(
        id,
        sessionId,
        accountId,
        tokenHash,
        rotatedFromId,
        registeredClientId,
        issuedAt,
        expiresAt,
        revokedAt);
  }

  /**
   * Whether {@code registeredClientId} may redeem this token: the client it is bound to, or any
   * client when it predates the binding (the rotation then binds the successor to that client).
   */
  public boolean isRedeemableBy(final String registeredClientId) {
    return this.registeredClientId == null || this.registeredClientId.equals(registeredClientId);
  }

  /**
   * Rotating away, explicit revocation (logout), or the BR-ID-03 reuse cascade — all the same. The
   * caller supplies the time (TD-ARCH-052), so a test can pin it and the use case reads one clock.
   */
  public void revoke(final Instant at) {
    this.revokedAt = Objects.requireNonNull(at, "at must not be null");
  }

  /**
   * Not revoked and not naturally expired at {@code now} — the only state a rotation request may
   * succeed from. A token whose {@code expiresAt} is exactly {@code now} is already expired.
   */
  public boolean isActive(final Instant now) {
    return revokedAt == null && expiresAt.isAfter(now);
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

  public Optional<String> registeredClientId() {
    return Optional.ofNullable(registeredClientId);
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
