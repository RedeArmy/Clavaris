package com.clavaris.organization.infrastructure.adapter.out.persistence;

import com.clavaris.organization.application.usecase.setsessionpolicyfororganization.SessionPolicyRepository;
import com.clavaris.organization.domain.model.SessionPolicy;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/**
 * Implements the outbound port. {@code save} doubles as insert-or-update — same reasoning {@code
 * JpaRateLimitPolicyRepository}'s own identical Javadoc documents ({@code SessionPolicy}'s {@code
 * id} is always a real, already-assigned UUID by the time this is called).
 */
@Repository
class JpaSessionPolicyRepository implements SessionPolicyRepository {

  private final SpringDataSessionPolicyJpaRepository policies;

  /* package */ JpaSessionPolicyRepository(final SpringDataSessionPolicyJpaRepository policies) {
    this.policies = policies;
  }

  @Override
  public Optional<SessionPolicy> findByOrganizationId(final UUID organizationId) {
    return policies.findByOrganizationId(organizationId).map(this::toDomain);
  }

  @Override
  public void save(final SessionPolicy policy) {
    policies.save(
        new SessionPolicyEntity(
            policy.id(),
            policy.organizationId(),
            policy.maximumLifetimeMinutes(),
            policy.inactivityTimeoutMinutes(),
            policy.reverificationWindowMinutes(),
            policy.multiSessionHandlingEnabled(),
            policy.createdAt(),
            policy.updatedAt()));
  }

  private SessionPolicy toDomain(final SessionPolicyEntity entity) {
    return SessionPolicy.reconstitute(
        entity.getId(),
        entity.getOrganizationId(),
        entity.getMaximumLifetimeMinutes(),
        entity.getInactivityTimeoutMinutes(),
        entity.getReverificationWindowMinutes(),
        entity.isMultiSessionHandlingEnabled(),
        entity.getCreatedAt(),
        entity.getUpdatedAt());
  }
}
