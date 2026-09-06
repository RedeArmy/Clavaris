package com.clavaris.clientregistry.domain.model;

import java.net.URI;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * ADR-0009 §3: one-to-one, optional theming data for an {@code OAuthClient}'s hosted login/consent
 * pages — logo, primary color, application display name. Own table, not a nullable column bolted
 * onto {@code OAuthClient} itself — same "separate table" convention {@code PasswordCredential}/
 * {@code SocialIdentity} already establish (data-model.md §2), and the same idiom {@link
 * RedirectPolicy} already establishes for a client-scoped, optional-by-default aggregate: absence
 * of a row means "use Clavaris's own default look," never an error.
 */
// LongVariable: applicationDisplayName/MAX_DISPLAY_NAME_LENGTH name exactly what ADR-0009 §3
// itself calls the field, same RedirectPolicy precedent. TooManyMethods: seven one-line
// accessors plus three rehydration/update factories is what a value object with this many fields
// looks like, same RedirectPolicy/OAuthClient precedent. OnlyOneReturn (per-method suppressions
// below): each validator's early-return-on-null is the clearest shape for "absent means skip
// validation," same convention this module already uses elsewhere.
@SuppressWarnings({
  "PMD.AvoidFieldNameMatchingMethodName",
  "PMD.ShortVariable",
  "PMD.ShortMethodName",
  "PMD.LongVariable",
  "PMD.TooManyMethods"
})
public final class ClientBranding {

  // #RGB or #RRGGBB — the two CSS hex-color shapes a "primary color" value needs to support;
  // anything else (named colors, rgb(...), CSS variables) is deliberately out of scope for v1 —
  // the hosted templates only ever interpolate this into a CSS custom property, so accepting an
  // arbitrary string here would be a real CSS-injection surface (BR-DATA-01-adjacent: this value
  // is operator-supplied, not end-user-supplied, but still rendered unescaped into a <style>
  // block).
  private static final Pattern HEX_COLOR_PATTERN =
      Pattern.compile("^#[0-9a-fA-F]{3}([0-9a-fA-F]{3})?$");

  private static final int MAX_DISPLAY_NAME_LENGTH = 100;

  private final UUID id;
  private final UUID oauthClientId;
  private final String logoUrl;
  private final String primaryColor;
  private final String applicationDisplayName;
  private final Instant createdAt;
  private final Instant updatedAt;

  private ClientBranding(
      final UUID id,
      final UUID oauthClientId,
      final String logoUrl,
      final String primaryColor,
      final String applicationDisplayName,
      final Instant createdAt,
      final Instant updatedAt) {
    this.id = Objects.requireNonNull(id, "id must not be null");
    this.oauthClientId = Objects.requireNonNull(oauthClientId, "oauthClientId must not be null");
    this.logoUrl = validateLogoUrl(logoUrl);
    this.primaryColor = validatePrimaryColor(primaryColor);
    this.applicationDisplayName = validateDisplayName(applicationDisplayName);
    this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
    this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt must not be null");
  }

  /**
   * The implicit answer for an {@code OAuthClient} that has never had branding configured — every
   * field absent, same "read-side default, never an error" convention {@link
   * RedirectPolicy#unconfigured} already establishes. Never persisted on its own.
   */
  public static ClientBranding unconfigured(final UUID oauthClientId) {
    return define(oauthClientId, null, null, null);
  }

  /** A brand-new branding row for an {@code OAuthClient} that has never had one set before. */
  public static ClientBranding define(
      final UUID oauthClientId,
      final String logoUrl,
      final String primaryColor,
      final String applicationDisplayName) {
    final Instant now = Instant.now();
    return new ClientBranding(
        UUID.randomUUID(), oauthClientId, logoUrl, primaryColor, applicationDisplayName, now, now);
  }

  /**
   * A real row already exists for this {@code OAuthClient} — replaces every field, keeping the
   * original {@code id}/{@code createdAt} (same "update in place, never a second row" convention as
   * {@link RedirectPolicy#withUrls}) and stamping a fresh {@code updatedAt}.
   */
  public ClientBranding withBranding(
      final String logoUrl, final String primaryColor, final String applicationDisplayName) {
    return new ClientBranding(
        id, oauthClientId, logoUrl, primaryColor, applicationDisplayName, createdAt, Instant.now());
  }

  public static ClientBranding reconstitute(
      final UUID id,
      final UUID oauthClientId,
      final String logoUrl,
      final String primaryColor,
      final String applicationDisplayName,
      final Instant createdAt,
      final Instant updatedAt) {
    return new ClientBranding(
        id, oauthClientId, logoUrl, primaryColor, applicationDisplayName, createdAt, updatedAt);
  }

  // Two exits (null passes through unchecked, a real value is validated) is clearer here than
  // forcing a single-return shape onto "absent" vs. "present" — same rationale RedirectPolicy's
  // own validateIfPresent suppression documents.
  @SuppressWarnings("PMD.OnlyOneReturn")
  private static String validateLogoUrl(final String logoUrl) {
    if (logoUrl == null) {
      return null;
    }
    // Well-formedness/absoluteness/https-only only — unlike RedirectPolicy's own URLs, a logo is
    // never matched against redirectUris (it's not a place the browser is ever redirected to), so
    // only OAuthClient's shared validator's first three checks are the relevant ones here.
    final URI parsed;
    try {
      parsed = URI.create(logoUrl);
    } catch (final IllegalArgumentException e) {
      throw new IllegalArgumentException("logoUrl must be a well-formed URI: " + logoUrl, e);
    }
    if (!parsed.isAbsolute() || !"https".equalsIgnoreCase(parsed.getScheme())) {
      throw new IllegalArgumentException("logoUrl must be an absolute https URL: " + logoUrl);
    }
    return logoUrl;
  }

  @SuppressWarnings("PMD.OnlyOneReturn")
  private static String validatePrimaryColor(final String primaryColor) {
    if (primaryColor == null) {
      return null;
    }
    if (!HEX_COLOR_PATTERN.matcher(primaryColor).matches()) {
      throw new IllegalArgumentException(
          "primaryColor must be a #RGB or #RRGGBB hex color: " + primaryColor);
    }
    return primaryColor;
  }

  @SuppressWarnings("PMD.OnlyOneReturn")
  private static String validateDisplayName(final String applicationDisplayName) {
    if (applicationDisplayName == null) {
      return null;
    }
    if (applicationDisplayName.isBlank()) {
      throw new IllegalArgumentException("applicationDisplayName must not be blank when present");
    }
    if (applicationDisplayName.length() > MAX_DISPLAY_NAME_LENGTH) {
      throw new IllegalArgumentException(
          "applicationDisplayName must be at most " + MAX_DISPLAY_NAME_LENGTH + " characters");
    }
    return applicationDisplayName;
  }

  public UUID id() {
    return id;
  }

  public UUID oauthClientId() {
    return oauthClientId;
  }

  public Optional<String> logoUrl() {
    return Optional.ofNullable(logoUrl);
  }

  public Optional<String> primaryColor() {
    return Optional.ofNullable(primaryColor);
  }

  public Optional<String> applicationDisplayName() {
    return Optional.ofNullable(applicationDisplayName);
  }

  public Instant createdAt() {
    return createdAt;
  }

  public Instant updatedAt() {
    return updatedAt;
  }
}
