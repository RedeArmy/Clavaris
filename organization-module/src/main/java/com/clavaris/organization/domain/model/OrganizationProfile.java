package com.clavaris.organization.domain.model;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * What an Organization says about itself, apart from its name: a description for its card on the
 * dashboard, the name of the consuming application it fronts, and its brand colour. Whether it has
 * a logo is recorded here too ({@link #logoUpdatedAt()}); the image itself is an {@link
 * OrganizationLogo}, kept apart so that listing profiles never reads it.
 *
 * <p>Every field is optional, and a blank one is the same as an absent one: the profile of an
 * Organization nobody has filled in is {@link #empty}, which is what every existing Organization
 * starts as. {@code applicationName} and {@code brandColor} are the Organization's defaults for the
 * consuming application's sign-in, consent and email branding; an OAuth client's own branding wins
 * over them.
 *
 * <p>The limits match the {@code organization_profiles} columns, and are enforced here as well as
 * on the dashboard form so a caller that skips the web layer gets the same answer.
 */
// PMD.AvoidFieldNameMatchingMethodName/ShortVariable/LongVariable: same value-object conventions
// Organization's own identical suppression documents. PMD.TooManyMethods: a value object with this
// many fields, each with an accessor and a way to change it.
@SuppressWarnings({
  "PMD.AvoidFieldNameMatchingMethodName",
  "PMD.LongVariable",
  "PMD.TooManyMethods",
  "java:S107"
})
public final class OrganizationProfile {

  public static final int MAX_DESCRIPTION_LENGTH = 500;
  public static final int MAX_APPLICATION_NAME_LENGTH = 100;

  private static final Pattern HEX_COLOR = Pattern.compile("^#[0-9A-Fa-f]{6}$");

  private final UUID organizationId;
  private final String description;
  private final String applicationName;
  private final String brandColor;
  private final Instant logoUpdatedAt;
  private final Instant updatedAt;

  private OrganizationProfile(
      final UUID organizationId,
      final String description,
      final String applicationName,
      final String brandColor,
      final Instant logoUpdatedAt,
      final Instant updatedAt) {
    this.organizationId = organizationId;
    this.description = limited(description, MAX_DESCRIPTION_LENGTH, "description");
    this.applicationName =
        limited(applicationName, MAX_APPLICATION_NAME_LENGTH, "application name");
    this.brandColor = validColor(brandColor);
    this.logoUpdatedAt = logoUpdatedAt;
    this.updatedAt = updatedAt;
  }

  /** The profile of an Organization nobody has filled in: nothing set, no logo. */
  public static OrganizationProfile empty(final UUID organizationId) {
    return new OrganizationProfile(organizationId, null, null, null, null, Instant.now());
  }

  /** A row as it was persisted. */
  public static OrganizationProfile reconstitute(
      final UUID organizationId,
      final String description,
      final String applicationName,
      final String brandColor,
      final Instant logoUpdatedAt,
      final Instant updatedAt) {
    return new OrganizationProfile(
        organizationId, description, applicationName, brandColor, logoUpdatedAt, updatedAt);
  }

  /** The same profile with new descriptive details (the logo is left as it is). */
  public OrganizationProfile withDetails(
      final String newDescription, final String newApplicationName, final String newBrandColor) {
    return new OrganizationProfile(
        organizationId,
        newDescription,
        newApplicationName,
        newBrandColor,
        logoUpdatedAt,
        Instant.now());
  }

  /** The same profile, now with a logo uploaded at {@code uploadedAt}. */
  public OrganizationProfile withLogoUpdatedAt(final Instant uploadedAt) {
    return new OrganizationProfile(
        organizationId, description, applicationName, brandColor, uploadedAt, Instant.now());
  }

  /** The same profile, with no logo. */
  public OrganizationProfile withoutLogo() {
    return new OrganizationProfile(
        organizationId, description, applicationName, brandColor, null, Instant.now());
  }

  public UUID organizationId() {
    return organizationId;
  }

  /** The description shown on the Organization's card, if it has one. */
  public Optional<String> description() {
    return Optional.ofNullable(description);
  }

  /** The name of the consuming application, if it has been set. */
  public Optional<String> applicationName() {
    return Optional.ofNullable(applicationName);
  }

  /** The brand colour as {@code #rrggbb} (lower case), if it has been set. */
  public Optional<String> brandColor() {
    return Optional.ofNullable(brandColor);
  }

  /** When the logo was last set, or empty when the Organization has none. */
  public Optional<Instant> logoUpdatedAt() {
    return Optional.ofNullable(logoUpdatedAt);
  }

  public boolean hasLogo() {
    return logoUpdatedAt != null;
  }

  public Instant updatedAt() {
    return updatedAt;
  }

  // Blank is absent; anything else is trimmed and must fit the column.
  private static String limited(final String value, final int max, final String what) {
    final String trimmed = value == null ? "" : value.strip();
    if (trimmed.length() > max) {
      throw new IllegalArgumentException(
          "Organization " + what + " must not exceed " + max + " characters");
    }
    return trimmed.isEmpty() ? null : trimmed;
  }

  private static String validColor(final String value) {
    final String trimmed = value == null ? "" : value.strip();
    if (!trimmed.isEmpty() && !HEX_COLOR.matcher(trimmed).matches()) {
      throw new IllegalArgumentException(
          "Organization brand colour must be a hex colour such as #2563eb");
    }
    return trimmed.isEmpty() ? null : trimmed.toLowerCase(java.util.Locale.ROOT);
  }
}
