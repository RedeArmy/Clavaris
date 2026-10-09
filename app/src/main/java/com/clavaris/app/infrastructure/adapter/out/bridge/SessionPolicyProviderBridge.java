package com.clavaris.app.infrastructure.adapter.out.bridge;

import com.clavaris.identity.application.usecase.issuerefreshtoken.SessionPolicyProvider;
import com.clavaris.identity.application.usecase.issuerefreshtoken.SessionPolicySnapshot;
import com.clavaris.organization.application.usecase.getsessionpolicyfororganization.GetSessionPolicyForOrganizationUseCase;
import com.clavaris.organization.domain.model.SessionPolicy;
import org.springframework.stereotype.Component;

/**
 * Adapts organization-module's {@code GetSessionPolicyForOrganizationUseCase} to identity-module's
 * own {@link SessionPolicyProvider} port — same module-independence-crossing-bridge pattern {@code
 * AccountAuthenticationPolicyProviderBridge} already establishes for an identical need. No enum
 * translation needed here (unlike that bridge) — every field is a plain {@code int}/{@code
 * boolean}. The organization-module use case already supplies defaults for an unconfigured
 * Organization, so this bridge never needs to express absence either.
 */
@Component
public class SessionPolicyProviderBridge implements SessionPolicyProvider {

  private final GetSessionPolicyForOrganizationUseCase useCase;

  public SessionPolicyProviderBridge(final GetSessionPolicyForOrganizationUseCase useCase) {
    this.useCase = useCase;
  }

  @Override
  public SessionPolicySnapshot policyFor(
      final com.clavaris.identity.domain.model.OrganizationId organizationId) {
    final SessionPolicy policy = useCase.handle(organizationId.value());
    return new SessionPolicySnapshot(
        policy.maximumLifetimeMinutes(),
        policy.inactivityTimeoutMinutes(),
        policy.reverificationWindowMinutes(),
        policy.multiSessionHandlingEnabled());
  }
}
