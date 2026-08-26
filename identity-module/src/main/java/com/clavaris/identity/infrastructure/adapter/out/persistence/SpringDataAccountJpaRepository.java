package com.clavaris.identity.infrastructure.adapter.out.persistence;

import java.util.List;
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

  // TD-SEC-031: full entities, not an id-only projection — "correct and simple first," same
  // precedent as OrganizationCapacityRateLimitingFilter's own Javadoc for reading a whole row over
  // a real DB read where the caller (JpaAccountRepository) already has a toDomain(...) mapper to
  // reuse. This is a bounded, delete-time-only read, not a hot path.
  List<AccountEntity> findByOrganizationId(UUID organizationId);

  // BR-DATA-02/03's own organization-level equivalent: Spring Data's own deleteBy/deleteAllBy
  // query derivation, natively a real DELETE, no @Modifying needed. Cascades at the database
  // level (migration V20260826100000) to password_credentials/sessions/refresh_tokens/
  // verification_tokens for every Account this removes.
  void deleteAllByOrganizationId(UUID organizationId);
}
