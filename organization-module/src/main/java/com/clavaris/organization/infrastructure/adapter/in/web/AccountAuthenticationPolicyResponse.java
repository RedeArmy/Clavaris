package com.clavaris.organization.infrastructure.adapter.in.web;

import com.clavaris.organization.domain.model.AccountAuthenticationPolicy;
import com.clavaris.organization.domain.model.EmailVerificationMethod;
import java.util.UUID;

@SuppressWarnings({"java:S107", "PMD.LongVariable"})
public record AccountAuthenticationPolicyResponse(
    UUID organizationId,
    boolean emailVerificationRequiredAtSignIn,
    EmailVerificationMethod emailVerificationMethod,
    boolean emailCodeSignInEnabled,
    boolean emailLinkSignInEnabled,
    boolean usernameSignUpEnabled,
    boolean usernameRequired,
    boolean usernameSignInEnabled,
    boolean passwordAtSignUpEnabled,
    boolean deviceTrustEnabled) {

  public static AccountAuthenticationPolicyResponse from(final AccountAuthenticationPolicy policy) {
    return new AccountAuthenticationPolicyResponse(
        policy.organizationId(),
        policy.emailVerificationRequiredAtSignIn(),
        policy.emailVerificationMethod(),
        policy.emailCodeSignInEnabled(),
        policy.emailLinkSignInEnabled(),
        policy.usernameSignUpEnabled(),
        policy.usernameRequired(),
        policy.usernameSignInEnabled(),
        policy.passwordAtSignUpEnabled(),
        policy.deviceTrustEnabled());
  }
}
