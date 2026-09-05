package com.clavaris.organization.application.usecase.getaccountauthenticationpolicyfororganization;

import com.clavaris.organization.domain.model.AccountAuthenticationPolicy;
import java.util.UUID;

@FunctionalInterface
public interface GetAccountAuthenticationPolicyForOrganizationUseCase {

  /**
   * Never empty — returns {@link AccountAuthenticationPolicy#defaults} when no row has ever been
   * saved for this Organization, the one place that default-supplying logic lives (reused by both
   * the admin-API {@code GET} controller and identity-module's own read port bridge, so neither has
   * to duplicate it).
   */
  AccountAuthenticationPolicy handle(UUID organizationId);
}
