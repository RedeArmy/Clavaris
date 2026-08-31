package com.clavaris.identity.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * BR-ID-02/BR-ID-09: one linked social identity for an {@link Account} — deliberately a separate
 * table/class from {@code Account}, same "never a nullable field on the core entity" convention
 * {@link PasswordCredential} already establishes, so BR-ID-02's "never zero auth methods" invariant
 * stays a natural {@code COUNT} across both tables rather than a null-check special case.
 *
 * <p>{@link #providerUserId} is the provider's own opaque, stable subject identifier ({@code sub}
 * for Google's OIDC id token, the numeric user id for GitHub) — deliberately never the email
 * address, which can change on the provider's own side independent of this row. Never created
 * directly from a fresh social login; only ever the result of {@code
 * ConfirmPendingSocialLinkService} consuming a {@link PendingSocialLink} (ADR-0020 Decision 1,
 * BR-ID-09) or a brand-new self-service signup with no pre-existing {@code Account} to conflict
 * with — see that service's own Javadoc for the full linking decision.
 *
 * <p>Same record-style-accessor PMD suppressions as every other value object in this codebase.
 * PMD.DataClass: deliberately nothing but an immutable link (no state transition like {@code
 * VerificationToken}'s own {@code consume()}/{@code isActive()} — a social link, once made, has no
 * further lifecycle in v1), not a class that should grow behavior to satisfy the metric.
 */
@SuppressWarnings({
  "PMD.AvoidFieldNameMatchingMethodName",
  "PMD.ShortVariable",
  "PMD.ShortMethodName",
  "PMD.DataClass"
})
public final class SocialIdentity {

  private final UUID id;
  private final AccountId accountId;
  private final OrganizationId organizationId;
  private final SocialProvider provider;
  private final String providerUserId;
  private final Instant linkedAt;

  private SocialIdentity(
      final UUID id,
      final AccountId accountId,
      final OrganizationId organizationId,
      final SocialProvider provider,
      final String providerUserId,
      final Instant linkedAt) {
    this.id = Objects.requireNonNull(id, "id must not be null");
    this.accountId = Objects.requireNonNull(accountId, "accountId must not be null");
    this.organizationId = Objects.requireNonNull(organizationId, "organizationId must not be null");
    this.provider = Objects.requireNonNull(provider, "provider must not be null");
    this.providerUserId = Objects.requireNonNull(providerUserId, "providerUserId must not be null");
    this.linkedAt = Objects.requireNonNull(linkedAt, "linkedAt must not be null");
    if (providerUserId.isBlank()) {
      // Guards against an OAuth2 provider response missing its own subject claim reaching
      // persistence silently — an identity linked to a blank provider id is a security bug, not a
      // validation nicety (same posture PasswordCredential's own blank-hash guard already takes).
      throw new IllegalArgumentException("providerUserId must not be blank");
    }
  }

  // CLAUDE.md §5: organizationId is carried on the identity itself, not derived from accountId at
  // lookup time — SocialIdentityRepository's own returning-login query is scoped by it (code
  // review finding, never shipped as a global (provider, providerUserId) lookup would have
  // allowed a login through one Organization to resolve an Account that actually belongs to
  // another).
  public static SocialIdentity link(
      final AccountId accountId,
      final OrganizationId organizationId,
      final SocialProvider provider,
      final String providerUserId) {
    return new SocialIdentity(
        UUID.randomUUID(), accountId, organizationId, provider, providerUserId, Instant.now());
  }

  /** Rehydrates an existing row — preserves the real persisted {@code id}/{@code linkedAt}. */
  public static SocialIdentity reconstitute(
      final UUID id,
      final AccountId accountId,
      final OrganizationId organizationId,
      final SocialProvider provider,
      final String providerUserId,
      final Instant linkedAt) {
    return new SocialIdentity(id, accountId, organizationId, provider, providerUserId, linkedAt);
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
