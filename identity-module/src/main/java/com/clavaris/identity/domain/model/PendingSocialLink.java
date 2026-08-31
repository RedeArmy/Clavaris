package com.clavaris.identity.domain.model;

import java.time.Instant;
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
 * <p>Shared state/lifecycle (every field except {@link #accountId()}, plus {@code consume()}/
 * {@code isActive()}) lives on {@link AbstractPendingSocialLink} — see its own Javadoc for why this
 * pair shares a base while the sibling {@code AuthenticateWithSocialProviderService} pair does not.
 *
 * <p>PMD.ShortVariable/PMD.LongVariable: {@code id}/{@code confirmationTokenHash} name exactly what
 * they are — same convention {@link AbstractPendingSocialLink}'s own identical suppression already
 * documents for these same two constructor parameters.
 */
@SuppressWarnings({"PMD.ShortVariable", "PMD.LongVariable"})
public final class PendingSocialLink extends AbstractPendingSocialLink<AccountId> {

  private PendingSocialLink(
      final UUID id,
      final AccountId accountId,
      final SocialProvider provider,
      final String providerUserId,
      final String confirmationTokenHash,
      final Instant expiresAt,
      final Instant consumedAt) {
    super(id, accountId, provider, providerUserId, confirmationTokenHash, expiresAt, consumedAt);
  }

  /** A freshly-raised pending link — {@link #consumedAt()} is empty until {@link #consume()}. */
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

  public AccountId accountId() {
    return owningId();
  }
}
