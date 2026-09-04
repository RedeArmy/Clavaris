package com.clavaris.organization.infrastructure.adapter.out.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataAccountAuthenticationPolicyJpaRepository
    extends JpaRepository<AccountAuthenticationPolicyEntity, UUID> {

  Optional<AccountAuthenticationPolicyEntity> findByOrganizationId(UUID organizationId);
}
