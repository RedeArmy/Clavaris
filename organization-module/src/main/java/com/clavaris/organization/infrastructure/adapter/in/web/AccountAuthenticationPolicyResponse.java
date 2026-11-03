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
    boolean usernameRequired,
    boolean usernameSignInEnabled,
    boolean passwordAtSignUpEnabled,
    boolean deviceTrustEnabled,
    boolean selfRegistrationRequiresApproval,
    boolean selfServiceEmailChangeEnabled,
    boolean codeAfterPasswordRequired) {

  public static AccountAuthenticationPolicyResponse from(final AccountAuthenticationPolicy policy) {
    return new AccountAuthenticationPolicyResponse(
        policy.organizationId(),
        policy.emailVerificationRequiredAtSignIn(),
        policy.emailVerificationMethod(),
        policy.emailCodeSignInEnabled(),
        policy.emailLinkSignInEnabled(),
        policy.usernameRequired(),
        policy.usernameSignInEnabled(),
        policy.passwordAtSignUpEnabled(),
        policy.deviceTrustEnabled(),
        policy.selfRegistrationRequiresApproval(),
        policy.selfServiceEmailChangeEnabled(),
        policy.codeAfterPasswordRequired());
  }
}
