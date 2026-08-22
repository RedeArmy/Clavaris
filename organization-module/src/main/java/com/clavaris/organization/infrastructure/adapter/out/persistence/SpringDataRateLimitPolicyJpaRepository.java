package com.clavaris.organization.infrastructure.adapter.out.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataRateLimitPolicyJpaRepository
    extends JpaRepository<RateLimitPolicyEntity, UUID> {

  Optional<RateLimitPolicyEntity> findByOrganizationId(UUID organizationId);
}
