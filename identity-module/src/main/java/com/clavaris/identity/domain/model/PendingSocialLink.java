package com.clavaris.identity.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * ADR-0020 Decision 1, BR-ID-09: the confirmation-step aggregate this codebase's own account-
 * linking decision requires. Raised when a social login's verified email matches an existing {@link
 * Account} that was created by a <em>different</em> method (password, or a different {@link
 * SocialProvider}) — never for a brand-new signup with no pre-existing account to conflict with,
 * which links immediately instead.
 *
 * <p><b>Why this exists at all</b> (see ADR-0020 Decision 1's own full reasoning): {@code
 * RegisterAccountController}'s self-service registration is ungated — an email can be pre-
 * registered, unverified, by anyone. Trusting "the social login's own email is verified, and it
 * matches" as sufficient to link would let an attacker who pre-registered an email they don't
 * control silently intercept the real owner's first legitimate social login. This row exists
 * specifically so linking never happens without the account holder proving they still control the
 * email of record — same single-use, time-limited, hash-only shape as {@link VerificationToken}
 * (BR-ID-05: the token is delivered only to that email, never observable any other way), consuming
 * it is what actually inserts the real {@link SocialIdentity} row, this row itself is never on its
 * own a valid authentication method.
 *
 * <p>Same record-style-accessor PMD suppressions as every other value object in this codebase.
 * PMD.LongVariable: {@code confirmationTokenHash} names exactly what it is — a shortened identifier
 * would only make this class harder to read, same convention every other descriptively- named
 * field/param in this codebase follows. Class-level, not per-occurrence, to avoid tripping
 * PMD.AvoidDuplicateLiterals on the repeated suppression string.
 */
@SuppressWarnings({
  "PMD.AvoidFieldNameMatchingMethodName",
  "PMD.ShortVariable",
  "PMD.ShortMethodName",
  "PMD.LongVariable"
})
public final class PendingSocialLink {

  private final UUID id;
  private final AccountId accountId;
  private final SocialProvider provider;
  private final String providerUserId;
  private final String confirmationTokenHash;
  private final Instant expiresAt;
  private Instant consumedAt;

  private PendingSocialLink(
      final UUID id,
      final AccountId accountId,
      final SocialProvider provider,
      final String providerUserId,
      final String confirmationTokenHash,
      final Instant expiresAt,
      final Instant consumedAt) {
    this.id = Objects.requireNonNull(id, "id must not be null");
    this.accountId = Objects.requireNonNull(accountId, "accountId must not be null");
    this.provider = Objects.requireNonNull(provider, "provider must not be null");
    this.providerUserId = Objects.requireNonNull(providerUserId, "providerUserId must not be null");
    this.confirmationTokenHash =
        Objects.requireNonNull(confirmationTokenHash, "confirmationTokenHash must not be null");
    this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt must not be null");
    this.consumedAt = consumedAt;
  }

  /** A freshly-raised pending link — {@link #consumedAt} is empty until {@link #consume()}. */
  public static PendingSocialLink raise(
      final AccountId accountId,
      final SocialProvider provider,
      final String providerUserId,
      final String confirmationTokenHash,
      final Instant expiresAt) {
    return new PendingSocialLink(
        UUID.randomUUID(),
        accountId,
        provider,
        providerUserId,
        confirmationTokenHash,
        expiresAt,
        null);
  }

  public static PendingSocialLink reconstitute(
      final UUID id,
      final AccountId accountId,
      final SocialProvider provider,
      final String providerUserId,
      final String confirmationTokenHash,
      final Instant expiresAt,
      final Instant consumedAt) {
    return new PendingSocialLink(
        id, accountId, provider, providerUserId, confirmationTokenHash, expiresAt, consumedAt);
  }

  /** BR-ID-09: single-use — a successful confirmation consumes the pending link. */
  public void consume() {
    this.consumedAt = Instant.now();
  }

  /** Not consumed and not naturally expired — the only state a confirmation may succeed from. */
  public boolean isActive() {
    return consumedAt == null && expiresAt.isAfter(Instant.now());
  }

  public UUID id() {
    return id;
  }

  public AccountId accountId() {
    return accountId;
  }

  public SocialProvider provider() {
    return provider;
  }

  public String providerUserId() {
    return providerUserId;
  }

  public String confirmationTokenHash() {
    return confirmationTokenHash;
  }

  public Instant expiresAt() {
    return expiresAt;
  }

  public Optional<Instant> consumedAt() {
    return Optional.ofNullable(consumedAt);
  }
}
