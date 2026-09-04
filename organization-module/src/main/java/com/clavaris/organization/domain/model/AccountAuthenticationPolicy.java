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
// PMD.LongVariable: every flagged field/parameter name here spells out exactly which of the
// nine tunable strategies it governs (emailVerificationRequiredAtSignIn, usernameSignInEnabled,
// passwordAtSignUpEnabled, ...) — same "descriptive over abbreviated" convention every sibling
// policy aggregate in this module already establishes. PMD.ExcessiveParameterList: one parameter
// per persisted column across define/reconstitute/withPolicy, same "wiring, not sprawl" reasoning
// RateLimitPolicy's own constructor already documents. PMD.AvoidDuplicateLiterals: "must not be
// null" is reused across every Objects.requireNonNull guard in the private constructor, same
// false positive every other multi-field domain aggregate's constructor already triggers.
@SuppressWarnings({
  "PMD.AvoidFieldNameMatchingMethodName",
  "PMD.ShortVariable",
  "PMD.ShortMethodName",
  "PMD.TooManyMethods",
  "PMD.DataClass",
  "PMD.LongVariable",
  "PMD.ExcessiveParameterList",
  "PMD.AvoidDuplicateLiterals"
})
public final class AccountAuthenticationPolicy {

  private final UUID id;
  private final UUID organizationId;
  private final boolean emailVerificationRequiredAtSignIn;
  private final EmailVerificationMethod emailVerificationMethod;
  private final boolean emailCodeSignInEnabled;
  private final boolean emailLinkSignInEnabled;
  private final boolean usernameSignUpEnabled;
  private final boolean usernameRequired;
  private final boolean usernameSignInEnabled;
  private final boolean passwordAtSignUpEnabled;
  private final boolean deviceTrustEnabled;
  private final Instant createdAt;
  private final Instant updatedAt;

  @SuppressWarnings("java:S107") // one parameter per persisted column, same rationale as
  // RateLimitPolicy's own private constructor — a synthetic parameter object here would add
  // indirection without removing any real complexity.
  private AccountAuthenticationPolicy(
      final UUID id,
      final UUID organizationId,
      final boolean emailVerificationRequiredAtSignIn,
      final EmailVerificationMethod emailVerificationMethod,
      final boolean emailCodeSignInEnabled,
      final boolean emailLinkSignInEnabled,
      final boolean usernameSignUpEnabled,
      final boolean usernameRequired,
      final boolean usernameSignInEnabled,
      final boolean passwordAtSignUpEnabled,
      final boolean deviceTrustEnabled,
      final Instant createdAt,
      final Instant updatedAt) {
    this.id = Objects.requireNonNull(id, "id must not be null");
    this.organizationId = Objects.requireNonNull(organizationId, "organizationId must not be null");
    this.emailVerificationRequiredAtSignIn = emailVerificationRequiredAtSignIn;
    this.emailVerificationMethod =
        Objects.requireNonNull(emailVerificationMethod, "emailVerificationMethod must not be null");
    this.emailCodeSignInEnabled = emailCodeSignInEnabled;
    this.emailLinkSignInEnabled = emailLinkSignInEnabled;
    this.usernameSignUpEnabled = usernameSignUpEnabled;
    this.usernameRequired = usernameRequired;
    this.usernameSignInEnabled = usernameSignInEnabled;
    this.passwordAtSignUpEnabled = passwordAtSignUpEnabled;
    this.deviceTrustEnabled = deviceTrustEnabled;
    this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
    this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt must not be null");
  }

  /** The fixed defaults every Organization implicitly has until an operator tunes them. */
  public static AccountAuthenticationPolicy defaults(final UUID organizationId) {
    return define(
        organizationId,
        false,
        EmailVerificationMethod.LINK,
        false,
        false,
        false,
        false,
        false,
        true,
        false);
  }

  /** A brand-new policy row for an Organization that has never had one set before. */
  @SuppressWarnings("java:S107")
  public static AccountAuthenticationPolicy define(
      final UUID organizationId,
      final boolean emailVerificationRequiredAtSignIn,
      final EmailVerificationMethod emailVerificationMethod,
      final boolean emailCodeSignInEnabled,
      final boolean emailLinkSignInEnabled,
      final boolean usernameSignUpEnabled,
      final boolean usernameRequired,
      final boolean usernameSignInEnabled,
      final boolean passwordAtSignUpEnabled,
      final boolean deviceTrustEnabled) {
    final Instant now = Instant.now();
    return new AccountAuthenticationPolicy(
        UUID.randomUUID(),
        organizationId,
        emailVerificationRequiredAtSignIn,
        emailVerificationMethod,
        emailCodeSignInEnabled,
        emailLinkSignInEnabled,
        usernameSignUpEnabled,
        usernameRequired,
        usernameSignInEnabled,
        passwordAtSignUpEnabled,
        deviceTrustEnabled,
        now,
        now);
  }

  /**
   * A real row already exists for this Organization — replaces every tunable field, keeping the
   * original {@code id}/{@code createdAt} and stamping a fresh {@code updatedAt}, same "update in
   * place, never a second row" convention as {@link RateLimitPolicy#withRequestsPerMinute}.
   */
  @SuppressWarnings("java:S107")
  public AccountAuthenticationPolicy withPolicy(
      final boolean newEmailVerificationRequiredAtSignIn,
      final EmailVerificationMethod newEmailVerificationMethod,
      final boolean newEmailCodeSignInEnabled,
      final boolean newEmailLinkSignInEnabled,
      final boolean newUsernameSignUpEnabled,
      final boolean newUsernameRequired,
      final boolean newUsernameSignInEnabled,
      final boolean newPasswordAtSignUpEnabled,
      final boolean newDeviceTrustEnabled) {
    return new AccountAuthenticationPolicy(
        id,
        organizationId,
        newEmailVerificationRequiredAtSignIn,
        newEmailVerificationMethod,
        newEmailCodeSignInEnabled,
        newEmailLinkSignInEnabled,
        newUsernameSignUpEnabled,
        newUsernameRequired,
        newUsernameSignInEnabled,
        newPasswordAtSignUpEnabled,
        newDeviceTrustEnabled,
        createdAt,
        Instant.now());
  }

  @SuppressWarnings("java:S107")
  public static AccountAuthenticationPolicy reconstitute(
      final UUID id,
      final UUID organizationId,
      final boolean emailVerificationRequiredAtSignIn,
      final EmailVerificationMethod emailVerificationMethod,
      final boolean emailCodeSignInEnabled,
      final boolean emailLinkSignInEnabled,
      final boolean usernameSignUpEnabled,
      final boolean usernameRequired,
      final boolean usernameSignInEnabled,
      final boolean passwordAtSignUpEnabled,
      final boolean deviceTrustEnabled,
      final Instant createdAt,
      final Instant updatedAt) {
    return new AccountAuthenticationPolicy(
        id,
        organizationId,
        emailVerificationRequiredAtSignIn,
        emailVerificationMethod,
        emailCodeSignInEnabled,
        emailLinkSignInEnabled,
        usernameSignUpEnabled,
        usernameRequired,
        usernameSignInEnabled,
        passwordAtSignUpEnabled,
        deviceTrustEnabled,
        createdAt,
        updatedAt);
  }

  public UUID id() {
    return id;
  }

  public UUID organizationId() {
    return organizationId;
  }

  public boolean emailVerificationRequiredAtSignIn() {
    return emailVerificationRequiredAtSignIn;
  }

  public EmailVerificationMethod emailVerificationMethod() {
    return emailVerificationMethod;
  }

  public boolean emailCodeSignInEnabled() {
    return emailCodeSignInEnabled;
  }

  public boolean emailLinkSignInEnabled() {
    return emailLinkSignInEnabled;
  }

  public boolean usernameSignUpEnabled() {
    return usernameSignUpEnabled;
  }

  public boolean usernameRequired() {
    return usernameRequired;
  }

  public boolean usernameSignInEnabled() {
    return usernameSignInEnabled;
  }

  public boolean passwordAtSignUpEnabled() {
    return passwordAtSignUpEnabled;
  }

  public boolean deviceTrustEnabled() {
    return deviceTrustEnabled;
  }

  public Instant createdAt() {
    return createdAt;
  }

  public Instant updatedAt() {
    return updatedAt;
  }
}
