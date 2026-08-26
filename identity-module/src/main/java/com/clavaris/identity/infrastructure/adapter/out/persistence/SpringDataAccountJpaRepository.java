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

  // BR-DATA-02/03's own organization-level equivalent: Spring Data's own deleteBy/deleteAllBy
  // query derivation, natively a real DELETE, no @Modifying needed. Cascades at the database
  // level (migration V20260826100000) to password_credentials/sessions/refresh_tokens/
  // verification_tokens for every Account this removes.
  void deleteAllByOrganizationId(UUID organizationId);
}
