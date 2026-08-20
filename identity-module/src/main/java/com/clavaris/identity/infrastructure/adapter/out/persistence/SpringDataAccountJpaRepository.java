package com.clavaris.identity.infrastructure.adapter.out.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data's own repository interface — kept separate from the outbound port ({@code
 * AccountRepository}) so the port stays framework-free.
 */
interface SpringDataAccountJpaRepository extends JpaRepository<AccountEntity, UUID> {

  boolean existsByOrganizationIdAndEmail(UUID organizationId, String email);

  Optional<AccountEntity> findByOrganizationIdAndEmail(UUID organizationId, String email);
}
