package com.clavaris.organization.application.usecase.setsocialloginpolicyfororganization;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.organization.application.usecase.createorganization.OrganizationRepository;
import com.clavaris.organization.domain.model.Organization;
import java.util.List;
import java.util.Set;
import org.springframework.transaction.annotation.Transactional;

/**
 * ADR-0020 Decision 3, BR-ID-12: v1 is operator-managed only — reached exclusively via the
 * platform-tier management API ({@code AdminApiSecurityConfig}, {@code
 * PlatformScopes.SOCIAL_LOGIN_POLICY_WRITE}), same separation of concerns as {@code
 * SetRateLimitPolicyForOrganizationService}. Also writes a {@code social_login_policy.set} audit
 * event in the same transaction — same TD-SEC-007 pattern every other admin-API mutation already
 * uses.
 */
public class SetSocialLoginPolicyForOrganizationService
    implements SetSocialLoginPolicyForOrganizationUseCase {

  // ADR-0020 Decision 5: this module's own plain-string mirror of identity-module's real
  // SocialProvider enum values (this module cannot depend on that type — see this use case's own
  // Command Javadoc). TD-FUT-022 (Microsoft) adds a value here, not a redesign, the day it ships.
  private static final Set<String> KNOWN_PROVIDERS = Set.of("GOOGLE", "GITHUB");

  private final OrganizationRepository organizations;
  private final AuditEventRecorder auditEvents;

  public SetSocialLoginPolicyForOrganizationService(
      final OrganizationRepository organizations, final AuditEventRecorder auditEvents) {
    this.organizations = organizations;
    this.auditEvents = auditEvents;
  }

  @Override
  @Transactional
  public SetSocialLoginPolicyForOrganizationResult handle(
      final SetSocialLoginPolicyForOrganizationCommand command) {
    for (final String provider : command.providers()) {
      if (!KNOWN_PROVIDERS.contains(provider)) {
        throw new UnknownSocialProviderException(provider);
      }
    }

    final Organization organization =
        organizations
            .findById(command.organizationId())
            .orElseThrow(() -> new OrganizationNotFoundException(command.organizationId()));

    final Organization updated =
        organization.withSocialLoginPolicy(command.enabled(), List.copyOf(command.providers()));
    organizations.save(updated);

    auditEvents.write(
        command.actor(),
        "social_login_policy.set",
        "Organization",
        command.organizationId().toString(),
        "enabled=" + command.enabled() + " providers=" + command.providers());

    return new SetSocialLoginPolicyForOrganizationResult(updated);
  }
}
