package com.clavaris.organization.infrastructure.adapter.out.persistence;

import com.clavaris.organization.application.usecase.setratelimitpolicyfororganization.RateLimitPolicyRepository;
import com.clavaris.organization.domain.model.RateLimitPolicy;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/**
 * Implements the outbound port. {@code save} doubles as insert-or-update: {@code RateLimitPolicy}'s
 * {@code id} is always a real, already-assigned UUID by the time this is called (never null, no
 * {@code @GeneratedValue} on the entity) — Spring Data JPA's default {@code isNew} check therefore
 * routes every call through {@code EntityManager.merge()}, which inserts a fresh row or updates the
 * existing one by primary key correctly either way, with no extra branching needed here.
 */
@Repository
class JpaRateLimitPolicyRepository implements RateLimitPolicyRepository {

  private final SpringDataRateLimitPolicyJpaRepository policies;

  /* package */ JpaRateLimitPolicyRepository(
      final SpringDataRateLimitPolicyJpaRepository policies) {
    this.policies = policies;
  }

  @Override
  public Optional<RateLimitPolicy> findByOrganizationId(final UUID organizationId) {
    return policies.findByOrganizationId(organizationId).map(this::toDomain);
  }

  @Override
  public void save(final RateLimitPolicy policy) {
    policies.save(
        new RateLimitPolicyEntity(
            policy.id(),
            policy.organizationId(),
            policy.requestsPerMinute(),
            policy.createdAt(),
            policy.updatedAt()));
  }

  private RateLimitPolicy toDomain(final RateLimitPolicyEntity entity) {
    return RateLimitPolicy.reconstitute(
        entity.getId(),
        entity.getOrganizationId(),
        entity.getRequestsPerMinute(),
        entity.getCreatedAt(),
        entity.getUpdatedAt());
  }
}
