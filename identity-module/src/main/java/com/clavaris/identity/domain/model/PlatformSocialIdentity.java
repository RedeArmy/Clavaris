package com.clavaris.identity.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * {@link SocialIdentity}'s platform-tier sibling — same BR-ID-09/ADR-0020 invariants, scoped to a
 * {@link PlatformAccount} instead of an {@link Account}, no {@code Organization} scoping (same
 * mirroring convention {@link PlatformPasswordCredential} already establishes for its own pair; see
 * {@link PlatformVerificationToken}'s own Javadoc for why {@link SocialProvider} itself is reused
 * as-is while the owning-id type is the one thing that has to differ). ADR-0020 Decision 2: this
 * and {@link PlatformPasswordCredential} coexist permanently for a given {@link PlatformAccount} —
 * social login here is additive, never a replacement.
 *
 * <p>Same record-style-accessor PMD suppressions as every other value object in this codebase.
 * PMD.DataClass: deliberately nothing but an immutable link — same rationale {@link
 * SocialIdentity}'s own identical suppression documents.
 */
@SuppressWarnings({
  "PMD.AvoidFieldNameMatchingMethodName",
  "PMD.ShortVariable",
  "PMD.DataClass",
  "PMD.ShortMethodName"
})
public final class PlatformSocialIdentity {

  private final UUID id;
  private final PlatformAccountId platformAccountId;
  private final SocialProvider provider;
  private final String providerUserId;
  private final Instant linkedAt;

  private PlatformSocialIdentity(
      final UUID id,
      final PlatformAccountId platformAccountId,
      final SocialProvider provider,
      final String providerUserId,
      final Instant linkedAt) {
    this.id = Objects.requireNonNull(id, "id must not be null");
    this.platformAccountId =
        Objects.requireNonNull(platformAccountId, "platformAccountId must not be null");
    this.provider = Objects.requireNonNull(provider, "provider must not be null");
    this.providerUserId = Objects.requireNonNull(providerUserId, "providerUserId must not be null");
    this.linkedAt = Objects.requireNonNull(linkedAt, "linkedAt must not be null");
    if (providerUserId.isBlank()) {
      throw new IllegalArgumentException("providerUserId must not be blank");
    }
  }

  public static PlatformSocialIdentity link(
      final PlatformAccountId platformAccountId,
      final SocialProvider provider,
      final String providerUserId) {
    return new PlatformSocialIdentity(
        UUID.randomUUID(), platformAccountId, provider, providerUserId, Instant.now());
  }

  public static PlatformSocialIdentity reconstitute(
      final UUID id,
      final PlatformAccountId platformAccountId,
      final SocialProvider provider,
      final String providerUserId,
      final Instant linkedAt) {
    return new PlatformSocialIdentity(id, platformAccountId, provider, providerUserId, linkedAt);
  }

  public UUID id() {
    return id;
  }

  public PlatformAccountId platformAccountId() {
    return platformAccountId;
  }

  public SocialProvider provider() {
    return provider;
  }

  public String providerUserId() {
    return providerUserId;
  }

  public Instant linkedAt() {
    return linkedAt;
  }
}
