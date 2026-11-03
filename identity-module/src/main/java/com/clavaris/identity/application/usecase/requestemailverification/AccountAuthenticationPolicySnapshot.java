package com.clavaris.identity.application.usecase.requestemailverification;

/**
 * ADR-0024: identity-module's own read-only view of organization-module's {@code
 * AccountAuthenticationPolicy} — module independence, same "mirror the shape, never the type" rule
 * this module's own {@code OrganizationSocialLoginPolicyProvider} already establishes for an
 * identical cross-module need. Every field mirrors the domain aggregate's own accessor one-to-one;
 * see that class's own Javadoc for what each one governs and its default.
 */
@SuppressWarnings({"java:S107", "PMD.LongVariable"})
public record AccountAuthenticationPolicySnapshot(
    boolean emailVerificationRequiredAtSignIn,
    EmailVerificationMethod emailVerificationMethod,
    boolean emailCodeSignInEnabled,
    boolean emailLinkSignInEnabled,
    boolean usernameRequired,
    boolean usernameSignInEnabled,
    boolean passwordAtSignUpEnabled,
    boolean deviceTrustEnabled,
    boolean selfRegistrationRequiresApproval,
    boolean codeAfterPasswordRequired) {

  /** Without ADR-0035's setting: no code after the password, as before it existed. */
  public AccountAuthenticationPolicySnapshot(
      final boolean emailVerificationRequiredAtSignIn,
      final EmailVerificationMethod emailVerificationMethod,
      final boolean emailCodeSignInEnabled,
      final boolean emailLinkSignInEnabled,
      final boolean usernameRequired,
      final boolean usernameSignInEnabled,
      final boolean passwordAtSignUpEnabled,
      final boolean deviceTrustEnabled,
      final boolean selfRegistrationRequiresApproval) {
    this(
        emailVerificationRequiredAtSignIn,
        emailVerificationMethod,
        emailCodeSignInEnabled,
        emailLinkSignInEnabled,
        usernameRequired,
        usernameSignInEnabled,
        passwordAtSignUpEnabled,
        deviceTrustEnabled,
        selfRegistrationRequiresApproval,
        false);
  }

  /** The same fixed defaults {@code AccountAuthenticationPolicy.defaults()} establishes. */
  public static AccountAuthenticationPolicySnapshot defaults() {
    return new AccountAuthenticationPolicySnapshot(
        false, EmailVerificationMethod.LINK, false, false, false, false, true, false, false, false);
  }
}
