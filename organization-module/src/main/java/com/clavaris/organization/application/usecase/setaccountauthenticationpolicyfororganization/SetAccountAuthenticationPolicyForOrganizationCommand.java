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
@SuppressWarnings({"java:S107", "PMD.LongVariable"})
public record SetAccountAuthenticationPolicyForOrganizationCommand(
    UUID organizationId,
    boolean emailVerificationRequiredAtSignIn,
    EmailVerificationMethod emailVerificationMethod,
    boolean emailCodeSignInEnabled,
    boolean emailLinkSignInEnabled,
    boolean usernameSignUpEnabled,
    boolean usernameRequired,
    boolean usernameSignInEnabled,
    boolean passwordAtSignUpEnabled,
    boolean deviceTrustEnabled,
    AuditActor actor) {}
