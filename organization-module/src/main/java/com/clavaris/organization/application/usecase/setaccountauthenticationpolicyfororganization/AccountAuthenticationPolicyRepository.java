package com.clavaris.organization.application.usecase.setaccountauthenticationpolicyfororganization;

import com.clavaris.organization.domain.model.AccountAuthenticationPolicy;
import java.util.Optional;
import java.util.UUID;

/**
 * Outbound port — implemented by {@code
 * infrastructure/adapter/out/persistence/JpaAccountAuthenticationPolicyRepository}. {@code
 * findByOrganizationId} returning empty is the normal state for any Organization whose policy has
 * never been tuned — see {@link AccountAuthenticationPolicy}'s own Javadoc.
 */
public interface AccountAuthenticationPolicyRepository {

  Optional<AccountAuthenticationPolicy> findByOrganizationId(UUID organizationId);

  void save(AccountAuthenticationPolicy policy);
}
