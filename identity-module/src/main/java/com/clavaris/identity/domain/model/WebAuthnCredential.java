package com.clavaris.identity.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * TD-FUT-034, Clerk "View Profile" passkeys parity — one row per WebAuthn/passkey credential an
 * {@link Account} has registered. {@code credentialId}/{@code publicKeyCose} are public by design
 * (the whole point of public-key cryptography is that the public half needs no secrecy) — unlike
 * {@link KnownDevice}'s {@code deviceTokenHash} or {@code VerificationToken}'s {@code tokenHash},
 * neither is ever hashed before storage.
 *
 * <p>{@code signatureCount}/{@code lastUsedAt} mutate in place on every successful authentication
 * (see {@link #touch(long)}) — same shape {@link AbstractKnownDevice#touch()} already establishes
 * for this exact kind of "most fields immutable, a couple bumped on every use" lifecycle. A rising
 * {@code signatureCount} is itself a security property: the authenticator's own internal counter
 * must never go backwards or repeat across two accepted assertions — that check lives in the
 * WebAuthn ceremony verification (Yubico's {@code RelyingParty.finishAssertion}), not here; this
 * class only ever receives a count already verified to be an acceptable next value.
 */
// PMD.ShortVariable/ShortMethodName: id names exactly what it is — same convention KnownDevice's
// own identical suppression already documents for this same constructor parameter/accessor.
// PMD.AvoidFieldNameMatchingMethodName/DataClass/TooManyMethods: a plain field-holding value type
// with one accessor per field — same precedent AbstractKnownDevice's own identical suppression set
// already documents for this exact "mostly immutable, a couple of fields bumped on use" shape.
@SuppressWarnings({
  "PMD.ShortVariable",
  "PMD.ShortMethodName",
  "PMD.AvoidFieldNameMatchingMethodName",
  "PMD.DataClass",
  "PMD.TooManyMethods"
})
public final class WebAuthnCredential {

  private final UUID id;
  private final AccountId accountId;
  private final OrganizationId organizationId;
  private final byte[] credentialId;
  private final byte[] publicKeyCose;
  private final String transports;
  private final String nickname;
  private final Instant createdAt;
  private long signatureCount;
  private Instant lastUsedAt;

  @SuppressWarnings("java:S107")
  private WebAuthnCredential(
      final UUID id,
      final AccountId accountId,
      final OrganizationId organizationId,
      final byte[] credentialId,
      final byte[] publicKeyCose,
      final long signatureCount,
      final String transports,
      final String nickname,
      final Instant createdAt,
      final Instant lastUsedAt) {
    this.id = Objects.requireNonNull(id, "id must not be null");
    this.accountId = Objects.requireNonNull(accountId, "accountId must not be null");
    this.organizationId = Objects.requireNonNull(organizationId, "organizationId must not be null");
    this.credentialId =
        Objects.requireNonNull(credentialId, "credentialId must not be null").clone();
    this.publicKeyCose =
        Objects.requireNonNull(publicKeyCose, "publicKeyCose must not be null").clone();
    this.signatureCount = signatureCount;
    this.transports = transports;
    this.nickname = nickname;
    this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
    this.lastUsedAt = lastUsedAt;
  }

  /** A newly completed registration ceremony — never seen a successful assertion yet. */
  public static WebAuthnCredential register(
      final AccountId accountId,
      final OrganizationId organizationId,
      final byte[] credentialId,
      final byte[] publicKeyCose,
      final long signatureCount,
      final String transports,
      final String nickname) {
    return new WebAuthnCredential(
        UUID.randomUUID(),
        accountId,
        organizationId,
        credentialId,
        publicKeyCose,
        signatureCount,
        transports,
        nickname,
        Instant.now(),
        null);
  }

  @SuppressWarnings({"java:S107", "PMD.ExcessiveParameterList"})
  public static WebAuthnCredential reconstitute(
      final UUID id,
      final AccountId accountId,
      final OrganizationId organizationId,
      final byte[] credentialId,
      final byte[] publicKeyCose,
      final long signatureCount,
      final String transports,
      final String nickname,
      final Instant createdAt,
      final Instant lastUsedAt) {
    return new WebAuthnCredential(
        id,
        accountId,
        organizationId,
        credentialId,
        publicKeyCose,
        signatureCount,
        transports,
        nickname,
        createdAt,
        lastUsedAt);
  }

  /** Called on every successful authentication — bumps the counter and the last-used timestamp. */
  public void touch(final long newSignatureCount) {
    this.signatureCount = newSignatureCount;
    this.lastUsedAt = Instant.now();
  }

  public UUID id() {
    return id;
  }

  public AccountId accountId() {
    return accountId;
  }

  public OrganizationId organizationId() {
    return organizationId;
  }

  public byte[] credentialId() {
    return credentialId.clone();
  }

  public byte[] publicKeyCose() {
    return publicKeyCose.clone();
  }

  public long signatureCount() {
    return signatureCount;
  }

  public String transports() {
    return transports;
  }

  public String nickname() {
    return nickname;
  }

  public Instant createdAt() {
    return createdAt;
  }

  public Instant lastUsedAt() {
    return lastUsedAt;
  }
}
