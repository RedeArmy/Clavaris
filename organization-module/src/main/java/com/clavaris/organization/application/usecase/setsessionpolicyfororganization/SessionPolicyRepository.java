package com.clavaris.organization.application.usecase.setsessionpolicyfororganization;

import com.clavaris.organization.domain.model.SessionPolicy;
import java.util.Optional;
import java.util.UUID;

/**
 * Outbound port — implemented by {@code
 * infrastructure/adapter/out/persistence/JpaSessionPolicyRepository}. {@code findByOrganizationId}
 * returning empty is the normal state for any Organization whose session policy has never been
 * tuned — see {@link SessionPolicy}'s own Javadoc.
 */
public interface SessionPolicyRepository {

  Optional<SessionPolicy> findByOrganizationId(UUID organizationId);

  void save(SessionPolicy policy);
}
