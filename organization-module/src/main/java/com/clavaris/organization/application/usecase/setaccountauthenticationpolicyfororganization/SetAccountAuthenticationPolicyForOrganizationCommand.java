package com.clavaris.organization.application.usecase.setaccountauthenticationpolicyfororganization;

import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.organization.domain.model.EmailVerificationMethod;
import java.util.UUID;

/**
 * ADR-0024: v1 is operator-managed only — this command is only ever reachable via the platform-tier
 * management API (see {@code SetAccountAuthenticationPolicyController}'s own Javadoc), never a
 * tenant's own token, same posture {@code SetSocialLoginPolicyForOrganizationCommand} already
 * establishes for its own sibling policy.
 */
@SuppressWarnings("PMD.ExcessiveParameterList")
public record SetAccountAuthenticationPolicyForOrganizationCommand(
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
    boolean codeAfterPasswordRequired,
    boolean totpEnabled,
    boolean passkeysEnabled,
    boolean passkeyOfferEnabled,
    AuditActor actor) {

  /** Without the ADR-0041 settings: passkeys and their offer stay on, as they are today. */
  public SetAccountAuthenticationPolicyForOrganizationCommand(
      final UUID organizationId,
      final boolean emailVerificationRequiredAtSignIn,
      final EmailVerificationMethod emailVerificationMethod,
      final boolean emailCodeSignInEnabled,
      final boolean emailLinkSignInEnabled,
      final boolean usernameRequired,
      final boolean usernameSignInEnabled,
      final boolean passwordAtSignUpEnabled,
      final boolean deviceTrustEnabled,
      final boolean selfRegistrationRequiresApproval,
      final boolean selfServiceEmailChangeEnabled,
      final boolean codeAfterPasswordRequired,
      final boolean totpEnabled,
      final AuditActor actor) {
    this(
        organizationId,
        emailVerificationRequiredAtSignIn,
        emailVerificationMethod,
        emailCodeSignInEnabled,
        emailLinkSignInEnabled,
        usernameRequired,
        usernameSignInEnabled,
        passwordAtSignUpEnabled,
        deviceTrustEnabled,
        selfRegistrationRequiresApproval,
        selfServiceEmailChangeEnabled,
        codeAfterPasswordRequired,
        totpEnabled,
        true,
        true,
        actor);
  }

  /** Without the ADR-0040 setting: the authenticator app stays off. */
  public SetAccountAuthenticationPolicyForOrganizationCommand(
      final UUID organizationId,
      final boolean emailVerificationRequiredAtSignIn,
      final EmailVerificationMethod emailVerificationMethod,
      final boolean emailCodeSignInEnabled,
      final boolean emailLinkSignInEnabled,
      final boolean usernameRequired,
      final boolean usernameSignInEnabled,
      final boolean passwordAtSignUpEnabled,
      final boolean deviceTrustEnabled,
      final boolean selfRegistrationRequiresApproval,
      final boolean selfServiceEmailChangeEnabled,
      final boolean codeAfterPasswordRequired,
      final AuditActor actor) {
    this(
        organizationId,
        emailVerificationRequiredAtSignIn,
        emailVerificationMethod,
        emailCodeSignInEnabled,
        emailLinkSignInEnabled,
        usernameRequired,
        usernameSignInEnabled,
        passwordAtSignUpEnabled,
        deviceTrustEnabled,
        selfRegistrationRequiresApproval,
        selfServiceEmailChangeEnabled,
        codeAfterPasswordRequired,
        false,
        true,
        true,
        actor);
  }

  /** Without the ADR-0035 setting: the emailed code after a password stays off. */
  public SetAccountAuthenticationPolicyForOrganizationCommand(
      final UUID organizationId,
      final boolean emailVerificationRequiredAtSignIn,
      final EmailVerificationMethod emailVerificationMethod,
      final boolean emailCodeSignInEnabled,
      final boolean emailLinkSignInEnabled,
      final boolean usernameRequired,
      final boolean usernameSignInEnabled,
      final boolean passwordAtSignUpEnabled,
      final boolean deviceTrustEnabled,
      final boolean selfRegistrationRequiresApproval,
      final boolean selfServiceEmailChangeEnabled,
      final AuditActor actor) {
    this(
        organizationId,
        emailVerificationRequiredAtSignIn,
        emailVerificationMethod,
        emailCodeSignInEnabled,
        emailLinkSignInEnabled,
        usernameRequired,
        usernameSignInEnabled,
        passwordAtSignUpEnabled,
        deviceTrustEnabled,
        selfRegistrationRequiresApproval,
        selfServiceEmailChangeEnabled,
        false,
        false,
        true,
        true,
        actor);
  }

  /** Without the TD-FUT-052 setting: it stays on, as it is for an Organization never tuned. */
  public SetAccountAuthenticationPolicyForOrganizationCommand(
      final UUID organizationId,
      final boolean emailVerificationRequiredAtSignIn,
      final EmailVerificationMethod emailVerificationMethod,
      final boolean emailCodeSignInEnabled,
      final boolean emailLinkSignInEnabled,
      final boolean usernameRequired,
      final boolean usernameSignInEnabled,
      final boolean passwordAtSignUpEnabled,
      final boolean deviceTrustEnabled,
      final boolean selfRegistrationRequiresApproval,
      final AuditActor actor) {
    this(
        organizationId,
        emailVerificationRequiredAtSignIn,
        emailVerificationMethod,
        emailCodeSignInEnabled,
        emailLinkSignInEnabled,
        usernameRequired,
        usernameSignInEnabled,
        passwordAtSignUpEnabled,
        deviceTrustEnabled,
        selfRegistrationRequiresApproval,
        true,
        false,
        false,
        true,
        true,
        actor);
  }
}
