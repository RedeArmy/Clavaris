package com.clavaris.organization.infrastructure.adapter.out.persistence;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

// existsById(UUID) is already declared by CrudRepository — nothing to add for it.
// PMD.AvoidDuplicateLiterals: the repeated string is "PMD.LongVariable" itself, applied on four
// separate ownerPlatformAccountId parameters — same ContentSecurityPolicyHeaderWriter precedent
// for an identical situation.
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
interface SpringDataOrganizationJpaRepository extends JpaRepository<OrganizationEntity, UUID> {

  List<OrganizationEntity> findAllByOwnerPlatformAccountId(
      @SuppressWarnings("PMD.LongVariable") UUID ownerPlatformAccountId);

  // TD-PERF-020 (keyset revision, 2026-09-14): backs OrganizationRepository#findKeysetPageOwnedBy
  // — three @Query methods (first/after/before), not a derived-name Page-returning method, since
  // a compound (createdAt, id) seek predicate has no Spring Data method-name equivalent. Pageable
  // here only ever supplies the LIMIT (page 0, size = requested + 1) — ORDER BY is explicit in
  // each query, so Pageable itself carries no Sort. Fetching size + 1 rows (not size) is how the
  // caller learns whether a further page exists without a second COUNT(*) query.
  @Query(
      """
      SELECT o FROM OrganizationEntity o
      WHERE o.ownerPlatformAccountId = :ownerPlatformAccountId
      ORDER BY o.createdAt DESC, o.id DESC
      """)
  List<OrganizationEntity> findFirstPageOwnedBy(
      @Param("ownerPlatformAccountId") @SuppressWarnings("PMD.LongVariable")
          UUID ownerPlatformAccountId,
      Pageable pageable);

  @Query(
      """
      SELECT o FROM OrganizationEntity o
      WHERE o.ownerPlatformAccountId = :ownerPlatformAccountId
        AND (o.createdAt < :cursorCreatedAt
             OR (o.createdAt = :cursorCreatedAt AND o.id < :cursorId))
      ORDER BY o.createdAt DESC, o.id DESC
      """)
  List<OrganizationEntity> findPageOwnedByAfter(
      @Param("ownerPlatformAccountId") @SuppressWarnings("PMD.LongVariable")
          UUID ownerPlatformAccountId,
      @Param("cursorCreatedAt") Instant cursorCreatedAt,
      @Param("cursorId") UUID cursorId,
      Pageable pageable);

  // Ascending — the only order a LIMIT can express "the rows immediately preceding the cursor"
  // in; SpringDataKeysetPageMapper#backward reverses the result back to newest-first.
  @Query(
      """
      SELECT o FROM OrganizationEntity o
      WHERE o.ownerPlatformAccountId = :ownerPlatformAccountId
        AND (o.createdAt > :cursorCreatedAt
             OR (o.createdAt = :cursorCreatedAt AND o.id > :cursorId))
      ORDER BY o.createdAt ASC, o.id ASC
      """)
  List<OrganizationEntity> findPageOwnedByBefore(
      @Param("ownerPlatformAccountId") @SuppressWarnings("PMD.LongVariable")
          UUID ownerPlatformAccountId,
      @Param("cursorCreatedAt") Instant cursorCreatedAt,
      @Param("cursorId") UUID cursorId,
      Pageable pageable);
}
