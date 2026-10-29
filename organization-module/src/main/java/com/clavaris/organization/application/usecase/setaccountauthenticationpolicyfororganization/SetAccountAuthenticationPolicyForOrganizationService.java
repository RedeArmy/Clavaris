package com.clavaris.organization.application.usecase.setaccountauthenticationpolicyfororganization;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.organization.application.usecase.createorganization.OrganizationRepository;
import com.clavaris.organization.domain.model.AccountAuthenticationPolicy;
import com.clavaris.organization.domain.model.AccountAuthenticationSettings;
import org.springframework.transaction.annotation.Transactional;

/**
 * ADR-0024: v1 is operator-managed only — reached exclusively via the platform-tier management API
 * ({@code AdminApiSecurityConfig}, {@code PlatformScopes.ACCOUNT_AUTHENTICATION_POLICY_WRITE}),
 * same separation of concerns as {@code SetSocialLoginPolicyForOrganizationService}/{@code
 * SetRateLimitPolicyForOrganizationService}. Also writes an {@code
 * account_authentication_policy.set} audit event in the same transaction — same TD-SEC-007 pattern
 * every other admin-API mutation on this surface already uses.
 */
public class SetAccountAuthenticationPolicyForOrganizationService
    implements SetAccountAuthenticationPolicyForOrganizationUseCase {

  private final OrganizationRepository organizations;
  private final AccountAuthenticationPolicyRepository policies;
  private final AuditEventRecorder auditEvents;

  public SetAccountAuthenticationPolicyForOrganizationService(
      final OrganizationRepository organizations,
      final AccountAuthenticationPolicyRepository policies,
      final AuditEventRecorder auditEvents) {
    this.organizations = organizations;
    this.policies = policies;
    this.auditEvents = auditEvents;
  }

  @Override
  @Transactional
  public SetAccountAuthenticationPolicyForOrganizationResult handle(
      final SetAccountAuthenticationPolicyForOrganizationCommand command) {
    if (!command.passwordAtSignUpEnabled()
        && !command.emailCodeSignInEnabled()
        && !command.emailLinkSignInEnabled()) {
      throw new PasswordOptionalRequiresPasswordlessSignInException();
    }
    // Same "never trust a caller-supplied id, even from a trusted operator token" discipline as
    // SetRateLimitPolicyForOrganizationService's own identical check.
    if (!organizations.existsById(command.organizationId())) {
      throw new OrganizationNotFoundException(command.organizationId());
    }

    final AccountAuthenticationPolicy policy =
        policies
            .findByOrganizationId(command.organizationId())
            .map(existing -> applyCommand(existing, command))
            .orElseGet(() -> defineFromCommand(command));

    policies.save(policy);

    auditEvents.write(
        command.actor(),
        "account_authentication_policy.set",
        "Organization",
        command.organizationId().toString(),
        "emailVerificationMethod="
            + command.emailVerificationMethod()
            + " passwordAtSignUpEnabled="
            + command.passwordAtSignUpEnabled()
            + " deviceTrustEnabled="
            + command.deviceTrustEnabled()
            + " selfRegistrationRequiresApproval="
            + command.selfRegistrationRequiresApproval()
            + " selfServiceEmailChangeEnabled="
            + command.selfServiceEmailChangeEnabled());

    return new SetAccountAuthenticationPolicyForOrganizationResult(policy);
  }

  private AccountAuthenticationPolicy applyCommand(
      final AccountAuthenticationPolicy existing,
      final SetAccountAuthenticationPolicyForOrganizationCommand command) {
    return existing
        .withPolicy(settingsFrom(command))
        .withSelfServiceEmailChange(command.selfServiceEmailChangeEnabled());
  }

  private AccountAuthenticationPolicy defineFromCommand(
      final SetAccountAuthenticationPolicyForOrganizationCommand command) {
    return AccountAuthenticationPolicy.define(command.organizationId(), settingsFrom(command))
        .withSelfServiceEmailChange(command.selfServiceEmailChangeEnabled());
  }

  private static AccountAuthenticationSettings settingsFrom(
      final SetAccountAuthenticationPolicyForOrganizationCommand command) {
    return new AccountAuthenticationSettings(
        command.emailVerificationRequiredAtSignIn(),
        command.emailVerificationMethod(),
        command.emailCodeSignInEnabled(),
        command.emailLinkSignInEnabled(),
        command.usernameRequired(),
        command.usernameSignInEnabled(),
        command.passwordAtSignUpEnabled(),
        command.deviceTrustEnabled(),
        command.selfRegistrationRequiresApproval());
  }
}
