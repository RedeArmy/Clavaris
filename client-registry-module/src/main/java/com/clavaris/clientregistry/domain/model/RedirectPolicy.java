package com.clavaris.clientregistry.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Clerk "customize redirect URLs" parity, adapted to Clavaris's own OIDC-first, redirect-based
 * architecture: the per-{@link OAuthClient} post-authentication landing-page configuration, used
 * only when there is no in-flight {@code /oauth2/authorize} request to resume. The OAuth2
 * authorization code's return to this same client's own registered {@code redirectUris} is
 * <b>never</b> overridden by this policy — doing so would break OIDC conformance (CLAUDE.md §2's
 * own success metric), a deliberate divergence from Clerk's own precedence, where a Force redirect
 * can override even an automatic {@code redirect_url}. One row per {@code OAuthClient} ({@code
 * oauthClientId} unique), absence-of-row means "fall straight through to the platform's own
 * hardcoded default" — same idiom {@link RateLimitPolicy} already establishes in
 * organization-module.
 *
 * <p>Every configured URL must be well-formed/absolute/secure (reuses {@link OAuthClient}'s own
 * package-private validator, same package) <b>and</b> a verbatim member of that same client's
 * {@code redirectUris} allowlist — the second check can't live in this class (it needs the owning
 * {@code OAuthClient}'s own field, which this value object never holds), so it's enforced once by
 * the caller ({@code SetRedirectPolicyForClientService}) before either factory below ever runs.
 */
// LongVariable: every configured URL field is named for exactly what Clerk's own equivalent
// concept calls it (fallbackSignInRedirectUrl, etc.) — a shortened name here would only make call
// sites harder to read, same reasoning OAuthClient's own postLogoutRedirectUris suppression
// documents. TooManyMethods: eight one-line accessors plus three rehydration/update factories is
// what a value object with this many fields looks like, same OAuthClient precedent.
@SuppressWarnings({
  "PMD.AvoidFieldNameMatchingMethodName",
  "PMD.ShortVariable",
  "PMD.ShortMethodName",
  "PMD.LongVariable",
  "PMD.TooManyMethods"
})
public final class RedirectPolicy {

  // PMD.AvoidDuplicateLiterals: this exact suppression string is applied on four separate
  // methods below (the private constructor, define, withUrls, reconstitute) — a real constant
  // reference, not four independently-drifting copies of the same string.
  private static final String SUPPRESS_S107 = "java:S107";

  private final UUID id;
  private final UUID oauthClientId;
  private final String fallbackSignInRedirectUrl;
  private final String fallbackSignUpRedirectUrl;
  private final String forceSignInRedirectUrl;
  private final String forceSignUpRedirectUrl;
  private final Instant createdAt;
  private final Instant updatedAt;

  // One parameter per persisted column — same rationale as OAuthClient's own identical
  // suppression: a synthetic parameter object here would add indirection without removing any
  // real complexity.
  @SuppressWarnings(SUPPRESS_S107)
  private RedirectPolicy(
      final UUID id,
      final UUID oauthClientId,
      final String fallbackSignInRedirectUrl,
      final String fallbackSignUpRedirectUrl,
      final String forceSignInRedirectUrl,
      final String forceSignUpRedirectUrl,
      final Instant createdAt,
      final Instant updatedAt) {
    this.id = Objects.requireNonNull(id, "id must not be null");
    this.oauthClientId = Objects.requireNonNull(oauthClientId, "oauthClientId must not be null");
    this.fallbackSignInRedirectUrl =
        validateIfPresent(fallbackSignInRedirectUrl, "fallbackSignInRedirectUrl");
    this.fallbackSignUpRedirectUrl =
        validateIfPresent(fallbackSignUpRedirectUrl, "fallbackSignUpRedirectUrl");
    this.forceSignInRedirectUrl =
        validateIfPresent(forceSignInRedirectUrl, "forceSignInRedirectUrl");
    this.forceSignUpRedirectUrl =
        validateIfPresent(forceSignUpRedirectUrl, "forceSignUpRedirectUrl");
    this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
    this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt must not be null");
  }

  /**
   * The implicit answer for an {@code OAuthClient} that has never had this policy configured —
   * every URL absent, same "read-side default, never an error" convention {@code
   * AccountAuthenticationPolicy#defaults} already establishes in organization-module. Never
   * persisted on its own; {@link #define} is what a real write goes through.
   */
  public static RedirectPolicy unconfigured(final UUID oauthClientId) {
    return define(oauthClientId, null, null, null, null);
  }

  /** A brand-new policy for an {@code OAuthClient} that has never had one set before. */
  @SuppressWarnings(SUPPRESS_S107)
  public static RedirectPolicy define(
      final UUID oauthClientId,
      final String fallbackSignInRedirectUrl,
      final String fallbackSignUpRedirectUrl,
      final String forceSignInRedirectUrl,
      final String forceSignUpRedirectUrl) {
    final Instant now = Instant.now();
    return new RedirectPolicy(
        UUID.randomUUID(),
        oauthClientId,
        fallbackSignInRedirectUrl,
        fallbackSignUpRedirectUrl,
        forceSignInRedirectUrl,
        forceSignUpRedirectUrl,
        now,
        now);
  }

  /**
   * A real row already exists for this {@code OAuthClient} — replaces every configured URL, keeping
   * the original {@code id}/{@code createdAt} (same "update in place, never a second row"
   * convention as {@link RateLimitPolicy#withRequestsPerMinute}) and stamping a fresh {@code
   * updatedAt}.
   */
  @SuppressWarnings(SUPPRESS_S107)
  public RedirectPolicy withUrls(
      final String fallbackSignInRedirectUrl,
      final String fallbackSignUpRedirectUrl,
      final String forceSignInRedirectUrl,
      final String forceSignUpRedirectUrl) {
    return new RedirectPolicy(
        id,
        oauthClientId,
        fallbackSignInRedirectUrl,
        fallbackSignUpRedirectUrl,
        forceSignInRedirectUrl,
        forceSignUpRedirectUrl,
        createdAt,
        Instant.now());
  }

  @SuppressWarnings(SUPPRESS_S107)
  public static RedirectPolicy reconstitute(
      final UUID id,
      final UUID oauthClientId,
      final String fallbackSignInRedirectUrl,
      final String fallbackSignUpRedirectUrl,
      final String forceSignInRedirectUrl,
      final String forceSignUpRedirectUrl,
      final Instant createdAt,
      final Instant updatedAt) {
    return new RedirectPolicy(
        id,
        oauthClientId,
        fallbackSignInRedirectUrl,
        fallbackSignUpRedirectUrl,
        forceSignInRedirectUrl,
        forceSignUpRedirectUrl,
        createdAt,
        updatedAt);
  }

  // Two exits (null passes through unchecked, a real value is validated) is clearer here than
  // forcing a single-return shape onto "absent" vs. "present" — same rationale OAuthClient's own
  // OnlyOneReturn suppressions document elsewhere in this codebase.
  @SuppressWarnings("PMD.OnlyOneReturn")
  private static String validateIfPresent(final String url, final String fieldName) {
    if (url == null) {
      return null;
    }
    OAuthClient.requireWellFormedAbsoluteSecureUri(url, fieldName);
    return url;
  }

  public UUID id() {
    return id;
  }

  public UUID oauthClientId() {
    return oauthClientId;
  }

  public Optional<String> fallbackSignInRedirectUrl() {
    return Optional.ofNullable(fallbackSignInRedirectUrl);
  }

  public Optional<String> fallbackSignUpRedirectUrl() {
    return Optional.ofNullable(fallbackSignUpRedirectUrl);
  }

  public Optional<String> forceSignInRedirectUrl() {
    return Optional.ofNullable(forceSignInRedirectUrl);
  }

  public Optional<String> forceSignUpRedirectUrl() {
    return Optional.ofNullable(forceSignUpRedirectUrl);
  }

  public Instant createdAt() {
    return createdAt;
  }

  public Instant updatedAt() {
    return updatedAt;
  }
}
