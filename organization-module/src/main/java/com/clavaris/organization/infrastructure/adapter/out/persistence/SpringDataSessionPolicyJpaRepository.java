package com.clavaris.organization.infrastructure.adapter.out.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataSessionPolicyJpaRepository extends JpaRepository<SessionPolicyEntity, UUID> {

  Optional<SessionPolicyEntity> findByOrganizationId(UUID organizationId);
}
