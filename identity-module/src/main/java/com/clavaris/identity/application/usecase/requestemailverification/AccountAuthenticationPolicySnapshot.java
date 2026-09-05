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
    boolean usernameSignUpEnabled,
    boolean usernameRequired,
    boolean usernameSignInEnabled,
    boolean passwordAtSignUpEnabled,
    boolean deviceTrustEnabled) {

  /** The same fixed defaults {@code AccountAuthenticationPolicy.defaults()} establishes. */
  public static AccountAuthenticationPolicySnapshot defaults() {
    return new AccountAuthenticationPolicySnapshot(
        false, EmailVerificationMethod.LINK, false, false, false, false, false, true, false);
  }
}
