package com.clavaris.app.infrastructure.config;

import com.clavaris.identity.application.usecase.requestemailverification.AccountAuthenticationPolicyProvider;
import com.clavaris.identity.application.usecase.requestemailverification.AccountAuthenticationPolicySnapshot;
import com.clavaris.identity.application.usecase.requestemailverification.EmailVerificationMethod;
import com.clavaris.organization.application.usecase.getaccountauthenticationpolicyfororganization.GetAccountAuthenticationPolicyForOrganizationUseCase;
import com.clavaris.organization.domain.model.AccountAuthenticationPolicy;
import org.springframework.stereotype.Component;

/**
 * ADR-0024: adapts organization-module's {@code
 * GetAccountAuthenticationPolicyForOrganizationUseCase} to identity-module's own {@link
 * AccountAuthenticationPolicyProvider} port — same module-independence-crossing-bridge pattern
 * {@code OrganizationSocialLoginPolicyProviderBridge} already establishes for an identical need.
 * The organization-module use case already supplies defaults for an unconfigured Organization, so
 * this bridge never needs to express absence either.
 */
@SuppressWarnings("PMD.LongVariable")
@Component
class AccountAuthenticationPolicyProviderBridge implements AccountAuthenticationPolicyProvider {

  private final GetAccountAuthenticationPolicyForOrganizationUseCase useCase;

  /* package */ AccountAuthenticationPolicyProviderBridge(
      final GetAccountAuthenticationPolicyForOrganizationUseCase useCase) {
    this.useCase = useCase;
  }

  @Override
  public AccountAuthenticationPolicySnapshot policyFor(
      final com.clavaris.identity.domain.model.OrganizationId organizationId) {
    final AccountAuthenticationPolicy policy = useCase.handle(organizationId.value());
    return new AccountAuthenticationPolicySnapshot(
        policy.emailVerificationRequiredAtSignIn(),
        toIdentityModuleEnum(policy.emailVerificationMethod()),
        policy.emailCodeSignInEnabled(),
        policy.emailLinkSignInEnabled(),
        policy.usernameSignUpEnabled(),
        policy.usernameRequired(),
        policy.usernameSignInEnabled(),
        policy.passwordAtSignUpEnabled(),
        policy.deviceTrustEnabled());
  }

  // Module independence: organization-module's own EmailVerificationMethod and identity-module's
  // own mirror are deliberately separate types (same "mirror, never share" rule this codebase
  // applies to every other cross-module enum) — this bridge is the one place that translates
  // between them.
  private EmailVerificationMethod toIdentityModuleEnum(
      final com.clavaris.organization.domain.model.EmailVerificationMethod
          organizationModuleValue) {
    return EmailVerificationMethod.valueOf(organizationModuleValue.name());
  }
}
