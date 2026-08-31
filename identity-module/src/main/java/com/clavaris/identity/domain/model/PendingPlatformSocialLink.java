package com.clavaris.identity.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * {@link PendingSocialLink}'s platform-tier sibling — same ADR-0020 Decision 1/BR-ID-09 invariants,
 * scoped to a {@link PlatformAccount} instead of an {@link Account}. See {@link
 * PendingSocialLink}'s own Javadoc for the full linking-confirmation reasoning; only the owning-id
 * type differs here, same mirroring convention {@link PlatformVerificationToken} already
 * establishes for its own pair.
 *
 * <p>Shared state/lifecycle lives on {@link AbstractPendingSocialLink} — see its own Javadoc for
 * why this pair shares a base while the sibling {@code
 * AuthenticatePlatformAccountWithSocialProviderService} pair does not.
 *
 * <p>PMD.ShortVariable/PMD.LongVariable: {@code id}/{@code confirmationTokenHash} name exactly what
 * they are — same convention {@link AbstractPendingSocialLink}'s own identical suppression already
 * documents for these same two constructor parameters.
 */
@SuppressWarnings({"PMD.ShortVariable", "PMD.LongVariable"})
public final class PendingPlatformSocialLink extends AbstractPendingSocialLink<PlatformAccountId> {

  private PendingPlatformSocialLink(
      final UUID id,
      final PlatformAccountId platformAccountId,
      final SocialProvider provider,
      final String providerUserId,
      final String confirmationTokenHash,
      final Instant expiresAt,
      final Instant consumedAt) {
    super(
        id,
        platformAccountId,
        provider,
        providerUserId,
        confirmationTokenHash,
        expiresAt,
        consumedAt);
  }

  public static PendingPlatformSocialLink raise(
      final PlatformAccountId platformAccountId,
      final SocialProvider provider,
      final String providerUserId,
      final String confirmationTokenHash,
      final Instant expiresAt) {
    return new PendingPlatformSocialLink(
        UUID.randomUUID(),
        platformAccountId,
        provider,
        providerUserId,
        confirmationTokenHash,
        expiresAt,
        null);
  }

  public static PendingPlatformSocialLink reconstitute(
      final UUID id,
      final PlatformAccountId platformAccountId,
      final SocialProvider provider,
      final String providerUserId,
      final String confirmationTokenHash,
      final Instant expiresAt,
      final Instant consumedAt) {
    return new PendingPlatformSocialLink(
        id,
        platformAccountId,
        provider,
        providerUserId,
        confirmationTokenHash,
        expiresAt,
        consumedAt);
  }

  public PlatformAccountId platformAccountId() {
    return owningId();
  }
}
