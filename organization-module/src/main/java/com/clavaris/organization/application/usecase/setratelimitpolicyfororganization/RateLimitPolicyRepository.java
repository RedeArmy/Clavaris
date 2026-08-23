package com.clavaris.organization.application.usecase.setratelimitpolicyfororganization;

import com.clavaris.organization.domain.model.RateLimitPolicy;
import java.util.Optional;
import java.util.UUID;

/**
 * Outbound port — implemented by {@code
 * infrastructure/adapter/out/persistence/JpaRateLimitPolicyRepository}. {@code
 * findByOrganizationId} returning empty is the normal state for any Organization whose ceiling has
 * never been tuned — see {@link RateLimitPolicy}'s own Javadoc.
 */
public interface RateLimitPolicyRepository {

  Optional<RateLimitPolicy> findByOrganizationId(UUID organizationId);

  void save(RateLimitPolicy policy);
}
