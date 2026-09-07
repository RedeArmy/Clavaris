package com.clavaris.identity.infrastructure.adapter.out.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data's own repository interface — kept separate from the outbound port ({@code
 * AccountRepository}) so the port stays framework-free.
 */
interface SpringDataAccountJpaRepository extends JpaRepository<AccountEntity, UUID> {

  boolean existsByOrganizationIdAndEmail(UUID organizationId, String email);

  Optional<AccountEntity> findByOrganizationIdAndEmail(UUID organizationId, String email);

  // ADR-0024 §4
  boolean existsByOrganizationIdAndUsername(UUID organizationId, String username);

  Optional<AccountEntity> findByOrganizationIdAndUsername(UUID organizationId, String username);

  // Code review finding (2026-09-01): a scalar projection, not the full entity + its own separate
  // password_credentials round trip findById()/toDomain() costs — for the several callers (e.g.
  // RevokeAccountSessionService, RotateRefreshTokenService) that only ever need organizationId,
  // the only fact a bare AccountId can't carry on its own.
  @Query("select a.organizationId from AccountEntity a where a.id = :accountId")
  Optional<UUID> findOrganizationIdById(@Param("accountId") UUID accountId);

  // TD-PERF-016: revisits TD-SEC-031's own original "full entities, not an id-only projection —
  // correct and simple first... this is a bounded, delete-time-only read, not a hot path" call.
  // Still true that it's not a hot path; not true that the cost is bounded — the caller
  // (OrganizationIdentityDataEraserBridge) only ever reads AccountId, but the prior version
  // (findByOrganizationId, returning full entities) forced JpaAccountRepository#toDomain to run a
  // second, separate password_credentials query per row, an N+1 that scales with the Organization's
  // own total account count on the single most destructive operation this system exposes. Same
  // scalar-projection fix findOrganizationIdById already established above for the identical shape
  // of over-fetch.
  @Query("select a.id from AccountEntity a where a.organizationId = :organizationId")
  List<UUID> findIdsByOrganizationId(@Param("organizationId") UUID organizationId);

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
