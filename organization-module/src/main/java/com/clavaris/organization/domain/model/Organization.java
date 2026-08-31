package com.clavaris.organization.domain.model;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * ADR-0010: the tenant isolation boundary — one row per consuming system (e.g. "JobSeeker" is one
 * {@code Organization}), owning its own fully isolated pool of {@code Account}s and {@code
 * OAuthClient}s. Not to be confused with {@code Workspace}, a team/company grouping *within* one
 * Organization's account pool.
 *
 * <p>{@code ownerPlatformAccountId} (ADR-0012): exactly one owning {@code PlatformAccount} per
 * Organization — a plain {@link UUID}, not identity-module's own {@code PlatformAccountId} type,
 * since organization-module never depends on identity-module (same module-independence discipline
 * {@code SigningKeyProvisioner}'s own Javadoc documents for {@code SigningKey}).
 *
 * <p>{@code allowedSocialProviders} (ADR-0020 Decision 3, BR-ID-12): plain provider-name strings
 * (e.g. {@code "GOOGLE"}), not identity-module's own {@code SocialProvider} enum — same
 * module-independence rule. {@link #socialLoginEnabled} governs only whether social login is
 * *additionally* offered on top of email/password, which this module has no code path to disable at
 * all — there is nothing here that could gate it even if a caller tried to.
 *
 * <p>PMD's AvoidFieldNameMatchingMethodName/ShortVariable/ShortMethodName rules flag this class for
 * the same reason {@code Account} (identity-module) suppresses them — the deliberate record-style
 * accessor convention used throughout this codebase's value objects, not an accidental data-holder
 * shape.
 */
@SuppressWarnings({
  "PMD.AvoidFieldNameMatchingMethodName",
  "PMD.ShortVariable",
  "PMD.ShortMethodName",
  "PMD.LongVariable"
})
public final class Organization {

  // Matches the organizations.name column (varchar(255), V20260818170300 migration) — enforced
  // here too, not only at the web-layer DTOs' own @Size(max = 255): security finding (SDE-III
  // review, 2026-08-22) — before this, an over-length name passed Bean Validation entirely and
  // only failed at the DB as an unhandled DataIntegrityViolationException (a raw 500), and any
  // future caller of register()/reconstitute() that skips the web layer would have hit the same
  // gap with no check at all.
  private static final int MAX_NAME_LENGTH = 255;

  private final UUID id;
  private final String name;
  private final Instant createdAt;
  private final UUID ownerPlatformAccountId;
  private final boolean socialLoginEnabled;
  private final List<String> allowedSocialProviders;

  private Organization(
      final UUID id,
      final String name,
      final Instant createdAt,
      final UUID ownerPlatformAccountId,
      final boolean socialLoginEnabled,
      final List<String> allowedSocialProviders) {
    this.id = Objects.requireNonNull(id, "id must not be null");
    this.name = requireValidName(name);
    this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
    this.ownerPlatformAccountId =
        Objects.requireNonNull(ownerPlatformAccountId, "ownerPlatformAccountId must not be null");
    this.socialLoginEnabled = socialLoginEnabled;
    this.allowedSocialProviders =
        List.copyOf(
            Objects.requireNonNull(
                allowedSocialProviders, "allowedSocialProviders must not be null"));
  }

  // ADR-0012: created either by the owning PlatformAccount itself via the session-authenticated
  // dashboard, or by a Clavaris operator via POST /api/v1/admin/organizations (BR-ORG-06) on that
  // PlatformAccount's behalf — either path resolves to this same factory, ownerPlatformAccountId
  // is never optional. ADR-0020 Decision 3: social login starts closed — an Organization opts in
  // explicitly afterward via withSocialLoginPolicy, never enabled by default.
  public static Organization register(final String name, final UUID ownerPlatformAccountId) {
    return new Organization(
        UUID.randomUUID(), name, Instant.now(), ownerPlatformAccountId, false, List.of());
  }

  public static Organization reconstitute(
      final UUID id,
      final String name,
      final Instant createdAt,
      final UUID ownerPlatformAccountId,
      final boolean socialLoginEnabled,
      final List<String> allowedSocialProviders) {
    return new Organization(
        id, name, createdAt, ownerPlatformAccountId, socialLoginEnabled, allowedSocialProviders);
  }

  /**
   * ADR-0020 Decision 3: returns a new instance with an updated social-login policy — same
   * immutable-update shape {@code RateLimitPolicy.withRequestsPerMinute} already establishes for
   * this module's own aggregates, not an in-place mutator. {@code enabled = false} is always valid
   * regardless of {@code providers} (email/password never depends on this at all); {@code enabled =
   * true} with an empty {@code providers} list is also structurally valid but a real no-op
   * configuration state — left for the use case layer to flag as a likely operator mistake, not
   * rejected here as invalid. {@code
   * setsocialloginpolicyfororganization.SetSocialLoginPolicyForOrganizationService} is that flag
   * (code review finding: actually implemented, not just named as future work).
   */
  public Organization withSocialLoginPolicy(final boolean enabled, final List<String> providers) {
    return new Organization(id, name, createdAt, ownerPlatformAccountId, enabled, providers);
  }

  private static String requireValidName(final String name) {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("Organization name must not be blank");
    }
    if (name.length() > MAX_NAME_LENGTH) {
      throw new IllegalArgumentException(
          "Organization name must not exceed " + MAX_NAME_LENGTH + " characters");
    }
    return name;
  }

  public UUID id() {
    return id;
  }

  public String name() {
    return name;
  }

  public Instant createdAt() {
    return createdAt;
  }

  public UUID ownerPlatformAccountId() {
    return ownerPlatformAccountId;
  }

  public boolean socialLoginEnabled() {
    return socialLoginEnabled;
  }

  public List<String> allowedSocialProviders() {
    return allowedSocialProviders;
  }
}
