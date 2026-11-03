package com.clavaris.organization.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * ADR-0024 (sign-up/sign-in options, Clerk parity): the per-{@code Organization} configuration
 * surface for which identifiers/strategies its own {@code Account} population may use to sign up
 * and sign in — mirrors
 * https://clerk.com/docs/guides/configure/auth-strategies/sign-up-sign-in-options, minus phone
 * number (deliberately deferred, no SMS provider chosen yet — see the ADR's own § "Deferred").
 *
 * <p>Absence of a row for a given Organization means "use these defaults," not "nothing configured"
 * — every default below is chosen to <b>exactly match this codebase's real behaviour before this
 * feature existed</b>, same "opt-in, zero regression" posture {@code
 * Organization.socialLoginEnabled} defaulting {@code false} already establishes for social login.
 * {@code AccountAuthenticationPolicyRepository.findByOrganizationId} returning empty is therefore
 * the normal state for every Organization whose policy has never been tuned, same convention {@link
 * RateLimitPolicy}'s own Javadoc already documents.
 *
 * <p>Email and password sign-up/sign-in are deliberately <b>not</b> fields here — both are
 * permanently available for every Organization and can never be disabled (BR-ID-12; {@code
 * Account.email} is a non-null, constructor-enforced identity field — making it optional would be
 * an {@code Account} identity-model restructuring, not a policy toggle, and is out of scope for
 * this ADR). Modeling a field that rejects one of its two possible values would be dishonest API
 * shape, not Clerk parity — this fixed behaviour is documented in the ADR instead.
 *
 * <p>Same record-style-accessor PMD suppressions as {@link RateLimitPolicy}, same rationale.
 */
// PMD.TooManyMethods: one flat accessor per option, so no caller needs to know the options are
// grouped in AccountAuthenticationSettings (TD-ARCH-042).
@SuppressWarnings("PMD.TooManyMethods")
public final class AccountAuthenticationPolicy {

  private final UUID id;
  private final UUID organizationId;
  private final AccountAuthenticationSettings settings;
  // TD-FUT-052: whether an End-User may change their own email. On by default; an Organization
  // whose consuming application owns the email turns it off.
  private final boolean selfServiceEmailChangeEnabled;
  // ADR-0035: whether a password sign-in must also present an emailed code, every time. Off by
  // default, so an Organization never tuned behaves exactly as before.
  private final boolean codeAfterPasswordRequired;
  private final Instant createdAt;
  private final Instant updatedAt;

  private AccountAuthenticationPolicy(
      final UUID id,
      final UUID organizationId,
      final AccountAuthenticationSettings settings,
      final boolean selfServiceEmailChangeEnabled,
      final boolean codeAfterPasswordRequired,
      final Instant createdAt,
      final Instant updatedAt) {
    this.id = Objects.requireNonNull(id, "id must not be null");
    this.organizationId = Objects.requireNonNull(organizationId, "organizationId must not be null");
    this.settings = Objects.requireNonNull(settings, "settings must not be null");
    this.selfServiceEmailChangeEnabled = selfServiceEmailChangeEnabled;
    this.codeAfterPasswordRequired = codeAfterPasswordRequired;
    this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
    this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt must not be null");
  }

  /** The fixed defaults every Organization implicitly has until an operator tunes them. */
  public static AccountAuthenticationPolicy defaults(final UUID organizationId) {
    return define(organizationId, AccountAuthenticationSettings.defaults());
  }

  /** A brand-new policy row for an Organization that has never had one set before. */
  public static AccountAuthenticationPolicy define(
      final UUID organizationId, final AccountAuthenticationSettings settings) {
    final Instant now = Instant.now();
    return new AccountAuthenticationPolicy(
        UUID.randomUUID(), organizationId, settings, true, false, now, now);
  }

  /**
   * A real row already exists for this Organization — replaces every tunable field, keeping the
   * original {@code id}/{@code createdAt} and stamping a fresh {@code updatedAt}, same "update in
   * place, never a second row" convention as {@link RateLimitPolicy#withRequestsPerMinute}.
   */
  public AccountAuthenticationPolicy withPolicy(final AccountAuthenticationSettings newSettings) {
    return new AccountAuthenticationPolicy(
        id,
        organizationId,
        newSettings,
        selfServiceEmailChangeEnabled,
        codeAfterPasswordRequired,
        createdAt,
        Instant.now());
  }

  /** The same policy with the End-User's own email change switched on or off (TD-FUT-052). */
  public AccountAuthenticationPolicy withSelfServiceEmailChange(final boolean enabled) {
    return new AccountAuthenticationPolicy(
        id, organizationId, settings, enabled, codeAfterPasswordRequired, createdAt, updatedAt);
  }

  /** The same policy with the emailed code after a valid password switched on or off (ADR-0035). */
  public AccountAuthenticationPolicy withCodeAfterPasswordRequired(final boolean required) {
    return new AccountAuthenticationPolicy(
        id,
        organizationId,
        settings,
        selfServiceEmailChangeEnabled,
        required,
        createdAt,
        updatedAt);
  }

  public static AccountAuthenticationPolicy reconstitute(
      final UUID id,
      final UUID organizationId,
      final AccountAuthenticationSettings settings,
      final boolean selfServiceEmailChangeEnabled,
      final boolean codeAfterPasswordRequired,
      final Instant createdAt,
      final Instant updatedAt) {
    return new AccountAuthenticationPolicy(
        id,
        organizationId,
        settings,
        selfServiceEmailChangeEnabled,
        codeAfterPasswordRequired,
        createdAt,
        updatedAt);
  }

  /** The ten options tuned together, as one value. */
  public AccountAuthenticationSettings settings() {
    return settings;
  }

  public UUID id() {
    return id;
  }

  public UUID organizationId() {
    return organizationId;
  }

  public boolean emailVerificationRequiredAtSignIn() {
    return settings.emailVerificationRequiredAtSignIn();
  }

  public EmailVerificationMethod emailVerificationMethod() {
    return settings.emailVerificationMethod();
  }

  public boolean emailCodeSignInEnabled() {
    return settings.emailCodeSignInEnabled();
  }

  public boolean emailLinkSignInEnabled() {
    return settings.emailLinkSignInEnabled();
  }

  public boolean usernameRequired() {
    return settings.usernameRequired();
  }

  public boolean usernameSignInEnabled() {
    return settings.usernameSignInEnabled();
  }

  public boolean passwordAtSignUpEnabled() {
    return settings.passwordAtSignUpEnabled();
  }

  public boolean deviceTrustEnabled() {
    return settings.deviceTrustEnabled();
  }

  public boolean selfRegistrationRequiresApproval() {
    return settings.selfRegistrationRequiresApproval();
  }

  public boolean selfServiceEmailChangeEnabled() {
    return selfServiceEmailChangeEnabled;
  }

  public boolean codeAfterPasswordRequired() {
    return codeAfterPasswordRequired;
  }

  public Instant createdAt() {
    return createdAt;
  }

  public Instant updatedAt() {
    return updatedAt;
  }
}
