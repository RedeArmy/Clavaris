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
  // TD-ARCH-046: self-service email change (TD-FUT-052), the emailed code after a password
  // (ADR-0035), the authenticator app (ADR-0040), passkeys and their offer (ADR-0041), kept
  // together
  // so no constructor takes them as a row of booleans.
  private final AccountSecuritySettings security;
  private final Instant createdAt;
  private final Instant updatedAt;

  private AccountAuthenticationPolicy(
      final UUID id,
      final UUID organizationId,
      final AccountAuthenticationSettings settings,
      final AccountSecuritySettings security,
      final Instant createdAt,
      final Instant updatedAt) {
    this.id = Objects.requireNonNull(id, "id must not be null");
    this.organizationId = Objects.requireNonNull(organizationId, "organizationId must not be null");
    this.settings = Objects.requireNonNull(settings, "settings must not be null");
    this.security = Objects.requireNonNull(security, "security must not be null");
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
        UUID.randomUUID(), organizationId, settings, AccountSecuritySettings.defaults(), now, now);
  }

  /**
   * A real row already exists for this Organization — replaces every tunable field, keeping the
   * original {@code id}/{@code createdAt} and stamping a fresh {@code updatedAt}, same "update in
   * place, never a second row" convention as {@link RateLimitPolicy#withRequestsPerMinute}.
   */
  public AccountAuthenticationPolicy withPolicy(final AccountAuthenticationSettings newSettings) {
    return new AccountAuthenticationPolicy(
        id, organizationId, newSettings, security, createdAt, Instant.now());
  }

  /** The same policy with the End-User's own email change switched on or off (TD-FUT-052). */
  public AccountAuthenticationPolicy withSelfServiceEmailChange(final boolean enabled) {
    return withSecurity(security.withSelfServiceEmailChange(enabled));
  }

  /** The same policy with the emailed code after a valid password switched on or off (ADR-0035). */
  public AccountAuthenticationPolicy withCodeAfterPasswordRequired(final boolean required) {
    return withSecurity(security.withCodeAfterPasswordRequired(required));
  }

  /** ADR-0040: whether accounts of this Organization may enrol an authenticator app. */
  public AccountAuthenticationPolicy withTotpEnabled(final boolean enabled) {
    return withSecurity(security.withTotpEnabled(enabled));
  }

  /** ADR-0041: whether accounts of this Organization may use passkeys at all. */
  public AccountAuthenticationPolicy withPasskeysEnabled(final boolean enabled) {
    return withSecurity(security.withPasskeysEnabled(enabled));
  }

  /** ADR-0041: whether a sign-in by emailed code or link is followed by the passkey offer. */
  public AccountAuthenticationPolicy withPasskeyOfferEnabled(final boolean enabled) {
    return withSecurity(security.withPasskeyOfferEnabled(enabled));
  }

  // These switches do not touch updatedAt, as before: only withPolicy stamps a new one.
  private AccountAuthenticationPolicy withSecurity(final AccountSecuritySettings changed) {
    return new AccountAuthenticationPolicy(
        id, organizationId, settings, changed, createdAt, updatedAt);
  }

  /** A row read back from the database, with its real {@code id} and timestamps. */
  public static AccountAuthenticationPolicy reconstitute(
      final UUID id,
      final UUID organizationId,
      final AccountAuthenticationSettings settings,
      final AccountSecuritySettings security,
      final Instant createdAt,
      final Instant updatedAt) {
    return new AccountAuthenticationPolicy(
        id, organizationId, settings, security, createdAt, updatedAt);
  }

  public AccountAuthenticationSettings settings() {
    return settings;
  }

  public AccountSecuritySettings security() {
    return security;
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
    return security.selfServiceEmailChangeEnabled();
  }

  public boolean codeAfterPasswordRequired() {
    return security.codeAfterPasswordRequired();
  }

  public boolean totpEnabled() {
    return security.totpEnabled();
  }

  public boolean passkeysEnabled() {
    return security.passkeysEnabled();
  }

  public boolean passkeyOfferEnabled() {
    return security.passkeyOfferEnabled();
  }

  public Instant createdAt() {
    return createdAt;
  }

  public Instant updatedAt() {
    return updatedAt;
  }
}
