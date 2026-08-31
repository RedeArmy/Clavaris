package com.clavaris.identity.infrastructure.adapter.out.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

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

  // BR-ID-02 ("never zero auth methods") integrity check, code review finding — see
  // AccountAuthMethodIntegrityCheckJob's own Javadoc for why this can only be a periodic sweep,
  // not a synchronous save()-time guard. A native cross-table query, not a derived one:
  // social_identities belongs to a sibling repository/aggregate, and this is read-only,
  // low-frequency (daily), diagnostic-only SQL — not a hot path this codebase's own
  // toDomain()-mapping convention needs to apply to.
  @Query(
      value =
          "SELECT COUNT(*) FROM accounts a "
              + "WHERE NOT EXISTS (SELECT 1 FROM password_credentials pc WHERE pc.account_id = a.id) "
              + "AND NOT EXISTS (SELECT 1 FROM social_identities si WHERE si.account_id = a.id)",
      nativeQuery = true)
  long countAccountsWithNoAuthMethod();
}
