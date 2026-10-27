package com.clavaris.identity.infrastructure.adapter.out.persistence;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data's own repository interface — kept separate from the outbound port ({@code
 * AccountRepository}) so the port stays framework-free.
 */
// PMD.TooManyMethods: TD-PERF-020's own three keyset-pagination query methods pushed this past
// the default threshold — every method here is a genuinely distinct, cohesive query this port's
// several consumers actually need, same "wiring, not sprawl" reasoning AccountRepository's own
// class-level suppression already documents. PMD.AvoidDuplicateLiterals: the repeated string is
// "organizationId" itself, the join column every one of these queries scopes by — same false
// positive SpringDataOrganizationJpaRepository's own identical suppression documents.
@SuppressWarnings({
  "PMD.TooManyMethods",
})
interface SpringDataAccountJpaRepository extends JpaRepository<AccountEntity, UUID> {

  // TD-ARCH-036: narrow sign-in writes (see AccountRepository#recordSignIn). A bulk update does not
  // load or merge the entity, so it cannot carry stale state over another writer's change; the
  // persistence context is flushed first and cleared after so a later read in the same transaction
  // sees the new value.
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query("UPDATE AccountEntity a SET a.lastSignedInAt = :signedInAt WHERE a.id = :accountId")
  int updateLastSignedInAt(
      @Param("accountId") UUID accountId, @Param("signedInAt") Instant signedInAt);

  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      """
      UPDATE AccountEntity a
      SET a.lastSignedInAt = :signedInAt,
          a.emailVerifiedAt = COALESCE(a.emailVerifiedAt, :verifiedAt)
      WHERE a.id = :accountId
      """)
  int updateLastSignedInAtVerifyingEmail(
      @Param("accountId") UUID accountId,
      @Param("signedInAt") Instant signedInAt,
      @Param("verifiedAt") Instant verifiedAt);

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

  // TD-PERF-027: bounded, caller-known id set — see AccountRepository#findAllByOrganizationIdAndIds
  // own Javadoc for why this exists alongside OrganizationAccountDirectory's own unbounded variant.
  List<AccountEntity> findByOrganizationIdAndIdIn(UUID organizationId, Collection<UUID> ids);

  // TD-PERF-001 (closed): BR-ID-02 ("never zero auth methods") integrity check, code review
  // finding — see AccountAuthMethodIntegrityCheckJob's own Javadoc for why this can only be a
  // periodic sweep, not a synchronous save()-time guard. Chunked in two queries per page (this one
  // resolves the page's own id boundaries; countOrphansAmongIds below counts within them) rather
  // than one unscoped full-table scan — accounts.id (a UUID) gives no meaningful ordering, but any
  // stable total order covers every row exactly once across chunks, which is all a bounded,
  // resumable sweep needs. afterId is null for the very first chunk — the (:afterId IS NULL OR
  // ...) form lets one query serve both the first and every following page, rather than a separate
  // "first page" query. A native cross-table query, not a derived one: social_identities belongs
  // to a sibling repository/aggregate, and this is read-only, low-frequency (daily),
  // diagnostic-only SQL — not a hot path this codebase's own toDomain()-mapping convention needs
  // to apply to.
  @Query(
      value =
          "SELECT a.id FROM accounts a WHERE (:afterId IS NULL OR a.id > :afterId) "
              + "ORDER BY a.id LIMIT :chunkSize",
      nativeQuery = true)
  List<UUID> findAccountIdsForIntegrityCheckChunk(
      @Param("afterId") UUID afterId, @Param("chunkSize") int chunkSize);

  // TD-PERF-001: the orphan count within one chunk's own id set, resolved by
  // findAccountIdsForIntegrityCheckChunk above — see that method's own Javadoc.
  @Query(
      value =
          "SELECT COUNT(*) FROM accounts a WHERE a.id IN (:ids) "
              + "AND NOT EXISTS (SELECT 1 FROM password_credentials pc WHERE pc.account_id = a.id) "
              + "AND NOT EXISTS (SELECT 1 FROM social_identities si WHERE si.account_id = a.id)",
      nativeQuery = true)
  long countOrphansAmongIds(@Param("ids") Collection<UUID> ids);

  // SDE-III review, 2026-09-19 — Clerk dashboard "Users" tab parity: backs
  // AccountRepository#findKeysetPageByOrganizationId, same keyset-pagination shape TD-PERF-020
  // already established for OAuthClient/Workspace lists.
  //
  // ADR-0029, TD-PERF-041: a search is a SEPARATE set of three queries (…Searching), not an
  // "AND (:term IS NULL OR …)" clause on these. With the optional clause the planner cannot keep a
  // trigram index in play once Postgres switches a prepared statement to a generic plan after five
  // executions (measured: a rare term went from 0.3 ms to 215 ms and stayed there); a query that
  // always has a pattern keeps the BitmapOr over the four pg_trgm indexes in either plan.
  @Query(
      """
      SELECT a FROM AccountEntity a
      WHERE a.organizationId = :organizationId
      ORDER BY a.createdAt DESC, a.id DESC
      """)
  List<AccountEntity> findFirstPageByOrganizationId(
      @Param("organizationId") UUID organizationId, Pageable pageable);

  @Query(
      """
      SELECT a FROM AccountEntity a
      WHERE a.organizationId = :organizationId
        AND (a.createdAt < :cursorCreatedAt
             OR (a.createdAt = :cursorCreatedAt AND a.id < :cursorId))
      ORDER BY a.createdAt DESC, a.id DESC
      """)
  List<AccountEntity> findPageByOrganizationIdAfter(
      @Param("organizationId") UUID organizationId,
      @Param("cursorCreatedAt") Instant cursorCreatedAt,
      @Param("cursorId") UUID cursorId,
      Pageable pageable);

  @Query(
      """
      SELECT a FROM AccountEntity a
      WHERE a.organizationId = :organizationId
        AND (a.createdAt > :cursorCreatedAt
             OR (a.createdAt = :cursorCreatedAt AND a.id > :cursorId))
      ORDER BY a.createdAt ASC, a.id ASC
      """)
  List<AccountEntity> findPageByOrganizationIdBefore(
      @Param("organizationId") UUID organizationId,
      @Param("cursorCreatedAt") Instant cursorCreatedAt,
      @Param("cursorId") UUID cursorId,
      Pageable pageable);

  // The search variants. :pattern is a ready LIKE pattern (see JpaAccountRepository#likePattern,
  // which escapes the term's own % _ and the escape character !). LOWER() is applied on both sides
  // in the database, as before, so case folding follows the database's own locale rules. Each
  // LOWER(column) is exactly
  // the expression a pg_trgm GIN index in V20261026120000 is built on.
  @Query(
      """
      SELECT a FROM AccountEntity a
      WHERE a.organizationId = :organizationId
        AND (LOWER(a.firstName) LIKE LOWER(:pattern) ESCAPE '!'
             OR LOWER(a.lastName) LIKE LOWER(:pattern) ESCAPE '!'
             OR LOWER(a.username) LIKE LOWER(:pattern) ESCAPE '!'
             OR LOWER(a.email) LIKE LOWER(:pattern) ESCAPE '!')
      ORDER BY a.createdAt DESC, a.id DESC
      """)
  List<AccountEntity> findFirstPageByOrganizationIdSearching(
      @Param("organizationId") UUID organizationId,
      @Param("pattern") String pattern,
      Pageable pageable);

  @Query(
      """
      SELECT a FROM AccountEntity a
      WHERE a.organizationId = :organizationId
        AND (a.createdAt < :cursorCreatedAt
             OR (a.createdAt = :cursorCreatedAt AND a.id < :cursorId))
        AND (LOWER(a.firstName) LIKE LOWER(:pattern) ESCAPE '!'
             OR LOWER(a.lastName) LIKE LOWER(:pattern) ESCAPE '!'
             OR LOWER(a.username) LIKE LOWER(:pattern) ESCAPE '!'
             OR LOWER(a.email) LIKE LOWER(:pattern) ESCAPE '!')
      ORDER BY a.createdAt DESC, a.id DESC
      """)
  List<AccountEntity> findPageByOrganizationIdAfterSearching(
      @Param("organizationId") UUID organizationId,
      @Param("cursorCreatedAt") Instant cursorCreatedAt,
      @Param("cursorId") UUID cursorId,
      @Param("pattern") String pattern,
      Pageable pageable);

  @Query(
      """
      SELECT a FROM AccountEntity a
      WHERE a.organizationId = :organizationId
        AND (a.createdAt > :cursorCreatedAt
             OR (a.createdAt = :cursorCreatedAt AND a.id > :cursorId))
        AND (LOWER(a.firstName) LIKE LOWER(:pattern) ESCAPE '!'
             OR LOWER(a.lastName) LIKE LOWER(:pattern) ESCAPE '!'
             OR LOWER(a.username) LIKE LOWER(:pattern) ESCAPE '!'
             OR LOWER(a.email) LIKE LOWER(:pattern) ESCAPE '!')
      ORDER BY a.createdAt ASC, a.id ASC
      """)
  List<AccountEntity> findPageByOrganizationIdBeforeSearching(
      @Param("organizationId") UUID organizationId,
      @Param("cursorCreatedAt") Instant cursorCreatedAt,
      @Param("cursorId") UUID cursorId,
      @Param("pattern") String pattern,
      Pageable pageable);
}
